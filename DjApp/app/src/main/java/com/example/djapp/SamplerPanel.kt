package com.example.djapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val PAD8 = listOf(
    Color(0xFFFF5C7A), Color(0xFFFFB74D), Color(0xFFFFEE58), Color(0xFF9CCC65),
    Color(0xFF4DD0E1), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFFF06292)
)
private val MODE_LABELS = listOf("ONE-SHOT", "HOLD", "LOOP")

/** Sampler: always shows the 8 pads of the current bank; BROWSE opens the sample-pack folders. */
@Composable
fun SamplerPanel() {
    val ctx = LocalContext.current
    val addPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Sampler.addPack(ctx, uri)
    }
    LaunchedEffect(Unit) { while (true) { Sampler.poll(); delay(80) } }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (Sampler.browse) {
            Column(Modifier.weight(4f).fillMaxHeight()) { Browser { addPack.launch(null) } }
        }
        Column(Modifier.weight(5f).fillMaxHeight()) { Performance() }
    }
}

// ------------------------------------------------------------------ pads + controls
@Composable
private fun Performance() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..3) PadView(row * 4 + col, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Column(Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (b in 0 until Sampler.BANKS)
                    PadButton("${b + 1}", Modifier.weight(1f).height(26.dp), active = Sampler.bank == b, color = DeckALight, fontSize = 11) { Sampler.bank = b }
            }
            PadButton(MODE_LABELS[AppSettings.padMode], Modifier.fillMaxWidth().height(26.dp), fontSize = 10) {
                AppSettings.padMode = (AppSettings.padMode + 1) % 3; AppSettings.save()
            }
            FxSlider("VOL", AppSettings.sampleVol, 0f..1f, Accent, "${(AppSettings.sampleVol * 100).toInt()}") {
                AppSettings.sampleVol = it; NativeEngine.setSampleVolume(it); AppSettings.save()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PadButton("STOP", Modifier.weight(1f).height(26.dp), fontSize = 10) { Sampler.stopAll() }
                PadButton(if (Sampler.browse) "DONE" else "BROWSE", Modifier.weight(1.4f).height(26.dp), active = Sampler.browse, color = Accent, fontSize = 10) {
                    Sampler.browse = !Sampler.browse
                }
            }
        }
    }
}

@Composable
private fun PadView(i: Int, modifier: Modifier) {
    val slot = Sampler.bank * Sampler.PER_BANK + i
    val pad = Sampler.pads[slot]
    val col = PAD8[i]
    val shape = RoundedCornerShape(8.dp)
    val sel = Sampler.browse && Sampler.selected == i
    Box(
        modifier.clip(shape)
            .background(if (pad.playing) col else if (pad.loaded) col.copy(alpha = 0.38f) else Color(0xFF111B2E))
            .then(if (sel) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .padTouch(slot, i),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${i + 1}", color = if (pad.playing) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                if (pad.loaded) pad.name else if (sel) "pick a file" else "empty",
                color = if (pad.playing) Color.Black else TextGray, fontSize = 8.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 3.dp)
            )
        }
    }
}

/** Triggers on touch-down (no tap delay); in browse mode the touched pad also becomes the target for the next file. */
private fun Modifier.padTouch(slot: Int, idx: Int): Modifier = pointerInput(slot) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        if (Sampler.browse) Sampler.selected = idx
        Sampler.down(slot)
        waitForUpOrCancellation()
        Sampler.up(slot)
    }
}

// ------------------------------------------------------------------ browser
@Composable
private fun Browser(onAddFolder: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SAMPLE PACKS", color = TextGray, fontSize = 9.sp, modifier = Modifier.weight(1f))
            PadButton("+ FOLDER", Modifier.width(74.dp).height(24.dp), active = true, color = Accent, fontSize = 9, onClick = onAddFolder)
            PadButton("REMOVE", Modifier.width(62.dp).height(24.dp), enabled = Sampler.selectedPack >= 0, fontSize = 9) { Sampler.removePack(Sampler.selectedPack) }
        }
        if (Sampler.packs.isEmpty()) {
            Text(
                "Tap + FOLDER and choose a folder that holds your samples (kicks, loops, vocals…). Sub-folders are listed too. " +
                    "The permission is remembered.",
                color = TextGray, fontSize = 10.sp
            )
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(Sampler.packs.size) { i ->
                PadButton(Sampler.packs[i].name, Modifier.widthIn(min = 56.dp, max = 130.dp).height(24.dp), active = Sampler.selectedPack == i, color = DeckALight, fontSize = 9) {
                    Sampler.selectPack(i)
                }
            }
        }
        val folders = Sampler.folders
        if (folders.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item { PadButton("ALL", Modifier.width(44.dp).height(22.dp), active = Sampler.folder.isEmpty(), color = Accent, fontSize = 9) { Sampler.folder = "" } }
            items(folders) { f ->
                PadButton(f.substringAfterLast('/'), Modifier.widthIn(min = 44.dp, max = 110.dp).height(22.dp), active = Sampler.folder == f, color = Accent, fontSize = 9) { Sampler.folder = f }
            }
        }
        val list = Sampler.visible()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (Sampler.scanning) "Scanning…" else "${list.size} samples · tap a pad, then a file",
                color = TextGray, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1
            )
            PadButton("FILL BANK", Modifier.width(66.dp).height(22.dp), enabled = list.isNotEmpty(), fontSize = 9) { Sampler.autoFill() }
            Spacer(Modifier.width(4.dp))
            PadButton("CLEAR", Modifier.width(50.dp).height(22.dp), fontSize = 9) { Sampler.clearBank() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(list, key = { it.uri.toString() }) { e ->
                Row(
                    Modifier.fillMaxWidth().height(30.dp).clickable { Sampler.assignSelected(e) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(e.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (e.folder.isNotEmpty()) Text(e.folder.substringAfterLast('/'), color = TextGray, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
