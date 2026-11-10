package com.example.djapp

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The mixed picture: layer A underneath, layer B on top, opacity from [VideoMixer.alphas]. */
@Composable
fun VideoOutput(modifier: Modifier) {
    val (aA, aB) = VideoMixer.alphas()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSurface(VideoMixer.layers[0], aA)
        VideoSurface(VideoMixer.layers[1], aB)
        val dark = 1f - AppSettings.videoBright
        if (dark > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dark)))
    }
}

@Composable
private fun VideoSurface(layer: VideoLayer, alpha: Float) {
    val owner = remember { Any() }
    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var s: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val sf = Surface(st); s = sf; layer.setSurface(owner, sf)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        layer.clearSurface(owner); s?.release(); s = null; return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { it.alpha = alpha },
        modifier = Modifier.aspectRatio(layer.aspect)
    )
}

@Composable
fun VideoPanel(onPick: (Int) -> Unit, onTrack: (Int) -> Unit) {
    var full by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // preview
        Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black)) {
                if (full) Text("Output is fullscreen", color = TextGray, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
                else VideoOutput(Modifier.fillMaxSize())
            }
            PadButton("FULLSCREEN OUTPUT", Modifier.fillMaxWidth().height(28.dp), active = full, color = Accent, fontSize = 10) { full = true }
        }
        // layers
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerCard(0, DeckALight, onPick, onTrack)
            LayerCard(1, DeckB, onPick, onTrack)
        }
        // mix
        Column(Modifier.weight(1.05f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TRANSITION", color = TextGray, fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("BLEND", "DIP", "CUT").forEachIndexed { i, t ->
                    PadButton(t, Modifier.weight(1f).height(28.dp), active = AppSettings.videoBlend == i, fontSize = 10) { AppSettings.videoBlend = i; AppSettings.save() }
                }
            }
            Text("VIDEO FADER", color = TextGray, fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadButton("XFADER", Modifier.weight(1f).height(28.dp), active = AppSettings.videoFollowCross, color = Accent, fontSize = 10) { AppSettings.videoFollowCross = true; AppSettings.save() }
                PadButton("MANUAL", Modifier.weight(1f).height(28.dp), active = !AppSettings.videoFollowCross, color = Accent, fontSize = 10) {
                    VideoMixer.manualCross = VideoMixer.audioCross
                    AppSettings.videoFollowCross = false; AppSettings.save()
                }
            }
            val x = VideoMixer.cross
            FxSlider("MIX", x, 0f..1f, Accent, if (x < 0.02f) "A" else if (x > 0.98f) "B" else "${(x * 100).toInt()}") {
                if (!AppSettings.videoFollowCross) VideoMixer.manualCross = it
            }
            FxSlider("BRIGHT", AppSettings.videoBright, 0f..1f, Accent, "${(AppSettings.videoBright * 100).toInt()}%") {
                AppSettings.videoBright = it; AppSettings.save()
            }
            PadButton(if (AppSettings.videoFollowTempo) "SPEED FOLLOWS TEMPO" else "SPEED FIXED 1x", Modifier.fillMaxWidth().height(28.dp), active = AppSettings.videoFollowTempo, fontSize = 9) {
                AppSettings.videoFollowTempo = !AppSettings.videoFollowTempo; AppSettings.save()
            }
        }
    }
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = false }) {
            VideoOutput(Modifier.fillMaxSize())
            Text("tap to close", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
        }
    }
}

@Composable
private fun ColumnScope.LayerCard(i: Int, color: Color, onPick: (Int) -> Unit, onTrack: (Int) -> Unit) {
    val l = VideoMixer.layers[i]
    Column(
        Modifier.fillMaxWidth().weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(0xFF262930)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("DECK ${if (i == 0) "A" else "B"} VIDEO", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            when { l.name.isEmpty() -> "no video"; !l.ready && !l.name.startsWith("Error") -> "loading…  ${l.name}"; else -> l.name },
            color = TextGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadButton("VIDEO + SOUND", Modifier.weight(1.4f).height(26.dp), active = true, color = color, fontSize = 8) { onTrack(i) }
            PadButton("CLEAR", Modifier.weight(1f).height(26.dp), enabled = l.name.isNotEmpty(), fontSize = 9) { l.clear() }
        }
        PadButton("SILENT LOOP", Modifier.fillMaxWidth().height(24.dp), fontSize = 9) { onPick(i) }
        Text(
            if (l.locked) "Plays with the deck's sound, in sync" else if (l.name.isNotEmpty()) "Silent loop, plays while the deck plays" else "VIDEO + SOUND loads a video as the track",
            color = TextGray, fontSize = 8.sp, maxLines = 1
        )
        if (l.name.isNotEmpty() && !l.locked)
            PadButton(if (l.forcePlay) "PLAYING ALWAYS" else "PLAY WITHOUT DECK", Modifier.fillMaxWidth().height(24.dp), active = l.forcePlay, color = color, fontSize = 9) { l.forcePlay = !l.forcePlay }
    }
}
