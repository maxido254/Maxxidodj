package com.example.djapp

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A detected musical key. [tonic] 0 = C ... 11 = B. */
class KeyResult(val tonic: Int, val minor: Boolean, val confidence: Float) {
    /** e.g. "Am", "F#", "Bbm" style names (sharps only). */
    val name: String get() = KeyDetector.NOTES[tonic] + if (minor) "m" else ""
    /** Camelot wheel code used by DJs, e.g. "8A" (A = minor, B = major). */
    val camelot: String get() = KeyDetector.camelot(tonic, minor)
}

/**
 * Offline key detection: averaged chroma (with tuning correction) matched against the
 * Krumhansl-Kessler major/minor profiles. Works on the same interleaved 16-bit stereo PCM files
 * the rest of the app uses. It is an estimate: relative major/minor mix-ups (e.g. Am vs C) happen,
 * and a track that changes key can only report one.
 */
object KeyDetector {
    val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private const val N = 16384          // FFT size
    private const val HOP = 8192
    private const val MIN_HZ = 100.0     // below this the kick/sub dominate and the bins are too coarse
    private const val MAX_HZ = 2000.0

    /** Camelot code for a key (C major = 8B, A minor = 8A). A key and its relative major/minor share the number. */
    fun camelot(tonic: Int, minor: Boolean): String {
        val major = if (minor) (tonic + 3) % 12 else tonic      // relative major shares the number
        val num = ((major * 7) % 12 + 7) % 12 + 1
        return "$num${if (minor) "A" else "B"}"
    }

    /** Sort key for the Camelot wheel: 1A, 1B, 2A, 2B ... 12B. Unknown sorts last. */
    fun camelotOrder(code: String): Int {
        if (code.length < 2) return Int.MAX_VALUE
        val n = code.dropLast(1).toIntOrNull() ?: return Int.MAX_VALUE
        return n * 2 + if (code.last() == 'B') 1 else 0
    }

    fun detect(pcm: File, rate: Int): KeyResult? = try { analyze(pcm, rate) } catch (_: Exception) { null }

    private fun analyze(pcm: File, rate: Int): KeyResult? {
        val total = pcm.length() / 4
        // Same idea as the BPM detector: skip intros/outros on long tracks, look at up to 90 s.
        val start = if (total > rate * 130L) rate * 30L else 0L
        val count = min(total - start, rate * 90L).toInt()
        if (count < N * 2) return null

        val mono = FloatArray(count)
        RandomAccessFile(pcm, "r").use { raf ->
            raf.seek(start * 4)
            val chunk = 32768
            val buf = ByteArray(chunk * 4)
            var done = 0
            while (done < count) {
                val n = min(chunk, count - done)
                raf.readFully(buf, 0, n * 4)
                val sb = ByteBuffer.wrap(buf, 0, n * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                for (i in 0 until n) mono[done + i] = (sb.get(2 * i) + sb.get(2 * i + 1)) * (0.5f / 32768f)
                done += n
            }
        }

        val fft = Fft(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val re = FloatArray(N); val im = FloatArray(N)

        // Map each FFT bin to a 1/3-semitone chroma bin (36 per octave, bin 0 = A 440 Hz and its octaves).
        val loBin = max(1, (MIN_HZ * N / rate).toInt())
        val hiBin = min(N / 2 - 1, (MAX_HZ * N / rate).toInt())
        val binMap = IntArray(hiBin + 1) { -1 }
        for (k in loBin..hiBin) {
            val f = k.toDouble() * rate / N
            val pos = 36.0 * ln(f / 440.0) / ln(2.0)
            binMap[k] = ((Math.round(pos) % 36 + 36) % 36).toInt()
        }

        val chroma36 = DoubleArray(36)
        var frames = 0
        var p = 0
        while (p + N <= count) {
            var energy = 0.0
            for (i in 0 until N) { val v = mono[p + i] * window[i]; re[i] = v; im[i] = 0f; energy += v * v }
            if (energy / N > 1e-7) {                           // skip near-silence
                fft.run(re, im)
                for (k in loBin..hiBin) {
                    val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
                    chroma36[binMap[k]] += sqrt(mag)           // compress so one loud partial doesn't dominate
                }
                frames++
            }
            p += HOP
        }
        if (frames < 4) return null

        // Tuning: choose which of the 3 sub-semitone phases the music actually sits on.
        var bestPhase = 0; var bestE = -1.0
        for (o in 0..2) {
            var e = 0.0
            for (i in 0 until 12) e += chroma36[(3 * i + o) % 36]
            if (e > bestE) { bestE = e; bestPhase = o }
        }
        val shift = if (bestPhase == 2) -1 else bestPhase    // phase 2 means "one third-semitone flat", not "two sharp"
        val semi = DoubleArray(12)                             // index = semitones above A
        for (i in 0 until 12) {
            var s = 0.0
            for (d in -1..1) s += chroma36[((3 * i + shift + d) % 36 + 36) % 36]
            semi[i] = s
        }
        val chroma = DoubleArray(12) { semi[(it + 3) % 12] }   // re-index so 0 = C (C is 3 semitones above A)

        // Match against all 24 keys.
        var best = -2.0; var second = -2.0; var bestTonic = 0; var bestMinor = false
        for (t in 0 until 12) for (minor in booleanArrayOf(false, true)) {
            val prof = if (minor) MINOR else MAJOR
            val c = pearson(chroma, prof, t)
            if (c > best) { second = best; best = c; bestTonic = t; bestMinor = minor }
            else if (c > second) second = c
        }
        return KeyResult(bestTonic, bestMinor, (best - second).toFloat().coerceIn(0f, 1f))
    }

    /** Correlation of [chroma] rotated so that [tonic] is at index 0 against [profile]. */
    private fun pearson(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        var mx = 0.0; var my = 0.0
        for (i in 0 until 12) { mx += chroma[(tonic + i) % 12]; my += profile[i] }
        mx /= 12; my /= 12
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until 12) {
            val x = chroma[(tonic + i) % 12] - mx; val y = profile[i] - my
            sxy += x * y; sxx += x * x; syy += y * y
        }
        return if (sxx <= 0.0 || syy <= 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }

    /** Small in-place radix-2 FFT. */
    private class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
        private val rev = IntArray(n).also { t ->
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) { var x = i; var r = 0; for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }; t[i] = r }
        }

        fun run(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val half = len shr 1; val step = n / len
                var i = 0
                while (i < n) {
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
    }
}
