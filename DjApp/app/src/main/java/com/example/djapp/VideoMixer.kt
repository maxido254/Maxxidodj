package com.example.djapp

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * One video per deck. The picture is muted (the audio engine owns all sound) and mixed on screen
 * with the crossfader, like a video-capable DJ app.
 *  - locked   : video came from the deck's own track, it follows the track position and tempo
 *  - free     : separate looping clip, plays while the deck plays (or when forced)
 */
class VideoLayer(val id: Int) {
    var name by mutableStateOf("")
    var ready by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var forcePlay by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var owner: Any? = null
    private var appliedSpeed = -1f

    fun load(ctx: Context, uri: Uri, lock: Boolean) {
        clear()
        locked = lock
        name = try {
            var n: String? = null
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0)
            }
            (n ?: "video").substringBeforeLast('.')
        } catch (_: Exception) { "video" }
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(ctx, uri)
            p.setVolume(0f, 0f)
            p.isLooping = !lock
            surface?.let { p.setSurface(it) }
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
            p.setOnPreparedListener { if (mp === it) { ready = true; appliedSpeed = -1f } }
            p.setOnErrorListener { _, what, extra ->
                // Budget phones can only run so many video decoders at once; say so instead of showing a silent black picture.
                if (mp === p) { ready = false; name = "Error: this phone could not play the video ($what/$extra)" }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            ready = false; name = "Error: ${e.message}"
        }
    }

    fun clear() {
        val p = mp
        mp = null; ready = false; locked = false; forcePlay = false; name = ""; appliedSpeed = -1f
        if (p != null) try { p.setSurface(null); p.reset(); p.release() } catch (_: Exception) {}
    }

    fun setSurface(o: Any, s: Surface) { owner = o; surface = s; try { mp?.setSurface(s) } catch (_: Exception) {} }
    fun clearSurface(o: Any) {
        if (owner !== o) return
        owner = null; surface = null
        try { mp?.setSurface(null) } catch (_: Exception) {}
    }

    /** Called ~5x per second from the UI loop. Keeps picture and deck together. */
    fun tick(d: DeckState, show: Boolean) {
        val p = mp ?: return
        if (!ready) return
        try {
            // Nothing to draw on (VID panel closed) or fully covered by the other video: do not decode at all.
            // Two 720p decoders running unseen was a big load on budget phones; the video re-syncs when shown again.
            val live = show && surface != null
            val want = live && (d.playing || (!locked && forcePlay))
            if (locked && live) {
                val dur = p.duration
                if (dur > 0) {
                    val target = (d.posSec * 1000).toLong().coerceAtLeast(0) % dur
                    if (abs(p.currentPosition - target) > 300) p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                }
            }
            if (want && !p.isPlaying) { p.start(); appliedSpeed = -1f }
            else if (!want && p.isPlaying) p.pause()
            if (p.isPlaying) {
                val s = if (AppSettings.videoFollowTempo) (1f + d.tempoPct).coerceIn(0.5f, 2f) else 1f
                if (abs(s - appliedSpeed) > 0.004f) {
                    p.playbackParams = p.playbackParams.setSpeed(s)
                    appliedSpeed = s
                }
            }
        } catch (_: Exception) { /* player in a transient state; next tick retries */ }
    }
}

object VideoMixer {
    val layers = listOf(VideoLayer(0), VideoLayer(1))
    var audioCross by mutableFloatStateOf(0.5f)
    var manualCross by mutableFloatStateOf(0.5f)

    val cross: Float get() = if (AppSettings.videoFollowCross) audioCross else manualCross

    /** Opacity of layer A (drawn underneath) and layer B (drawn on top). */
    fun alphas(): Pair<Float, Float> {
        val aOk = layers[0].ready; val bOk = layers[1].ready
        if (aOk && !bOk) return 1f to 0f
        if (!aOk && bOk) return 0f to 1f
        val x = cross
        return when (AppSettings.videoBlend) {
            1 -> (1f - 2f * x).coerceIn(0f, 1f) to (2f * x - 1f).coerceIn(0f, 1f)   // dip to black
            2 -> (if (x < 0.5f) 1f else 0f) to (if (x >= 0.5f) 1f else 0f)           // cut
            else -> 1f to x                                                         // A * (1-x) + B * x
        }
    }

    fun tick(decks: List<DeckState>) {
        val (a, b) = alphas()
        val aVisible = a > 0.01f && !(AppSettings.videoBlend == 0 && layers[1].ready && b > 0.99f)
        layers[0].tick(decks[0], aVisible)
        layers[1].tick(decks[1], b > 0.01f)
    }
    fun releaseAll() { layers.forEach { it.clear() } }
}
