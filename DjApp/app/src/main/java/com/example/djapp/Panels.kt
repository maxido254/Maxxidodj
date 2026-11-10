package com.example.djapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.widget.Toast
import kotlin.math.abs
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Panel(val title: String) {
    None(""), Stems("STEMS"), Loop("LOOP"), Mixer("MIXER"), Fx("EFFECTS"), Pads("HOT CUES"), Library("LIBRARY"), Sampler("SAMPLER"), Video("VIDEO MIXER"), Settings("SETTINGS")
}

@Composable
fun PanelHost(
    panel: Panel, decks: List<DeckState>, onLoad: (Int, android.net.Uri) -> Unit, onBrowse: (Int) -> Unit,
    onPickVideo: (Int) -> Unit, modifier: Modifier = Modifier.fillMaxSize(), onClose: () -> Unit
) {
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(PanelBg.copy(alpha = 0.98f)).border(1.dp, Cardline, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {   // swallow touches so nothing leaks to the platters underneath
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(panel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = TextGray, fontSize = 15.sp)
            }
        }
        if (panel == Panel.Settings) {
            Box(Modifier.weight(1f).fillMaxWidth()) { SettingsPanel(decks) }
        } else if (panel == Panel.Library) {
            Box(Modifier.weight(1f).fillMaxWidth()) { LibraryPanel(onLoad, onBrowse) }
        } else if (panel == Panel.Sampler) {
            Box(Modifier.weight(1f).fillMaxWidth()) { SamplerPanel() }
        } else if (panel == Panel.Video) {
            Box(Modifier.weight(1f).fillMaxWidth()) { VideoPanel(onPickVideo, onBrowse) }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (d in decks) Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        if (d.loaded) d.name else (if (d.id == 0) "DECK A" else "DECK B"),
                        color = d.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    when (panel) {
                        Panel.Stems -> StemsPanel(d)
                        Panel.Loop -> LoopPanel(d)
                        Panel.Mixer -> MixerPanel(d)
                        Panel.Fx -> FxPanel(d)
                        Panel.Pads -> PadsPanel(d)
                        else -> {}
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Stems
/** Mixer order (engine index): VOCAL, MELODY, BASS, DRUMS. */
private val STEM_UI_ORDER = listOf(0, 3, 2, 1)

@Composable
private fun ColumnScope.StemsPanel(d: DeckState) {
    val ctx = LocalContext.current
    val ready = d.stemsReady
    val uri = d.curUri
    val queued = uri != null && PrepQueue.queue.any { it.uri == uri }
    val status = when {
        !d.loaded -> "Load a track first"
        ready -> if (uri != null && Prepared.has(uri)) "Stems ready · prepared track" else "Stems ready"
        d.stemProg in 0f..1f -> "Preparing stems… ${(d.stemProg * 100).toInt()}%  (full mix keeps playing)"
        queued -> "Preparing in the background… ${if (PrepQueue.current == d.curTitle) "${(PrepQueue.progress * 100).toInt()}%" else "queued"}"
        !AppSettings.stemAuto -> "Not prepared yet: tap PREPARE (best done before the set)"
        else -> "Stems unavailable"
    }
    Text(status, color = TextGray, fontSize = 10.sp, maxLines = 1)
    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in STEM_UI_ORDER) Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            PadButton(STEM_LABELS[i], Modifier.fillMaxWidth().height(28.dp), active = d.stemOn[i] && ready, color = d.accent, enabled = ready, fontSize = 9) { d.toggleStem(i) }
            VFader(d.stemLevel[i], d.accent, ready, Modifier.weight(1f).fillMaxWidth()) { d.setStemLevel(i, it) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("ACAPELLA", Modifier.weight(1f).height(28.dp), enabled = ready, fontSize = 9) { d.soloStem(0) }
        PadButton("INSTRUMENTAL", Modifier.weight(1.3f).height(28.dp), enabled = ready, fontSize = 9) { d.muteOnly(0) }
        PadButton("BEATLESS", Modifier.weight(1f).height(28.dp), enabled = ready, fontSize = 9) { d.muteOnly(1) }
        PadButton("RESET", Modifier.weight(1f).height(28.dp), enabled = ready, fontSize = 9) { d.resetStems() }
    }
    if (d.loaded && !ready && uri != null)
        PadButton(
            if (queued) "PREPARING…" else "PREPARE THIS TRACK", Modifier.fillMaxWidth().padding(top = 4.dp).height(28.dp),
            active = true, color = Accent, enabled = !queued && d.stemProg !in 0f..1f, fontSize = 10
        ) { PrepQueue.add(ctx, uri, d.curTitle.ifEmpty { d.name }) }
}

// ------------------------------------------------------------------ Loop
private val BEATS = listOf(0.25f to "1/4", 0.5f to "1/2", 1f to "1", 2f to "2", 4f to "4", 8f to "8", 16f to "16")

@Composable
private fun LoopPanel(d: DeckState) {
    val hasBpm = d.bpm > 0f
    Text(
        when {
            !d.loaded -> "Load a track first"
            d.loopActive && d.loopBeats > 0 -> "LOOP ON · ${BEATS.firstOrNull { it.first == d.loopBeats }?.second ?: "%.2f".format(d.loopBeats)} beats"
            d.loopActive -> "LOOP ON · %.2f s".format(d.loopOut - d.loopIn)
            !hasBpm -> "Detecting BPM… (manual IN/OUT works now)"
            else -> "Tap a beat length to loop from the current position"
        },
        color = if (d.loopActive) d.accent else TextGray, fontSize = 10.sp, maxLines = 1
    )
    Spacer(Modifier.height(6.dp))
    Text("AUTO LOOP (beats)", color = TextGray, fontSize = 9.sp)
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((b, label) in BEATS)
            PadButton(label, Modifier.weight(1f), active = d.loopActive && d.loopBeats == b, color = d.accent, enabled = d.loaded && hasBpm) { d.beatLoop(b) }
    }
    Spacer(Modifier.height(8.dp))
    Text("MANUAL", color = TextGray, fontSize = 9.sp)
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PadButton("LOOP IN", Modifier.weight(1f), enabled = d.loaded) { d.loopInSet() }
        PadButton("LOOP OUT", Modifier.weight(1f), active = d.loopActive && d.loopBeats == 0f, color = d.accent, enabled = d.loaded) { d.loopOutSet() }
        PadButton(if (d.loopActive) "EXIT" else "RELOOP", Modifier.weight(1f), enabled = d.loaded && (d.loopActive || d.loopOut > d.loopIn)) {
            if (d.loopActive) d.exitLoop() else d.reloop()
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PadButton("½  SHORTER", Modifier.weight(1f), enabled = d.loopActive) { d.halveLoop() }
        PadButton("×2  LONGER", Modifier.weight(1f), enabled = d.loopActive) { d.doubleLoop() }
    }
}

// ------------------------------------------------------------------ Mixer
@Composable
private fun MixerPanel(d: DeckState) {
    FxSlider("VOLUME", d.vol, 0f..1f, d.accent, "${(d.vol * 100).toInt()}%") { d.vol = it; d.pushVolume() }
    FxSlider("FILTER", d.filter, -1f..1f, d.accent, filterText(d.filter), centered = true) { d.filter = it; d.pushFilter() }
    Text("◄ low-pass          high-pass ►", color = TextGray, fontSize = 9.sp, modifier = Modifier.padding(start = 56.dp))
    Spacer(Modifier.height(4.dp))
    for ((i, name) in listOf("BASS", "MID", "TREBLE").withIndex())
        FxSlider(name, eqToFrac(d.eq[i]), 0f..1f, d.accent, eqText(d.eq[i]), centered = true) { d.setEq(i, eqFromFrac(if (abs(it - 0.5f) < 0.03f) 0.5f else it)) }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton("FILTER OFF", Modifier.width(100.dp), enabled = d.filter != 0f) { d.filter = 0f; d.pushFilter() }
        PadButton("EQ FLAT", Modifier.width(90.dp), enabled = d.eq.any { it != 0f }) { for (b in 0..2) d.setEq(b, 0f) }
        PadButton("BASS KILL", Modifier.width(100.dp), active = d.eq[0] < -25f) { d.setEq(0, if (d.eq[0] < -25f) 0f else -26f) }
    }
}

private fun filterText(v: Float) = when {
    v < -0.02f -> "LP ${(-v * 100).toInt()}"
    v > 0.02f -> "HP ${(v * 100).toInt()}"
    else -> "OFF"
}

// ------------------------------------------------------------------ Effects
@Composable
private fun FxPanel(d: DeckState) {
    FxSlider("FILTER", d.filter, -1f..1f, d.accent, filterText(d.filter), centered = true) { d.filter = it; d.pushFilter() }
    FxSlider("REVERB", d.reverb, 0f..1f, d.accent, "${(d.reverb * 100).toInt()}%") { d.reverb = it; d.pushReverb() }
    FxSlider("ROOM", d.room, 0f..1f, d.accent, "${(d.room * 100).toInt()}%") { d.room = it; d.pushReverb() }
    FxSlider("PHASER", d.phaser, 0f..1f, d.accent, "${(d.phaser * 100).toInt()}%") { d.phaser = it; d.pushPhaser() }
    FxSlider("RATE", d.phRate, 0.1f..4f, d.accent, "%.1f Hz".format(d.phRate)) { d.phRate = it; d.pushPhaser() }
    Spacer(Modifier.height(4.dp))
    PadButton("ALL FX OFF", Modifier.width(110.dp), enabled = d.filter != 0f || d.reverb > 0f || d.phaser > 0f) { d.resetFx() }
}

// ------------------------------------------------------------------ Hot cues
private val PAD_COLORS = listOf(Color(0xFFFF5C7A), Color(0xFFFFB74D), Color(0xFF64B5F6), Color(0xFFBA68C8))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadsPanel(d: DeckState) {
    Text("Tap: set / jump · Long-press: clear", color = TextGray, fontSize = 10.sp)
    Spacer(Modifier.height(6.dp))
    for (row in 0..1) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (col in 0..1) {
            val i = row * 2 + col
            val set = d.hotcues[i] >= 0
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (set) PAD_COLORS[i] else Color(0xFF1A2640))
                    .combinedClickable(enabled = d.loaded, onClick = { d.hotcue(i) }, onLongClick = { d.clearHotcue(i) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (set) "${i + 1}  ·  %d:%02d".format(d.hotcues[i].toInt() / 60, d.hotcues[i].toInt() % 60) else "${i + 1}  SET",
                    color = if (set) Color.Black else TextGray, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Settings
@Composable
private fun SettingsPanel(decks: List<DeckState>) {
    var page by remember { mutableStateOf(0) }
    val tabs = listOf("MIXER", "STEMS", "PERFORMANCE", "SAMPLER", "VIDEO", "DISPLAY", "DEVELOPERS")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(136.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = page == i
                Box(
                    Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(0xFF0F2540) else Color.Transparent).clickable { page = i }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        t, color = if (on) Accent else Color(0xFFC9D4E8), fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.5.sp, maxLines = 1
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Cardline))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (page) {
                0 -> MixerSettings(decks)
                1 -> StemSettings()
                2 -> PerfSettings()
                3 -> SamplerSettings()
                4 -> VideoSettings()
                5 -> DisplaySettings()
                else -> DeveloperPage()
            }
        }
    }
}

private const val WHATSAPP_NUMBER = "254115710055"   // +254 115 710 055

private fun openWhatsApp(ctx: Context, message: String = "Hi, I'm using MAXIDO DJ") {
    try {
        val url = "https://wa.me/$WHATSAPP_NUMBER?text=" + Uri.encode(message)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
    }
}

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Could not open the link", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LinkRow(label: String, value: String, url: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF111B2E))
            .clickable { openLink(ctx, url) }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextGray, fontSize = 10.sp, modifier = Modifier.width(64.dp))
        Text(value, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DeveloperPage() {
    val ctx = LocalContext.current
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(196.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111B2E)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painterResource(R.drawable.developer), contentDescription = "Developer",
                contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp).clip(CircleShape).border(2.dp, DeckA, CircleShape)
            )
            Spacer(Modifier.height(8.dp))
            Text("Maxido Ent.", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("MUSIC · TECH · CREATIVITY", color = TextGray, fontSize = 8.sp, letterSpacing = 1.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text("Built by music lovers, for music lovers.", color = Color(0xFFC9D4E8), fontSize = 10.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            PadButton("CHAT ON WHATSAPP", Modifier.fillMaxWidth(), active = true, color = Color(0xFF25D366), fontSize = 10) { openWhatsApp(ctx) }
            Spacer(Modifier.height(4.dp))
            Text("+254 115 710 055", color = TextGray, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CONTACT", color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            LinkRow("Email", "maxidoent@gmail.com", "mailto:maxidoent@gmail.com")
            LinkRow("YouTube", "@MaxidoEnt", "https://www.youtube.com/@MaxidoEnt")
            LinkRow("TikTok", "@maxidoent", "https://www.tiktok.com/@maxidoent")
            LinkRow("Website", "www.maxidoent.co.ke", "https://www.maxidoent.co.ke")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton("REPORT A PROBLEM", Modifier.weight(1f), fontSize = 10) {
                    openWhatsApp(ctx, "Bug report. MAXIDO DJ $version, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}:\n")
                }
                PadButton("SUGGEST A FEATURE", Modifier.weight(1f), active = true, color = Accent, fontSize = 10) {
                    openWhatsApp(ctx, "Feature idea for MAXIDO DJ:\n")
                }
            }
            Text("ABOUT THIS BUILD", color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Version $version  ·  Android 8.0 and newer", color = Color.White, fontSize = 11.sp)
            Text("Kotlin, Jetpack Compose, C++ audio engine (Oboe), ONNX Runtime with HT-Demucs for offline stems.", color = TextGray, fontSize = 10.sp)
            Text("Works fully offline. The app itself does not use the internet.", color = TextGray, fontSize = 10.sp)
            Text("© 2025 Maxido Ent. All rights reserved.", color = TextGray, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF111B2E)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = TextGray, fontSize = 10.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.width(46.dp).height(26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (on) Accent else Color(0xFF26344F)).clickable { onChange(!on) }.padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) Color(0xFF06101C) else TextGray)) }
    }
}

@Composable
private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun Choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> PadButton(l, Modifier.width(92.dp), active = selected == i) { onSelect(i) } }
    }
}

@Composable
private fun MixerSettings(decks: List<DeckState>) = SettingsPage {
    Text("TEMPO RANGE", color = TextGray, fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((r, label) in listOf(0.08f to "±8%", 0.16f to "±16%", 0.5f to "±50%"))
            PadButton(label, Modifier.width(80.dp), active = AppSettings.tempoRange == r) {
                AppSettings.tempoRange = r; decks.forEach { it.applyTempo(it.tempoPct) }; AppSettings.save()
            }
    }
    Text("CROSSFADER CURVE", color = TextGray, fontSize = 10.sp)
    Choice(listOf("SMOOTH", "SHARP (CUT)", "LINEAR"), AppSettings.crossCurve) {
        AppSettings.crossCurve = it; NativeEngine.setCrossfaderCurve(it); AppSettings.save()
    }
    Text(
        when (AppSettings.crossCurve) {
            1 -> "Sharp: both decks stay at full volume, then cut quickly at the ends. Made for scratching and fast cuts."
            2 -> "Linear: straight volume ramp, dips slightly in the middle."
            else -> "Smooth: constant-power blend, no volume dip in the middle. Best for long mixes."
        }, color = TextGray, fontSize = 10.sp
    )
    FxSlider("MASTER", AppSettings.masterGain, 0.25f..1.25f, Accent, "${(AppSettings.masterGain * 100).toInt()}%") {
        AppSettings.masterGain = it; NativeEngine.setMasterGain(it); AppSettings.save()
    }
}

@Composable
private fun StemSettings() {
    val ctx = LocalContext.current
    var usage by remember { mutableStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { usage = withContext(Dispatchers.IO) { Prepared.usageBytes(ctx) } }
    SettingsPage {
        Text("PREPARED TRACKS  (recommended on budget phones)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            "Prepare a track ahead of time (Library > P, or the STEMS panel). The phone then does all the heavy work once, in the background, " +
                "and saves vocal / melody / bass / drums, the beat grid and the key. Loading a prepared track is instant and uses almost no CPU while you play.",
            color = TextGray, fontSize = 10.sp
        )
        Text("PREPARE QUALITY", color = TextGray, fontSize = 10.sp)
        Choice(listOf("BETTER", "PRO"), AppSettings.prepQuality) { AppSettings.prepQuality = it; AppSettings.save() }
        Text(
            if (AppSettings.prepQuality == 1) "PRO: finest separation this app can do (4096 FFT, 4-way masks). Takes a few minutes per song on a budget phone; it runs at lowest priority."
            else "BETTER: faster to prepare, a little more bleed.",
            color = TextGray, fontSize = 10.sp
        )
        Text("WHILE A DECK PLAYS", color = TextGray, fontSize = 10.sp)
        Choice(listOf("PAUSE PREP", "KEEP GOING"), if (AppSettings.prepPause) 0 else 1) { AppSettings.prepPause = it == 0; AppSettings.save() }
        Text("STORAGE  ·  prepared tracks use ${usage / 1_048_576} MB  (about 170 MB per 4-minute song)", color = TextGray, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((mb, label) in listOf(1000 to "1 GB", 3000 to "3 GB", 8000 to "8 GB"))
                PadButton("CAP $label", Modifier.width(80.dp), active = AppSettings.prepCapMb == mb) { AppSettings.prepCapMb = mb; AppSettings.save() }
            PadButton("DELETE ALL", Modifier.width(92.dp)) { Prepared.deleteAll(ctx); refresh++ }
        }
        Text("When the cap is reached the least recently used prepared tracks are deleted first.", color = TextGray, fontSize = 10.sp)

        Text("LIVE STEMS  (tracks that are not prepared)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        ToggleRow(
            "Make stems on load",
            if (AppSettings.stemAuto) "Stems are made in the background right after you load a track. This uses the CPU while you play."
            else "Off: no stem work while you play. Only prepared tracks get stems. Best for smooth playback on budget phones.",
            AppSettings.stemAuto
        ) { AppSettings.stemAuto = it; AppSettings.save() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("FAST", "BETTER", "PRO", "AI").forEachIndexed { i, l ->
                PadButton(l, Modifier.width(76.dp), active = AppSettings.stemQuality == i, enabled = AppSettings.stemAuto) { AppSettings.stemQuality = i; AppSettings.save() }
            }
        }
        Text(
            if (AppSettings.stemQuality == 3 && !AppSettings.ramOkForAi(ctx)) "AI needs about 6 GB of RAM. This phone has less, so BETTER is used instead to avoid crashes."
            else when (AppSettings.stemQuality) {
                3 -> "AI: neural network (HT-Demucs), fully offline. Needs ~6 GB RAM, takes minutes per track. Falls back to BETTER if it can't run."
                2 -> "PRO live: very slow on a budget phone. Prefer preparing the track ahead of time."
                1 -> "Better: STFT percussive/harmonic + centre-channel split."
                else -> "Fast: simple filters. Ready in seconds, rougher separation."
            },
            color = TextGray, fontSize = 10.sp
        )
    }
}

@Composable
private fun PerfSettings() {
    val ctx = LocalContext.current
    val low = remember { AppSettings.lowEnd(ctx) }
    SettingsPage {
        Text(if (low) "This phone is detected as a budget device: gentle defaults are already applied." else "Performance options (this phone has plenty of RAM).", color = TextGray, fontSize = 10.sp)
        Text("AUDIO BUFFER", color = TextGray, fontSize = 10.sp)
        Choice(listOf("FAST", "BALANCED", "SAFE"), AppSettings.audioMode) {
            AppSettings.audioMode = it; AppSettings.save()
            if (!NativeEngine.setAudioMode(it)) Toast.makeText(ctx, "Could not restart audio", Toast.LENGTH_SHORT).show()
        }
        Text(
            when (AppSettings.audioMode) {
                0 -> "Fast: lowest delay. Can crackle on weak phones."
                1 -> "Balanced: a little more delay, much safer on budget chips."
                else -> "Safe: biggest buffer. Use this if you still hear crackling or dropouts."
            }, color = TextGray, fontSize = 10.sp
        )
        ToggleRow("Lite UI", "Redraws about 25 times a second with a lighter waveform, leaving the CPU free for the audio.", AppSettings.liteUi) {
            AppSettings.liteUi = it; AppSettings.save()
        }
        ToggleRow("Key lock by default", "Keeps the pitch when you change the tempo. Each deck also has its own KEYLOCK button.", AppSettings.keylock) {
            AppSettings.keylock = it; AppSettings.save()
        }
    }
}

@Composable
private fun SamplerSettings() = SettingsPage {
    Text("PAD MODE", color = TextGray, fontSize = 10.sp)
    Choice(listOf("ONE-SHOT", "HOLD", "LOOP"), AppSettings.padMode) { AppSettings.padMode = it; AppSettings.save() }
    Text(
        when (AppSettings.padMode) {
            1 -> "Hold: the sample plays only while you keep the pad pressed."
            2 -> "Loop: tap to start a looping sample, tap again to stop."
            else -> "One-shot: tap to play the sample once. Tap again to restart it."
        }, color = TextGray, fontSize = 10.sp
    )
    FxSlider("VOLUME", AppSettings.sampleVol, 0f..1f, Accent, "${(AppSettings.sampleVol * 100).toInt()}%") {
        AppSettings.sampleVol = it; NativeEngine.setSampleVolume(it); AppSettings.save()
    }
    Text("Sample packs are folders you add in the SAMPLER panel (BROWSE > + FOLDER). Up to 4 banks of 8 pads; assignments are remembered.", color = TextGray, fontSize = 10.sp)
    PadButton("STOP ALL SAMPLES", Modifier.width(150.dp)) { Sampler.stopAll() }
}

@Composable
private fun VideoSettings() = SettingsPage {
    Text("TRANSITION", color = TextGray, fontSize = 10.sp)
    Choice(listOf("BLEND", "DIP TO BLACK", "CUT"), AppSettings.videoBlend) { AppSettings.videoBlend = it; AppSettings.save() }
    Text("VIDEO FADER SOURCE", color = TextGray, fontSize = 10.sp)
    Choice(listOf("CROSSFADER", "MANUAL"), if (AppSettings.videoFollowCross) 0 else 1) {
        if (it == 1) VideoMixer.manualCross = VideoMixer.audioCross
        AppSettings.videoFollowCross = it == 0; AppSettings.save()
    }
    Text("VIDEO SPEED", color = TextGray, fontSize = 10.sp)
    Choice(listOf("FOLLOW TEMPO", "FIXED 1x"), if (AppSettings.videoFollowTempo) 0 else 1) { AppSettings.videoFollowTempo = it == 0; AppSettings.save() }
    FxSlider("BRIGHT", AppSettings.videoBright, 0f..1f, Accent, "${(AppSettings.videoBright * 100).toInt()}%") { AppSettings.videoBright = it; AppSettings.save() }
    Text("Load a video file as a track (FILE A / FILE B) and its picture stays locked to the deck. Extra looping clips go on the VIDEO panel.", color = TextGray, fontSize = 10.sp)
}

@Composable
private fun DisplaySettings() {
    val ctx = LocalContext.current
    SettingsPage {
        ToggleRow("Stay awake", "Keeps the screen on while MAXIDO DJ is open, which you want during a set.", AppSettings.keepAwake) {
            AppSettings.keepAwake = it; AppSettings.save()
            var c = ctx
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            (c as? android.app.Activity)?.window?.let { w ->
                if (AppSettings.keepAwake) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
