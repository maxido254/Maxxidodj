package com.example.djapp

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        CrashLog.install(this)
        AppSettings.load(this)
        if (AppSettings.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!NativeEngine.start()) Toast.makeText(this, "Could not start audio output", Toast.LENGTH_LONG).show()
        AppSettings.applyToEngine()
        Sampler.init(this)
        Prepared.init(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { DjScreen() } }
    }
    override fun onDestroy() { VideoMixer.releaseAll(); NativeEngine.stop(); super.onDestroy() }
}

@Composable
fun DjScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val decks = remember { listOf(DeckState(0, DeckA, DeckADark), DeckState(1, DeckB, DeckBDark)) }
    var panel by remember { mutableStateOf(Panel.None) }
    var cross by remember { mutableFloatStateOf(0.5f) }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    var recPath by remember { mutableStateOf("") }
    val level = remember { mutableFloatStateOf(0f) }

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) decks[0].load(ctx, scope, uri) }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) decks[1].load(ctx, scope, uri) }
    val videoA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) VideoMixer.layers[0].load(ctx, uri, lock = false) }
    val videoB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) VideoMixer.layers[1].load(ctx, uri, lock = false) }

    LaunchedEffect(Unit) { decks.forEach { NativeEngine.setKeylock(it.id, it.keylock) } }
    LaunchedEffect(Unit) {
        while (true) {
            for (d in decks) { d.playing = NativeEngine.isPlaying(d.id); d.posSec = NativeEngine.getPosition(d.id) }
            for (d in decks) d.syncTick()
            VideoMixer.tick(decks)
            delay(200)
        }
    }
    LaunchedEffect(Unit) { while (true) { uiFrame(); level.floatValue = NativeEngine.getLevel() } }
    LaunchedEffect(recording) { if (recording) { recSecs = 0; while (true) { delay(1000); recSecs++ } } }
    BackHandler(panel != Panel.None) { panel = Panel.None }

    fun toggleRec() {
        if (recording) {
            NativeEngine.stopRecording(); recording = false
            Toast.makeText(ctx, "Saved: $recPath", Toast.LENGTH_LONG).show()
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            val f = File(dir, "DJ_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav")
            if (NativeEngine.startRecording(f.absolutePath)) { recPath = f.absolutePath; recording = true }
            else Toast.makeText(ctx, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }
    fun sync(me: DeckState, other: DeckState) {
        if (me.syncOn) me.stopSync()
        else if (!me.syncTo(other)) Toast.makeText(ctx, "BPM not detected yet", Toast.LENGTH_SHORT).show()
    }
    fun toggle(p: Panel) { panel = if (panel == p) Panel.None else p }
    fun loadFromLibrary(deck: Int, uri: android.net.Uri) {
        // A mis-tap in the library must never cut a playing deck.
        if (decks[deck].playing) Toast.makeText(ctx, "Pause deck ${if (deck == 0) "A" else "B"} first", Toast.LENGTH_SHORT).show()
        else decks[deck].load(ctx, scope, uri)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
        Column(Modifier.fillMaxSize().background(BgDark)) {
            Box(Modifier.weight(12f).fillMaxWidth()) {
                TopBar(decks, level, recording, recSecs, { panel = Panel.Library }, { panel = Panel.Library }, ::toggleRec)
            }
            HLine()
            Box(Modifier.weight(74f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(46f).fillMaxWidth()) { WaveSection(decks) }
                    HLine()
                    Box(Modifier.weight(54f).fillMaxWidth()) { MiddleSection(decks, level, panel, ::toggle) }
                }
                if (panel != Panel.None) PanelHost(
                    panel, decks, ::loadFromLibrary,
                    { d -> (if (d == 0) pickA else pickB).launch(arrayOf("audio/*", "video/*")) },
                    { d -> (if (d == 0) videoA else videoB).launch(arrayOf("video/*")) },
                    // The sampler stays a low strip (waveforms visible) until the sample browser is opened.
                    modifier = if (panel == Panel.Sampler && !Sampler.browse)
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.58f)
                    else Modifier.fillMaxSize()
                ) { panel = Panel.None }
            }
            HLine()
            Box(Modifier.weight(14f).fillMaxWidth()) {
                BottomBar(decks, cross, { cross = it; NativeEngine.setCrossfader(it); VideoMixer.audioCross = it }, ::sync)
            }
        }
        CrashLog.last?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable { }.padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The app closed unexpectedly last time. Please screenshot this and send it to the developer:", color = Color.White, fontSize = 12.sp)
                    Text(msg, color = Color(0xFFFFB4A9), fontSize = 8.sp, modifier = Modifier.weight(1f))
                    PadButton("CLOSE", Modifier.width(100.dp), active = true) { CrashLog.dismiss() }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ waveforms (Serato-style)
@Composable
private fun WaveSection(decks: List<DeckState>) {
    Column(Modifier.fillMaxSize()) {
        for (i in 0..1) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                ScrollingWave(decks[i], if (i == 0) "A" else "B", Modifier.weight(3f).fillMaxWidth())
                OverviewWave(decks[i], Modifier.weight(1f).fillMaxWidth())
            }
            if (i == 0) HLine()
        }
    }
}

@Composable private fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.6f)))

// ------------------------------------------------------------------ top bar
@Composable
private fun TopBar(
    decks: List<DeckState>, level: State<Float>, recording: Boolean, recSecs: Int,
    loadA: () -> Unit, loadB: () -> Unit, onRec: () -> Unit
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(Modifier.fillMaxHeight(0.86f).aspectRatio(1f), loadA, borderColor = Color.White) {
            PlusIcon(DeckA, Modifier.fillMaxSize(0.42f))
        }
        DeckTitle(decks[0], Modifier.weight(1f))
        Row(Modifier.width(300.dp).fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("MAXIDO", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1)
                    Text(" DJ", color = DeckA, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text("EXPERIENCE THE BEAT", color = TextGray, fontSize = 7.sp, letterSpacing = 1.6.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp, 20.dp)) {
                    val lv = level.value.coerceIn(0f, 1f)
                    val hs = listOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
                    val bw = 2.2.dp.toPx(); val gap = (size.width - bw * 5) / 4
                    for (i in 0..4) {
                        val h = size.height * (0.18f + 0.82f * lv * hs[i])
                        drawRect(Color.White, Offset(i * (bw + gap), (size.height - h) / 2), Size(bw, h))
                    }
                }
            }
            val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                1f, 0.25f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "recAlpha"
            )
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRec).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(15.dp).clip(CircleShape).background(Color(0xFFFF3B4E).copy(alpha = if (recording) blink else 1f)))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (recording) "%d:%02d".format(recSecs / 60, recSecs % 60) else "REC",
                    color = Color.White, fontSize = 13.sp
                )
            }
        }
        DeckTitle(decks[1], Modifier.weight(1f))
        CircleIconButton(Modifier.fillMaxHeight(0.86f).aspectRatio(1f), loadB, borderColor = Color.White) {
            PlusIcon(DeckB, Modifier.fillMaxSize(0.42f))
        }
    }
}

@Composable
private fun DeckTitle(d: DeckState, modifier: Modifier) {
    val sub = buildList {
        if (d.loading) add("decoding…")
        else if (d.loaded) {
            add(if (d.bpm > 0f) "%.2f BPM".format(d.effectiveBpm) else "analysing BPM…")
            if (d.key.isNotEmpty()) add(d.key)
            when {
                d.stemProg in 0f..1f -> add(if (AppSettings.modelStatus.isNotEmpty()) AppSettings.modelStatus else "stems ${(d.stemProg * 100).toInt()}%")
                d.stemsReady -> add("stems ready")
                else -> {}
            }
        } else add("tap + to load")
    }.joinToString("  ·  ")
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            d.name.uppercase(), color = TextGray, fontSize = 14.sp, fontWeight = FontWeight.Light,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(sub, color = d.accent.copy(alpha = 0.9f), fontSize = 9.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ middle: tempo | platter | controls | platter | tempo
@Composable
private fun MiddleSection(decks: List<DeckState>, level: State<Float>, panel: Panel, toggle: (Panel) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        TempoColumn(decks[0], Modifier.weight(7f).fillMaxHeight()) {
            CircleIconButton(Modifier.size(32.dp), { toggle(Panel.Stems) }, active = panel == Panel.Stems, accent = DeckALight) {
                WaveIcon(Color.White, Modifier.size(18.dp))
            }
        }
        Box(Modifier.weight(31f).fillMaxHeight()) { DeckZone(decks[0], DeckA, DeckADark) }
        CenterColumn(decks, level, panel, toggle, Modifier.weight(24f).fillMaxHeight())
        Box(Modifier.weight(31f).fillMaxHeight()) { DeckZone(decks[1], DeckB, DeckBDark) }
        TempoColumn(decks[1], Modifier.weight(7f).fillMaxHeight()) {
            CircleIconButton(Modifier.size(32.dp), { toggle(Panel.Settings) }, active = panel == Panel.Settings, accent = Accent) {
                GearIcon(Color.White, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun GearIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val c = center; val r = size.minDimension * 0.28f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        drawLine(color, c + Offset(dx * r, dy * r), c + Offset(dx * r * 1.65f, dy * r * 1.65f), r * 0.62f, StrokeCap.Butt)
    }
    drawCircle(color, r * 1.25f, c); drawCircle(BgDark, r * 0.55f, c)
}

@Composable
private fun TempoColumn(deck: DeckState, modifier: Modifier, icon: @Composable () -> Unit) {
    Column(modifier.padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        TempoSlider(deck, Modifier.weight(1f).fillMaxWidth())
        Box(
            Modifier.padding(top = 3.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp))
                .background(if (deck.keylock) deck.accent else Color(0xFF111B2E)).clickable { deck.toggleKeylock() },
            contentAlignment = Alignment.Center
        ) { Text("KEYLOCK", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun TempoSlider(deck: DeckState, modifier: Modifier = Modifier) {
    val range = AppSettings.tempoRange
    BoxWithConstraints(modifier.padding(vertical = 17.dp)) {
        val h = maxHeight
        val frac = (deck.tempoPct / range / 2f + 0.5f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().faderTouch(true) { f ->
            var p = (f - 0.5f) * 2f * range
            if (abs(p) < range * 0.03f) p = 0f          // detent at 0 %
            deck.applyTempo(p)
        })
        Canvas(Modifier.fillMaxSize()) {
            val tw = 17.dp.toPx()
            drawRoundRect(Color(0xFF070B14), Offset((size.width - tw) / 2, -4.dp.toPx()), Size(tw, size.height + 8.dp.toPx()), CornerRadius(tw / 2))
            drawCircle(deck.accent, 3.5.dp.toPx(), Offset(if (deck.id == 0) 0f else size.width, size.height / 2))
        }
        val pct = deck.tempoPct * 100f
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = h * (1f - frac) - 15.dp).fillMaxWidth(0.98f).height(30.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xFFE6E6E8)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (abs(pct) < 0.05f) "0.0 %" else "%+.1f %%".format(pct), color = Color(0xFF666870), fontSize = 9.sp, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ platter with scratch
/** One deck's play area: filter + bass faders on the left of the jog wheel, the four stem buttons underneath it. */
@Composable
private fun DeckZone(deck: DeckState, color: Color, inner: Color) {
    Row(Modifier.fillMaxSize()) {
        FilterFader(deck, Modifier.width(34.dp).fillMaxHeight().padding(vertical = 8.dp))
        BassFader(deck, Modifier.width(34.dp).fillMaxHeight().padding(vertical = 8.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { Platter(deck, color, inner) }
            StemButtons(deck, Modifier.padding(start = 2.dp, end = 2.dp, bottom = 5.dp, top = 2.dp))
        }
    }
}

/** Vertical fader with a centre detent: [pos] -1..1, 0 = off/flat. Tap the label to reset. */
@Composable
private fun CenterFader(label: String, active: Boolean, pos: Float, accent: Color, modifier: Modifier, onPos: (Float) -> Unit, onReset: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label, color = if (active) accent else TextGray, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clickable { onReset() }.padding(bottom = 3.dp)
        )
        Box(Modifier.weight(1f).fillMaxWidth().faderTouch(true) { f ->
            val v = f * 2f - 1f
            onPos(if (abs(v) < 0.07f) 0f else v.coerceIn(-1f, 1f))        // snaps to the middle
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val tw = 6.dp.toPx(); val tx = size.width / 2
                val th = 14.dp.toPx(); val top = th / 2; val bot = size.height - th / 2
                val mid = (top + bot) / 2
                val y = bot - (bot - top) * ((pos + 1f) / 2f)
                drawRoundRect(Color(0xFF070B14), Offset(tx - tw / 2, top), Size(tw, bot - top), androidx.compose.ui.geometry.CornerRadius(tw / 2))
                drawRect(if (active) accent else Color(0xFF3A3D45), Offset(tx - tw / 2, minOf(y, mid)), Size(tw, abs(y - mid)))
                drawLine(Color(0xFF6A6D75), Offset(tx - 14.dp.toPx(), mid), Offset(tx + 14.dp.toPx(), mid), 1.5.dp.toPx())
                drawRoundRect(if (active) Color.White else Color(0xFFB9BBC2), Offset(tx - 15.dp.toPx(), y - th / 2), Size(30.dp.toPx(), th), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                drawLine(Color(0xFF30333A), Offset(tx - 11.dp.toPx(), y), Offset(tx + 11.dp.toPx(), y), 1.5.dp.toPx())
            }
        }
    }
}

/** DJ filter: centre = off, down = low-pass, up = high-pass. */
@Composable
private fun FilterFader(d: DeckState, modifier: Modifier) =
    CenterFader(
        when { d.filter < -0.02f -> "LP"; d.filter > 0.02f -> "HP"; else -> "FILTER" }, d.filter != 0f, d.filter, d.accent, modifier,
        { d.filter = it; d.pushFilter() }, { d.filter = 0f; d.pushFilter() }
    )

/** Bass EQ like the bass knob on an amplifier: up boosts (+12 dB), down cuts (to a near kill for transitions). */
@Composable
private fun BassFader(d: DeckState, modifier: Modifier) =
    CenterFader(
        if (abs(d.eq[0]) < 0.5f) "BASS" else eqText(d.eq[0]).replace(" dB", ""), abs(d.eq[0]) >= 0.5f, eqToFrac(d.eq[0]) * 2f - 1f, d.accent, modifier,
        { d.setEq(0, eqFromFrac((it + 1f) / 2f)) }, { d.setEq(0, 0f) }
    )

/** VOCAL / MELODY / BASS / INSTR (drums): tap to mute or bring back that part of the track. Lit = playing. */
@Composable
private fun StemButtons(d: DeckState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((label, idx) in listOf("VOCAL" to 0, "MELODY" to 3, "BASS" to 2, "INSTR" to 1))
            PadButton(label, Modifier.weight(1f).height(26.dp), active = d.stemOn[idx] && d.stemsReady, color = d.accent, enabled = d.stemsReady, fontSize = 8) { d.toggleStem(idx) }
    }
}

@Composable
private fun Platter(deck: DeckState, color: Color, inner: Color) {
    var angle by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deck) {
        while (true) {
            uiFrame()
            val p = NativeEngine.getPosition(deck.id)
            angle = ((p * 200.0) % 360.0).toFloat()          // 200 deg/s ~ 33 rpm
            progress = if (deck.duration > 0) (p / deck.duration).toFloat().coerceIn(0f, 1f) else 0f
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val dia = minOf(maxWidth, maxHeight)
        Box(
            Modifier.size(dia).pointerInput(deck) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f; val cy = size.height / 2f
                    fun ang(p: Offset) = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat()
                    var last = ang(down.position); var lastT = down.uptimeMillis
                    var engaged = false; var travelled = 0f; var acc = 0f; var basePos = 0.0
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val a = ang(c.position)
                        var da = a - last
                        if (da > 180f) da -= 360f; if (da < -180f) da += 360f
                        travelled += abs(da)
                        if (!engaged && travelled > 3f) {           // plain taps don't stop the music
                            engaged = true; acc = 0f; basePos = NativeEngine.getPosition(deck.id)
                        } else if (engaged) acc += da
                        if (engaged) {
                            // The platter angle IS the track position (200 deg/s = 33 rpm); the engine follows it smoothly.
                            val t = (basePos + acc / 200.0).coerceIn(0.0, maxOf(deck.duration, 0.0))
                            NativeEngine.setJog(deck.id, true, t)
                        }
                        last = a; lastT = c.uptimeMillis; c.consume()
                    }
                    if (engaged) NativeEngine.setJog(deck.id, false, 0.0)
                }
            },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2; val c = center
                drawCircle(Color(0xFF0A1020), r, c)
                drawCircle(color.copy(alpha = 0.25f), r * 0.94f, c, style = Stroke(3.dp.toPx()))
                for (k in listOf(0.55f, 0.66f, 0.77f, 0.88f)) drawCircle(Color.White.copy(alpha = 0.05f), r * k, c, style = Stroke(1.dp.toPx()))
                if (progress > 0f) drawArc(
                    color, -90f, progress * 360f, false,
                    topLeft = c - Offset(r * 0.94f, r * 0.94f), size = Size(r * 1.88f, r * 1.88f),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
                drawCircle(Color(0xFF1B2740), r, c, style = Stroke(1.5.dp.toPx()))
                drawCircle(inner, r * 0.355f, c)
                drawCircle(color, r * 0.355f, c, style = Stroke(1.5.dp.toPx()))
                rotate(angle, pivot = c) {
                    // small tick on the label so you can see the platter turn
                    drawRect(Color.White, c + Offset(-r * 0.012f, -r * 0.355f), Size(r * 0.024f, r * 0.07f))
                }
            }
            Text(
                "MAXIDO DJ", color = Color.White, fontSize = (dia.value * 0.075f).sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, maxLines = 1,
                modifier = Modifier.graphicsLayer { rotationZ = angle }
            )
            Text(
                if (deck.loaded) "%d:%02d / %d:%02d".format(deck.posSec.toInt() / 60, deck.posSec.toInt() % 60, deck.duration.toInt() / 60, deck.duration.toInt() % 60) else "",
                color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dia * 0.12f)
            )
            val badge = buildString {
                if (deck.loopActive) append("LOOP ")
                if (deck.filter != 0f) append("FLT ")
                if (deck.reverb > 0f) append("REV ")
                if (deck.phaser > 0f) append("PHS ")
            }.trim()
            if (badge.isNotEmpty()) Text(
                badge, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = dia * 0.1f)
            )
        }
    }
}

// ------------------------------------------------------------------ centre column
@Composable
private fun CenterColumn(decks: List<DeckState>, level: State<Float>, panel: Panel, toggle: (Panel) -> Unit, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val bs = Modifier.size(32.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleIconButton(bs, { toggle(Panel.Loop) }, active = panel == Panel.Loop || decks.any { it.loopActive }) {
                Text("↻", color = Color.White, fontSize = 17.sp)
            }
            CircleIconButton(bs, { toggle(Panel.Mixer) }, active = panel == Panel.Mixer) {
                MixerIcon(Color.White, Modifier.size(16.dp))
            }
            CircleIconButton(bs, { toggle(Panel.Fx) }, active = panel == Panel.Fx || decks.any { it.reverb > 0f || it.phaser > 0f }) {
                Text("FX", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            }
            CircleIconButton(bs, { toggle(Panel.Library) }, active = panel == Panel.Library || Library.analysing) {
                Text("LIB", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.weight(1f).padding(vertical = 10.dp).width(28.dp).background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                val lv = level.value.coerceIn(0f, 1f)
                val top = size.height * (1f - lv)
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFFFF4B4B), Color(0xFFFFC93D), Color(0xFF2BE37A)), 0f, size.height),
                    Offset(0f, top), Size(size.width, size.height - top)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleIconButton(bs, { toggle(Panel.Pads) }, active = panel == Panel.Pads) {
                GridIcon(Color.White, Modifier.size(15.dp))
            }
            CircleIconButton(bs, { toggle(Panel.Sampler) }, active = panel == Panel.Sampler || Sampler.pads.any { it.playing }, accent = DeckALight) {
                Text("SMP", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            CircleIconButton(bs, { toggle(Panel.Video) }, active = panel == Panel.Video || VideoMixer.layers.any { it.ready }, accent = Accent) {
                Text("VID", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ bottom: transport | crossfader | transport
@Composable
private fun BottomBar(decks: List<DeckState>, cross: Float, onCross: (Float) -> Unit, onSync: (DeckState, DeckState) -> Unit) {
    val a = decks[0]; val b = decks[1]
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(31f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CueButton(a, Modifier.weight(1f))
            PlayButton(a, DeckA, DeckALight, Modifier.weight(1f))
            SyncButton(Modifier.weight(1f), a.syncOn) { onSync(a, b) }
        }
        Crossfader(cross, onCross, Modifier.weight(23f).fillMaxHeight())
        Row(Modifier.weight(31f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncButton(Modifier.weight(1f), b.syncOn) { onSync(b, a) }
            CueButton(b, Modifier.weight(1f))
            PlayButton(b, DeckB, DeckBLight, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GrayButton(label: String, modifier: Modifier, touch: Modifier, on: Boolean = false) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(if (on) listOf(Color(0xFF3A2A08), Color(0xFF2B2108)) else listOf(BtnTop, BtnBottom))).then(touch),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFEDEDEF), fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp) }
}

@Composable
private fun CueButton(d: DeckState, modifier: Modifier) =
    GrayButton("CUE", modifier, Modifier.pressable({ d.cueDown() }, { d.cueUp() }))

@Composable
private fun SyncButton(modifier: Modifier, on: Boolean, onClick: () -> Unit) =
    GrayButton("SYNC", modifier, Modifier.clickable(onClick = onClick), on)

@Composable
private fun PlayButton(d: DeckState, c1: Color, c2: Color, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(c1, c2, c1))).clickable { d.togglePlay() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            if (d.playing) {
                val w = size.width * 0.28f
                drawRect(Color.Black, Offset(size.width * 0.1f, 0f), Size(w, size.height))
                drawRect(Color.Black, Offset(size.width * 0.62f, 0f), Size(w, size.height))
            } else {
                drawPath(Path().apply { moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.12f, size.height); close() }, Color.Black)
            }
        }
    }
}

@Composable
private fun Crossfader(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    Box(modifier.faderTouch(false) { f -> onChange(if (abs(f - 0.5f) < 0.03f) 0.5f else f) }) {
        Canvas(Modifier.fillMaxSize()) {
            val tw = 18.dp.toPx(); val th = minOf(34.dp.toPx(), size.height)
            val cy = size.height / 2; val bar = 9.dp.toPx()
            val tx = tw / 2 + (size.width - tw) * value
            drawRoundRect(Color(0xFF0A1020), Offset(0f, cy - bar / 2 - 1.dp.toPx()), Size(size.width, bar + 2.dp.toPx()), CornerRadius(bar))
            drawRoundRect(DeckA, Offset(1.dp.toPx(), cy - bar / 2), Size(tx - 1.dp.toPx(), bar), CornerRadius(bar / 2))
            drawRoundRect(DeckB, Offset(tx, cy - bar / 2), Size(size.width - tx - 1.dp.toPx(), bar), CornerRadius(bar / 2))
            drawRoundRect(Color(0xFFE4E4E6), Offset(tx - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFF2A2D35), Offset(tx, cy - th / 2 + 2.dp.toPx()), Offset(tx, cy + th / 2 - 2.dp.toPx()), 2.dp.toPx())
        }
    }
}
