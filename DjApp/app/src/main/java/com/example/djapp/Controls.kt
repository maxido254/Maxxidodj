package com.example.djapp

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BgDark = Color(0xFF070B14)
val PanelBg = Color(0xFF0E1524)
val BtnTop = Color(0xFF1A2640)
val BtnBottom = Color(0xFF111B2E)
val LineGray = Color(0xFF26344F)
val TextGray = Color(0xFF8FA0BD)
val DeckA = Color(0xFF1AA3FF)          // deck A: electric blue
val DeckADark = Color(0xFF0F2C4A)
val DeckALight = Color(0xFF66C2FF)
val DeckB = Color(0xFF9B4DFF)          // deck B: violet
val DeckBDark = Color(0xFF2A1A4A)
val DeckBLight = Color(0xFFC08BFF)
val Accent = DeckA                     // general UI accent (sliders, highlighted buttons)
val AccentLight = DeckALight
val Cardline = Color(0xFF1B2740)

/** Touch-and-drag along a track. Reports 0..1 (for vertical: 1 = top). Responds immediately on touch. */
fun Modifier.faderTouch(vertical: Boolean, onFraction: (Float) -> Unit): Modifier = pointerInput(vertical) {
    awaitEachGesture {
        val down = awaitFirstDown()
        fun f(p: Offset) =
            if (vertical) 1f - (p.y / size.height).coerceIn(0f, 1f) else (p.x / size.width).coerceIn(0f, 1f)
        onFraction(f(down.position)); down.consume()
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull() ?: break
            if (!c.pressed) break
            onFraction(f(c.position)); c.consume()
        }
    }
}

/** Fires [onDown] when touched and [onUp] when released/cancelled. */
fun Modifier.pressable(onDown: () -> Unit, onUp: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(); onDown()
        waitForUpOrCancellation(); onUp()
    }
}

@Composable
fun CircleIconButton(
    modifier: Modifier, onClick: () -> Unit, active: Boolean = false, accent: Color = Color.White,
    borderColor: Color = LineGray, content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier.clip(CircleShape).background(if (active) accent.copy(alpha = 0.28f) else Color.Transparent)
            .border(1.5.dp, if (active) accent else borderColor, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center, content = content
    )
}

@Composable
fun PlusIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val s = size.minDimension; val w = s * 0.2f
    drawLine(color, Offset(s * 0.05f, s / 2), Offset(s * 0.95f, s / 2), w)
    drawLine(color, Offset(s / 2, s * 0.05f), Offset(s / 2, s * 0.95f), w)
}

@Composable
fun GridIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val s = size.minDimension; val g = s * 0.06f; val c = (s - 2 * g) / 3
    for (i in 0..2) for (j in 0..2) drawRect(color, Offset(i * (c + g), j * (c + g)), Size(c, c))
}

@Composable
fun WaveIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val pts = listOf(0f to 0.5f, 0.15f to 0.5f, 0.25f to 0.15f, 0.38f to 0.9f, 0.5f to 0.3f, 0.62f to 0.75f, 0.72f to 0.5f, 1f to 0.5f)
    for (i in 0 until pts.size - 1)
        drawLine(color, Offset(pts[i].first * w, pts[i].second * h), Offset(pts[i + 1].first * w, pts[i + 1].second * h), 2.2f * density, StrokeCap.Round)
}

@Composable
fun MixerIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val xs = listOf(0.15f, 0.5f, 0.85f); val ky = listOf(0.3f, 0.7f, 0.45f)
    for (i in 0..2) {
        drawLine(color, Offset(xs[i] * w, 0f), Offset(xs[i] * w, h), 2.4f * density, StrokeCap.Round)
        drawRect(color, Offset(xs[i] * w - w * 0.14f, ky[i] * h - h * 0.07f), Size(w * 0.28f, h * 0.14f))
    }
}

@Composable
fun PadButton(
    text: String, modifier: Modifier = Modifier, active: Boolean = false, color: Color = DeckALight,
    enabled: Boolean = true, fontSize: Int = 11, onClick: () -> Unit
) {
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) color else BtnBottom)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, fontSize = fontSize.sp, maxLines = 1, textAlign = TextAlign.Center,
            color = if (!enabled) Color(0xFF777A82) else if (active) Color.Black else Color(0xFFE6E7EA)
        )
    }
}

/** Horizontal slider with label + value readout. [centered] fills from the middle (for -1..1 controls). */
@Composable
fun FxSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, color: Color, valueText: String,
    centered: Boolean = false, onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = TextGray, modifier = Modifier.width(56.dp))
        Box(
            Modifier.weight(1f).fillMaxHeight().faderTouch(false) { f ->
                var v = range.start + f * (range.endInclusive - range.start)
                if (centered && kotlin.math.abs(v) < (range.endInclusive - range.start) * 0.03f) v = 0f
                onChange(v)
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val r = 8.dp.toPx(); val th = 4.dp.toPx(); val cy = size.height / 2
                val x0 = r; val x1 = size.width - r
                val tx = x0 + (x1 - x0) * frac
                drawRoundRect(Color(0xFF070B14), Offset(x0, cy - th / 2), Size(x1 - x0, th), CornerRadius(th / 2))
                val a = if (centered) (x0 + x1) / 2 else x0
                drawRoundRect(color, Offset(minOf(a, tx), cy - th / 2), Size(kotlin.math.abs(tx - a), th), CornerRadius(th / 2))
                if (centered) drawLine(LineGray, Offset((x0 + x1) / 2, cy - 7.dp.toPx()), Offset((x0 + x1) / 2, cy + 7.dp.toPx()), 1.dp.toPx())
                drawCircle(Color.White, r, Offset(tx, cy)); drawCircle(color, r * 0.55f, Offset(tx, cy))
            }
        }
        Text(valueText, fontSize = 11.sp, color = Color(0xFFD5D6DA), textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
}

/** Vertical fader used for stem levels. */
@Composable
fun VFader(value: Float, color: Color, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    Box(modifier.then(if (enabled) Modifier.faderTouch(true) { onChange(it) } else Modifier)) {
        Canvas(Modifier.fillMaxSize()) {
            val tw = 6.dp.toPx(); val tx = size.width / 2
            val th = 14.dp.toPx(); val top = th / 2; val bot = size.height - th / 2
            val y = bot - (bot - top) * value.coerceIn(0f, 1f)
            val c = if (enabled) color else Color(0xFF555861)
            drawRoundRect(Color(0xFF070B14), Offset(tx - tw / 2, top), Size(tw, bot - top), CornerRadius(tw / 2))
            drawRoundRect(c, Offset(tx - tw / 2, y), Size(tw, bot - y), CornerRadius(tw / 2))
            drawRoundRect(if (enabled) Color(0xFFE8E8EA) else Color(0xFF777A82), Offset(tx - 15.dp.toPx(), y - th / 2), Size(30.dp.toPx(), th), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFF30333A), Offset(tx - 11.dp.toPx(), y), Offset(tx + 11.dp.toPx(), y), 1.5.dp.toPx())
        }
    }
}


/** One UI "frame": every display frame normally, ~25 fps on budget phones (Settings > PERFORMANCE > LITE UI). */
suspend fun uiFrame() {
    if (AppSettings.liteUi) kotlinx.coroutines.delay(40) else androidx.compose.runtime.withFrameNanos { }
}
