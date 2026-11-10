package com.example.djapp

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stem order used everywhere (file names + engine): 0 vocals, 1 drums, 2 bass, 3 other (= melody / instruments). */
val STEM_NAMES = listOf("vocals", "drums", "bass", "other")
/** Labels shown to the user, in engine order. */
val STEM_LABELS = listOf("VOCAL", "DRUMS", "BASS", "MELODY")

interface StemSeparator {
    val id: String
    /** Reads interleaved stereo int16 [mix], writes vocals/drums/bass/other .pcm into [outDir]. */
    fun separate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean
}

private class Biquad(lowpass: Boolean, fc: Double, fs: Double) {
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var z1 = 0.0; private var z2 = 0.0
    init {
        val w = 2 * Math.PI * minOf(fc, fs * 0.45) / fs
        val c = Math.cos(w); val al = Math.sin(w) / (2 * 0.7071); val a0 = 1 + al
        if (lowpass) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0 } else { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0 }
        b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0
    }
    fun p(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
}

/**
 * FAST mode: cheap filter-based split that runs in seconds even on budget CPUs.
 * Bass = <150 Hz, Vocals = centre-panned mids, Drums = highs, Other = the rest.
 * The 4 stems always add back up to the original. It is NOT AI separation.
 */
class LiteSeparator : StemSeparator {
    override val id = "lite"

    override fun separate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val fs = rate.toDouble()
        val bassL1 = Biquad(true, 150.0, fs); val bassL2 = Biquad(true, 150.0, fs)
        val bassR1 = Biquad(true, 150.0, fs); val bassR2 = Biquad(true, 150.0, fs)
        val vocHP = Biquad(false, 200.0, fs); val vocLP = Biquad(true, 4000.0, fs)
        val drumL = Biquad(false, 4500.0, fs); val drumR = Biquad(false, 4500.0, fs)

        val frames = 16384
        val inBuf = ByteArray(frames * 4)
        val outBytes = Array(4) { ByteArray(frames * 4) }
        val outShorts = Array(4) { ByteBuffer.wrap(outBytes[it]).order(ByteOrder.nativeOrder()).asShortBuffer() }
        val total = mix.length().coerceAtLeast(1); var done = 0L
        val outs = STEM_NAMES.map { BufferedOutputStream(File(outDir, "$it.pcm").outputStream(), 64 * 1024) }
        try {
            FileInputStream(mix).use { inp ->
                while (true) {
                    if (cancelled()) return false
                    val n = readFully(inp, inBuf)
                    if (n < 4) break
                    val fr = n / 4
                    val sb = ByteBuffer.wrap(inBuf, 0, fr * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (i in 0 until fr) {
                        val l = sb.get(2 * i).toDouble(); val r = sb.get(2 * i + 1).toDouble()
                        val bl = bassL2.p(bassL1.p(l)); val br = bassR2.p(bassR1.p(r))
                        val rl = l - bl; val rr = r - br
                        val vc = vocLP.p(vocHP.p((rl + rr) * 0.5))
                        val dl = drumL.p(rl); val dr = drumR.p(rr)
                        put(outShorts[0], i, vc, vc)
                        put(outShorts[1], i, dl, dr)
                        put(outShorts[2], i, bl, br)
                        put(outShorts[3], i, rl - vc - dl, rr - vc - dr)
                    }
                    for (s in 0 until 4) outs[s].write(outBytes[s], 0, fr * 4)
                    done += n; progress((done.toFloat() / total).coerceAtMost(1f))
                }
            }
        } finally { outs.forEach { it.close() } }
        return true
    }

    private fun put(b: java.nio.ShortBuffer, i: Int, l: Double, r: Double) {
        b.put(2 * i, l.coerceIn(-32768.0, 32767.0).toInt().toShort())
        b.put(2 * i + 1, r.coerceIn(-32768.0, 32767.0).toInt().toShort())
    }

    private fun readFully(inp: InputStream, buf: ByteArray): Int {
        var t = 0
        while (t < buf.size) { val n = inp.read(buf, t, buf.size - t); if (n < 0) break; t += n }
        return t
    }
}


/**
 * BETTER mode: STFT-based separation (no AI model needed, runs offline on-device).
 *  - Drums    = percussive part (HPSS: median filter across frequency vs. across time, soft Wiener masks)
 *  - Bass     = harmonic energy below ~150 Hz
 *  - Vocals   = harmonic, centre-panned (mid/side ratio), 120 Hz - 10 kHz
 *  - Other    = everything that is left (computed as mix minus the other three, so stems sum back exactly)
 * Streams the file frame by frame, so RAM use stays small.
 */
class HpssSeparator : StemSeparator {
    override val id = "hpss1"
    override fun separate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean =
        HpssJob(mix, rate, outDir, progress, cancelled, pro = false).run()
}

/**
 * PRO mode: the same idea as BETTER but built to be run once, ahead of time (see [PrepQueue]), so it can afford
 *  - a 4096-point FFT (twice the frequency resolution: bass, voice and instruments overlap far less),
 *  - a looser drum margin for the voice (consonants and vibrato are not mistaken for drums as often),
 *  - four-way masks that always add up to 1 (vocal / drums / bass / melody share each bin instead of fighting over it),
 *  - smoothing of the masks across frequency (less "bubbly" musical noise).
 * It is still classical signal processing, not a neural network: expect clearly cleaner results than BETTER,
 * but some bleed (that is the price of running on a budget phone with 4 GB of RAM).
 */
class ProSeparator : StemSeparator {
    override val id = "pro1"
    override fun separate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean =
        HpssJob(mix, rate, outDir, progress, cancelled, pro = true).run()
}

private class HpssJob(
    val mix: File, val rate: Int, val outDir: File,
    val progress: (Float) -> Unit, val cancelled: () -> Boolean, val pro: Boolean
) {
    val N = if (pro) 4096 else 2048           // FFT size
    val H = 1024                              // hop
    val B = N / 2 + 1                         // bins
    val T = 7                                 // time-median half width (frames); also frequency-median half width (bins)
    val R = 2 * T + 1                         // frames kept in context ring
    val win = FloatArray(N) { Math.sin(Math.PI * (it + 0.5) / N).toFloat() }
    val cosT = FloatArray(N / 2) { Math.cos(2 * Math.PI * it / N).toFloat() }
    val sinT = FloatArray(N / 2) { Math.sin(2 * Math.PI * it / N).toFloat() }
    val rev = IntArray(N).also { t ->
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) { var x = i; var r = 0; for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }; t[i] = r }
    }
    fun ss(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= N) {
            val half = len shr 1; val step = N / len
            var i = 0
            while (i < N) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = -sinT[k]; k += step
                    val xr = re[j + half]; val xi = im[j + half]
                    val vr = xr * wr - xi * wi; val vi = xr * wi + xi * wr
                    re[j + half] = re[j] - vr; im[j + half] = im[j] - vi
                    re[j] += vr; im[j] += vi
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun med(a: FloatArray): Float {
        for (i in 1 until R) { val v = a[i]; var j = i - 1; while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }; a[j + 1] = v }
        return a[T]
    }

    private val rb = ByteArray(N * 4)
    /** Reads [count] stereo frames starting at [start] (zero outside the file) as floats. */
    private fun readStereo(raf: RandomAccessFile, total: Long, start: Long, count: Int, l: FloatArray, r: FloatArray) {
        java.util.Arrays.fill(l, 0, count, 0f); java.util.Arrays.fill(r, 0, count, 0f)
        val from = maxOf(start, 0L); val to = minOf(start + count, total)
        if (to <= from) return
        val n = (to - from).toInt()
        raf.seek(from * 4); raf.readFully(rb, 0, n * 4)
        val sb = ByteBuffer.wrap(rb, 0, n * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
        val off = (from - start).toInt()
        for (i in 0 until n) { l[off + i] = sb.get(2 * i).toFloat(); r[off + i] = sb.get(2 * i + 1).toFloat() }
    }

    fun run(): Boolean {
        val fs = rate.toFloat()
        val total = mix.length() / 4
        val K = ((total + H - 1) / H).toInt()

        val lowW = FloatArray(B); val band = FloatArray(B)
        for (k in 0 until B) {
            val f = k * fs / N
            lowW[k] = when { f <= 110f -> 1f; f >= 190f -> 0f; else -> (0.5 * (1 + Math.cos(Math.PI * (f - 110) / 80))).toFloat() }
            band[k] = ss(120f, 220f, f) * (1f - ss(8000f, 12000f, f))
        }

        val mag = FloatArray(R * B); val zr = FloatArray(R * N); val zi = FloatArray(R * N)
        val xl = FloatArray(N); val xr = FloatArray(N); val re = FloatArray(N); val im = FloatArray(N)
        val mask = Array(3) { FloatArray(B) }                       // vocals, drums, bass
        val pv = FloatArray(B); val pd = FloatArray(B); val pb = FloatArray(B); val pmel = FloatArray(B)   // PRO: raw 4-way masks
        val acc = Array(3) { arrayOf(FloatArray(N), FloatArray(N)) } // [stem][channel]
        val tmp = FloatArray(R)
        val ol = FloatArray(H); val orr = FloatArray(H)
        val ob = Array(4) { ByteArray(H * 4) }
        val obs = Array(4) { ByteBuffer.wrap(ob[it]).order(ByteOrder.nativeOrder()).asShortBuffer() }
        val inv = (2f * H / N) / N                           // sine^2 windows at hop H sum to N / (2H)
        val outs = STEM_NAMES.map { BufferedOutputStream(File(outDir, "$it.pcm").outputStream(), 64 * 1024) }
        val raf = RandomAccessFile(mix, "r")

        fun clip(x: Int) = if (x < -32768) -32768 else if (x > 32767) 32767 else x

        fun processCenter(c: Int) {
            val sc = c % R
            for (k in 0 until B) {
                for (j in 0 until R) tmp[j] = mag[j * B + k]
                val hm = med(tmp)                                    // harmonic (smooth over time)
                for (j in 0 until R) tmp[j] = mag[sc * B + (k + j - T).coerceIn(0, B - 1)]
                val pm = med(tmp)                                    // percussive (smooth over frequency)
                val hh = 4f * hm * hm; val pp = pm * pm   // margin: only clearly percussive bins count as drums
                val mp = pp / (hh + pp + 1e-12f); val mh = 1f - mp

                val a = zr[sc * N + k]; val b = zi[sc * N + k]
                val j2 = (N - k) and (N - 1)
                val c2 = zr[sc * N + j2]; val d2 = zi[sc * N + j2]
                val lr = (a + c2) * 0.5f; val li = (b - d2) * 0.5f
                val rr = (b + d2) * 0.5f; val ri = (c2 - a) * 0.5f
                val mr = lr + rr; val mi = li + ri; val sr = lr - rr; val si = li - ri
                val em = mr * mr + mi * mi; val es = sr * sr + si * si
                val cv = ss(0.4f, 0.9f, em / (em + es + 1e-12f))      // how "centre" this bin is

                if (!pro) {
                    mask[0][k] = mh * (1f - lowW[k]) * band[k] * cv
                    mask[1][k] = mp
                    mask[2][k] = mh * lowW[k]
                } else {
                    // the voice only has to beat the drums by a normal margin, not a doubled one
                    val mpv = pp / (hm * hm + pp + 1e-12f)
                    val voice = (1f - mpv) * (1f - lowW[k]) * band[k] * cv
                    pv[k] = voice
                    pd[k] = mp
                    pb[k] = mh * lowW[k]
                    pmel[k] = mh * (1f - lowW[k]) * (1f - band[k] * cv)
                }
            }
            if (pro) {
                for (k in 0 until B) {            // smooth voice and melody across frequency
                    val a = pv[maxOf(k - 1, 0)]; val c = pv[minOf(k + 1, B - 1)]
                    val a2 = pmel[maxOf(k - 1, 0)]; val c2 = pmel[minOf(k + 1, B - 1)]
                    pv[k] = 0.25f * a + 0.5f * pv[k] + 0.25f * c
                    pmel[k] = 0.25f * a2 + 0.5f * pmel[k] + 0.25f * c2
                }
                for (k in 0 until B) {
                    val sum = pv[k] + pd[k] + pb[k] + pmel[k] + 1e-9f
                    mask[0][k] = pv[k] / sum; mask[1][k] = pd[k] / sum; mask[2][k] = pb[k] / sum
                }
            }
            for (s in 0 until 3) {
                val m = mask[s]
                for (n in 0 until N) {
                    val g = m[if (n < B) n else N - n]
                    re[n] = zr[sc * N + n] * g; im[n] = zi[sc * N + n] * g
                }
                fft(im, re)                                           // inverse FFT via swap trick: re/im now hold N*(real/imag)
                val aL = acc[s][0]; val aR = acc[s][1]
                for (n in 0 until N) { val w = win[n] * inv; aL[n] += re[n] * w; aR[n] += im[n] * w }
            }
        }

        fun emit(c: Int) {
            val s0 = (c - 1).toLong() * H
            if (s0 >= 0 && s0 < total) {
                val cnt = minOf(H.toLong(), total - s0).toInt()
                readStereo(raf, total, s0, cnt, ol, orr)
                for (i in 0 until cnt) for (ch in 0..1) {
                    val orig = (if (ch == 0) ol[i] else orr[i]).toInt()
                    val v = clip(Math.round(acc[0][ch][i])); val d = clip(Math.round(acc[1][ch][i])); val b = clip(Math.round(acc[2][ch][i]))
                    val o = clip(orig - v - d - b)                    // "other" = what is left, so the sum is exact
                    obs[0].put(2 * i + ch, v.toShort()); obs[1].put(2 * i + ch, d.toShort())
                    obs[2].put(2 * i + ch, b.toShort()); obs[3].put(2 * i + ch, o.toShort())
                }
                for (s in 0 until 4) outs[s].write(ob[s], 0, cnt * 4)
            }
            for (s in 0 until 3) for (ch in 0..1) {
                val a = acc[s][ch]
                System.arraycopy(a, H, a, 0, N - H); java.util.Arrays.fill(a, N - H, N, 0f)
            }
        }

        try {
            for (fk in 0..(K + T)) {
                if (cancelled()) return false
                readStereo(raf, total, (fk - 1).toLong() * H, N, xl, xr)
                for (n in 0 until N) { re[n] = xl[n] * win[n]; im[n] = xr[n] * win[n] }
                fft(re, im)
                val s = fk % R
                System.arraycopy(re, 0, zr, s * N, N); System.arraycopy(im, 0, zi, s * N, N)
                for (k in 0 until B) {
                    val j = (N - k) and (N - 1)
                    val a = re[k]; val b = im[k]; val c = re[j]; val d = im[j]
                    val lr = (a + c) * 0.5f; val li = (b - d) * 0.5f
                    val rr = (b + d) * 0.5f; val ri = (c - a) * 0.5f
                    mag[s * B + k] = (Math.sqrt((lr * lr + li * li).toDouble()).toFloat() + Math.sqrt((rr * rr + ri * ri).toDouble()).toFloat()) * 0.5f
                }
                if (fk >= T) {
                    val c = fk - T
                    processCenter(c); emit(c)
                    if (c % 8 == 0) progress(c.toFloat() / K)
                }
            }
            progress(1f)
        } finally { outs.forEach { it.close() }; raf.close() }
        return true
    }
}

object StemCache {
    /** Returns the 4 stem file paths (cached after the first time), or null if cancelled/failed. */
    fun prepare(ctx: Context, mix: File, rate: Int, keyBase: String,
                progress: (Float) -> Unit, cancelled: () -> Boolean,
                sep: StemSeparator = LiteSeparator()): Array<String>? {
        val root = File(ctx.cacheDir, "stems").apply { mkdirs() }
        val dir = File(root, "${keyBase}_${mix.length()}_${sep.id}")
        if (!ready(dir)) {
            val tmp = File(root, dir.name + ".tmp"); tmp.deleteRecursively(); tmp.mkdirs()
            if (!sep.separate(mix, rate, tmp, progress, cancelled)) { tmp.deleteRecursively(); return null }
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) return null
        }
        dir.setLastModified(System.currentTimeMillis())
        prune(root, 3)
        return STEM_NAMES.map { File(dir, "$it.pcm").absolutePath }.toTypedArray()
    }

    private fun ready(dir: File) = dir.isDirectory && STEM_NAMES.all { File(dir, "$it.pcm").length() > 0 }

    /** Stems are big (~200 MB per 5 min song), so keep only the newest few. */
    private fun prune(root: File, keep: Int) {
        root.listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.deleteRecursively() }
    }
}
