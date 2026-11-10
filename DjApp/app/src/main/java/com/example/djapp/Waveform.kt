package com.example.djapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Per-10ms peak + low-frequency peak of a track, used to draw Serato-style waveforms. */
class WaveData(val peak: FloatArray, val low: FloatArray, val perSec: Int, val maxPeak: Float)

object WaveAnalyzer {
    const val PER_SEC = 100

    fun analyze(mix: File, rate: Int): WaveData {
        val bucket = max(1, rate / PER_SEC)
        val frames = mix.length() / 4
        val n = (frames / bucket + 1).toInt()
        val peak = FloatArray(n)
        val low = FloatArray(n)
        val a = (1.0 - exp(-2.0 * Math.PI * 250.0 / rate)).toFloat()
        var lp = 0f
        var maxAll = 1e-4f
        RandomAccessFile(mix, "r").use { raf ->
            val bytes = ByteArray(65536 * 4)
            var frame = 0L
            while (true) {
                val got = raf.read(bytes)
                if (got < 4) break
                val sb = ByteBuffer.wrap(bytes, 0, got - got % 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                var i = 0
                while (i + 1 < sb.limit()) {
                    val m = (sb.get(i) + sb.get(i + 1)) * (0.5f / 32768f)
                    lp += a * (m - lp)
                    val b = (frame / bucket).toInt()
                    val am = if (m < 0) -m else m
                    val al = if (lp < 0) -lp else lp
                    if (am > peak[b]) peak[b] = am
                    if (al > low[b]) low[b] = al
                    frame++
                    i += 2
                }
            }
        }
        for (v in peak) if (v > maxAll) maxAll = v
        return WaveData(peak, low, PER_SEC, maxAll)
    }
}

private val WaveBg = Color(0xFF05080F)
private val CueColors = listOf(Color(0xFFFF5C7A), Color(0xFFFFB74D), Color(0xFF64B5F6), Color(0xFFBA68C8))

/** Playhead position as a State: read it inside draw lambdas so only drawing (not composition) re-runs every frame. */
@Composable
private fun rememberPos(d: DeckState): State<Double> {
    val pos = remember(d) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(d) {
        while (true) {
            uiFrame()
            pos.doubleValue = NativeEngine.getPosition(d.id)
        }
    }
    return pos
}

private fun fmt(t: Double): String {
    val s = max(0, t.toInt())
    return "%d:%02d".format(s / 60, s % 60)
}

/** Zoomed, scrolling waveform with a fixed centre playhead, cue/hot-cue markers and loop shading. */
@Composable
fun ScrollingWave(d: DeckState, letter: String, modifier: Modifier = Modifier) {
    val posState = rememberPos(d)
    val remain by remember(d) { derivedStateOf { "-" + fmt(d.duration - posState.value) } }
    val win = 6.0
    val hi = lerp(d.accent, Color.White, 0.75f)
    Box(modifier.background(WaveBg)) {
        Canvas(Modifier.fillMaxSize()) {
            val pos = posState.value
            val w = size.width; val h = size.height; val mid = h / 2f; val cx = w / 2f
            val pps = (w / win).toFloat()
            val wd = d.wave
            if (wd != null) {
                val step = if (AppSettings.liteUi) 4f else 2f
                var x = 0f
                while (x < w) {
                    val t = pos + (x - cx) / pps
                    val idx = (t * wd.perSec).toInt()
                    if (t >= 0.0 && idx >= 0 && idx < wd.peak.size) {
                        val pk = wd.peak[idx]
                        val p = (pk / wd.maxPeak).coerceIn(0f, 1f).pow(0.75f)
                        val ratio = if (pk > 1e-5f) (wd.low[idx] / pk).coerceIn(0f, 1f) else 0f
                        val col = lerp(hi, d.accent, ratio)
                        val hh = max(1f, mid * 0.94f * p)
                        drawRect(col, Offset(x, mid - hh), Size(step, hh * 2f))
                    }
                    x += step
                }
            } else {
                drawRect(Color(0xFF16223B), Offset(0f, mid - 1f), Size(w, 2f))
            }
            if (d.loopIn >= 0.0 && d.loopOut > d.loopIn) {
                val x1 = cx + ((d.loopIn - pos) * pps).toFloat()
                val x2 = cx + ((d.loopOut - pos) * pps).toFloat()
                val l = max(0f, min(x1, x2)); val r = min(w, max(x1, x2))
                if (r > l) drawRect(Color(0xFF4CAF50).copy(alpha = 0.22f), Offset(l, 0f), Size(r - l, h))
            }
            if (d.loaded) {
                val cueX = cx + ((d.cue - pos) * pps).toFloat()
                if (cueX in 0f..w) drawRect(Color(0xFFFF9800), Offset(cueX - 1f, 0f), Size(2f, h))
                for (i in 0..3) {
                    val hc = d.hotcues[i]
                    if (hc >= 0.0) {
                        val hx = cx + ((hc - pos) * pps).toFloat()
                        if (hx in 0f..w) drawRect(CueColors[i], Offset(hx - 1f, 0f), Size(3f, h))
                    }
                }
            }
            drawRect(Color.White, Offset(cx - 1f, 0f), Size(2f, h))
        }
        Row(Modifier.align(Alignment.TopStart).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(d.accent), contentAlignment = Alignment.Center) {
                Text(letter, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d.loaded) {
                if (d.bpm > 0f) {
                    Text("%.1f".format(d.effectiveBpm), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(" BPM", color = TextGray, fontSize = 8.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(remain, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (!d.loaded) {
            Text(
                if (d.loading) "Decoding…" else "Load a track with  +",
                color = TextGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** Whole-track overview with a play marker. Touch or drag to seek. */
@Composable
fun OverviewWave(d: DeckState, modifier: Modifier = Modifier) {
    val posState = rememberPos(d)
    val dim = lerp(d.accent, Color.Black, 0.6f)
    Box(
        modifier.background(Color(0xFF0A1020)).pointerInput(d) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun seekTo(x: Float) {
                    if (d.duration > 0.0) NativeEngine.seek(d.id, (x / size.width).coerceIn(0f, 1f) * d.duration)
                }
                seekTo(down.position.x); down.consume()
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    seekTo(c.position.x); c.consume()
                }
            }
        }
    ) {
        // The whole-track picture is rendered once into two bitmaps (played colour / unplayed colour);
        // each frame only clips and blits them, instead of redrawing ~700 bars.
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val bw = size.width.toInt().coerceAtLeast(1); val bh = size.height.toInt().coerceAtLeast(1)
                val wd = d.wave
                val bmpA = ImageBitmap(bw, bh); val bmpD = ImageBitmap(bw, bh)
                if (wd != null && wd.peak.isNotEmpty()) {
                    val n = wd.peak.size; val mid = bh / 2f
                    fun render(bmp: ImageBitmap, col: Color) {
                        val cv = androidx.compose.ui.graphics.Canvas(bmp)
                        CanvasDrawScope().draw(this, layoutDirection, cv, Size(bw.toFloat(), bh.toFloat())) {
                            for (c in 0 until bw) {
                                val i0 = (c.toLong() * n / bw).toInt()
                                val i1 = max(i0 + 1, ((c + 1).toLong() * n / bw).toInt()).coerceAtMost(n)
                                var m = 0f
                                for (i in i0 until i1) if (wd.peak[i] > m) m = wd.peak[i]
                                val p = (m / wd.maxPeak).coerceIn(0f, 1f).pow(0.7f)
                                val hh = max(0.5f, mid * 0.9f * p)
                                drawRect(col, Offset(c.toFloat(), mid - hh), Size(1f, hh * 2f))
                            }
                        }
                    }
                    render(bmpA, d.accent); render(bmpD, dim)
                }
                onDrawBehind {
                    val w = size.width; val h = size.height
                    val frac = if (d.duration > 0.0) (posState.value / d.duration).toFloat().coerceIn(0f, 1f) else 0f
                    val px = frac * w
                    clipRect(0f, 0f, px, h) { drawImage(bmpA) }
                    clipRect(px, 0f, w, h) { drawImage(bmpD) }
                    drawRect(Color.White, Offset(px - 1f, 0f), Size(2f, h))
                }
            }
        )
    }
}
