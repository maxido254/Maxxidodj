package com.example.djapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI stem separation: HT-Demucs (MIT licence) exported to ONNX, run on the phone's CPU, fully offline.
 * The ~166 MB model ships inside the APK (assets/htdemucs_fp16.onnx, fetched at build time by the
 * `fetchDemucsModel` Gradle task) and is unpacked once into app storage on first use.
 * Any failure (model missing, out of memory, ...) returns false so the caller can fall back.
 */
class DemucsSeparator(private val ctx: Context) : StemSeparator {
    override val id = "demucs"

    private companion object {
        const val TAG = "DemucsSeparator"
        const val MODEL_NAME = "htdemucs_fp16.onnx"
        const val MIN_MODEL_BYTES = 100_000_000L
        const val SEG = 343980            // samples per model segment @ 44.1 kHz
        const val MODEL_RATE = 44100
        // model output order is drums, bass, other, vocals; the app wants vocals, drums, bass, other
        val MODEL_INDEX = intArrayOf(3, 0, 1, 2)
    }

    override fun separate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        return try {
            doSeparate(mix, rate, outDir, progress, cancelled)
        } catch (t: Throwable) {
            Log.e(TAG, "AI separation failed", t)
            false
        }
    }

    /** Returns the model file in app storage, unpacking it from the APK's assets the first time. */
    private fun ensureModel(progress: (Float) -> Unit, cancelled: () -> Boolean): File? {
        val f = File(ctx.filesDir, MODEL_NAME)
        if (f.exists() && f.length() > MIN_MODEL_BYTES) return f
        val part = File(ctx.filesDir, "$MODEL_NAME.part")
        try {
            // Stored uncompressed in the APK (see noCompress in build.gradle.kts), so the exact size is known.
            val total = try { ctx.assets.openFd(MODEL_NAME).use { it.length } } catch (_: Exception) { 166_000_000L }
            var got = 0L
            var lastPct = -1
            ctx.assets.open(MODEL_NAME).use { inp ->
                FileOutputStream(part).use { fos ->
                    BufferedOutputStream(fos, 256 * 1024).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled()) { part.delete(); return null }
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val frac = (got.toFloat() / total).coerceIn(0f, 1f)
                            progress(0.05f * frac)
                            val pct = (frac * 100).toInt()
                            if (pct != lastPct) { lastPct = pct; AppSettings.modelStatus = "unpacking AI model $pct%" }
                        }
                    }
                }
            }
            if (part.length() < MIN_MODEL_BYTES || !part.renameTo(f)) { part.delete(); return null }
            return f
        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "AI model is not bundled in this build (assets/$MODEL_NAME missing)", e)
            part.delete()
            return null
        } finally {
            AppSettings.modelStatus = ""
        }
    }

    private fun resample(src: FloatArray, srcLen: Int, dstLen: Int): FloatArray {
        val out = FloatArray(dstLen)
        if (srcLen == dstLen) { System.arraycopy(src, 0, out, 0, dstLen); return out }
        val ratio = (srcLen - 1).toDouble() / max(dstLen - 1, 1)
        for (i in 0 until dstLen) {
            val x = i * ratio
            val i0 = x.toInt()
            val fr = (x - i0).toFloat()
            val i1 = min(i0 + 1, srcLen - 1)
            out[i] = src[i0] * (1f - fr) + src[i1] * fr
        }
        return out
    }

    private fun doSeparate(mix: File, rate: Int, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val model = ensureModel(progress, cancelled) ?: return false
        if (cancelled()) return false

        val totalFrames = mix.length() / 4
        if (totalFrames < 1) return false

        // Pass 1: level statistics of the mono mix (Demucs normalises its input by them).
        var sum = 0.0; var sumSq = 0.0
        RandomAccessFile(mix, "r").use { raf ->
            val bytes = ByteArray(65536 * 4)
            while (true) {
                val n = raf.read(bytes)
                if (n < 4) break
                val sb = ByteBuffer.wrap(bytes, 0, n - n % 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                var i = 0
                while (i + 1 < sb.limit()) {
                    val m = (sb.get(i) + sb.get(i + 1)) * 0.5 / 32768.0
                    sum += m; sumSq += m * m
                    i += 2
                }
            }
        }
        val mean = sum / totalFrames
        val std = max(sqrt(max(sumSq / totalFrames - mean * mean, 0.0)), 1e-4).toFloat()
        val meanF = mean.toFloat()

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4))
        val session = env.createSession(model.absolutePath, opts)
        val inputName = session.inputNames.iterator().next()

        val ms = max(1, Math.round(SEG.toDouble() * rate / MODEL_RATE).toInt())   // segment length in source frames
        val os = ms / 4                                                            // overlap (source frames)
        val stride = ms - os
        val tail = Array(4) { FloatArray(2 * os) }
        val outs = STEM_NAMES.map { BufferedOutputStream(File(outDir, "$it.pcm").outputStream(), 256 * 1024) }
        var ok = false
        try {
            RandomAccessFile(mix, "r").use { raf ->
                val inBytes = ByteArray(ms * 4)
                var p = 0L
                var k = 0
                while (true) {
                    if (cancelled()) return false
                    val isLast = p + ms >= totalFrames
                    val valid = min(ms.toLong(), totalFrames - p).toInt()

                    // read + de-interleave (zero padded)
                    raf.seek(p * 4)
                    java.util.Arrays.fill(inBytes, 0)
                    var read = 0
                    while (read < valid * 4) {
                        val n = raf.read(inBytes, read, valid * 4 - read)
                        if (n < 0) break
                        read += n
                    }
                    val sb = ByteBuffer.wrap(inBytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                    val l = FloatArray(ms); val r = FloatArray(ms)
                    for (i in 0 until valid) { l[i] = sb.get(2 * i) / 32768f; r[i] = sb.get(2 * i + 1) / 32768f }
                    val lm = resample(l, ms, SEG); val rm = resample(r, ms, SEG)

                    val fb = ByteBuffer.allocateDirect(2 * SEG * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    for (i in 0 until SEG) fb.put((lm[i] - meanF) / std)
                    for (i in 0 until SEG) fb.put((rm[i] - meanF) / std)
                    fb.rewind()

                    val y = Array(4) { arrayOf(FloatArray(ms), FloatArray(ms)) }
                    val tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 2, SEG.toLong()))
                    try {
                        session.run(mapOf(inputName to tensor)).use { res ->
                            val outT = res.get(0) as OnnxTensor
                            val fbuf = outT.floatBuffer
                            val all = FloatArray(8 * SEG)
                            fbuf.rewind(); fbuf.get(all)
                            for (s in 0 until 4) for (c in 0 until 2) {
                                val seg = FloatArray(SEG)
                                val base = (MODEL_INDEX[s] * 2 + c) * SEG
                                for (i in 0 until SEG) seg[i] = all[base + i] * std
                                y[s][c] = resample(seg, SEG, ms)
                            }
                        }
                    } finally { tensor.close() }

                    // overlap-add and write
                    val emit = if (isLast) valid else stride
                    var i0 = 0
                    val ob = Array(4) { ByteBuffer.allocate(4 * 4096).order(ByteOrder.nativeOrder()) }
                    while (i0 < emit) {
                        val cnt = min(4096, emit - i0)
                        for (s in 0 until 4) {
                            ob[s].clear()
                            for (j in 0 until cnt) {
                                val i = i0 + j
                                var vl = y[s][0][i]; var vr = y[s][1][i]
                                if (k > 0 && i < os) {
                                    val w = i.toFloat() / os
                                    vl = vl * w + tail[s][i]
                                    vr = vr * w + tail[s][os + i]
                                }
                                ob[s].putShort(toShort(vl)); ob[s].putShort(toShort(vr))
                            }
                            outs[s].write(ob[s].array(), 0, cnt * 4)
                        }
                        i0 += cnt
                    }
                    if (!isLast) {
                        for (s in 0 until 4) for (i in 0 until os) {
                            val w = 1f - i.toFloat() / os
                            tail[s][i] = y[s][0][stride + i] * w
                            tail[s][os + i] = y[s][1][stride + i] * w
                        }
                    }
                    p += stride
                    k++
                    progress(0.05f + 0.95f * (min(p, totalFrames).toFloat() / totalFrames))
                    if (isLast) break
                }
            }
            ok = true
        } finally {
            outs.forEach { try { it.close() } catch (_: Exception) {} }
            try { session.close() } catch (_: Exception) {}
            try { opts.close() } catch (_: Exception) {}
        }
        return ok
    }

    private fun toShort(v: Float): Short {
        val x = Math.round(v * 32768f)
        return (if (x > 32767) 32767 else if (x < -32768) -32768 else x).toShort()
    }
}
