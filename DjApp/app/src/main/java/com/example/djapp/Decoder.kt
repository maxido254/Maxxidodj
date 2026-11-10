package com.example.djapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer

object Decoder {
    /**
     * Decodes any Android-supported audio file to interleaved 16-bit stereo PCM,
     * streaming it to [outFile] so RAM use stays tiny (important on 3-4 GB phones).
     * Returns the sample rate.
     * [startSec] / [maxSec] decode only a slice (maxSec <= 0 means "to the end"); used by the library analyser.
     */
    fun decode(ctx: Context, uri: Uri, outFile: File, startSec: Double = 0.0, maxSec: Double = 0.0): Int {
        val ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val idx = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
        }
        ex.selectTrack(idx)
        if (startSec > 0.0) ex.seekTo((startSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val fmt = ex.getTrackFormat(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var written = 0L
        BufferedOutputStream(outFile.outputStream(), 64 * 1024).use { os ->
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val src = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = info.size / 2 / ch
                    val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                    if (ch == 2) out.put(src) else for (f in 0 until frames) {
                        val l = src.get(f * ch); val r = if (ch > 1) src.get(f * ch + 1) else l
                        out.put(l); out.put(r)
                    }
                    val bytes = ByteArray(frames * 4)
                    out.rewind(); ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(out)
                    os.write(bytes)
                    codec.releaseOutputBuffer(o, false)
                    written += frames
                    if (maxSec > 0.0 && written >= (maxSec * rate).toLong()) outDone = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        return rate
    }
}
