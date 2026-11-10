package com.example.djapp

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

/** One low-priority thread for stem prep: decks queue up and the audio thread is never starved. */
private val prepDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "stem-prep")
}.asCoroutineDispatcher()

/** BPM / key / waveform analysis of a freshly loaded track: one background thread, so two quick loads never starve the audio. */
private val analysisDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "load-analysis")
}.asCoroutineDispatcher()

object AppSettings {
    var tempoRange by mutableFloatStateOf(0.08f)      // +/- fraction
    var modelStatus by mutableStateOf("")
    var stemQuality by mutableIntStateOf(1)           // live stems: 0 fast, 1 better, 2 pro (slow), 3 AI (Demucs, needs ~6 GB RAM)
    var stemAuto by mutableStateOf(true)              // prepare stems in the background when a track is loaded
    val aiStems get() = stemQuality == 3
    var prepQuality by mutableIntStateOf(1)           // prepared files: 0 better, 1 pro
    var prepPause by mutableStateOf(true)             // pause preparing while a deck is playing
    var prepCapMb by mutableIntStateOf(3000)          // storage cap for prepared tracks
    // Performance (budget phones)
    var audioMode by mutableIntStateOf(0)             // 0 fast, 1 balanced, 2 safe (bigger audio buffer)
    var liteUi by mutableStateOf(false)               // 30 fps UI, lighter waveform
    var keylock by mutableStateOf(true)               // key lock default for both decks

    // Mixer
    var crossCurve by mutableIntStateOf(0)            // 0 smooth, 1 sharp (cut), 2 linear
    var masterGain by mutableFloatStateOf(1f)
    // Sampler
    var padMode by mutableIntStateOf(0)               // 0 one-shot, 1 hold, 2 loop (toggle)
    var sampleVol by mutableFloatStateOf(0.8f)
    // Video
    var videoBlend by mutableIntStateOf(0)            // 0 blend, 1 dip to black, 2 cut
    var videoFollowCross by mutableStateOf(true)      // video fader follows the audio crossfader
    var videoFollowTempo by mutableStateOf(true)      // video speed follows deck tempo
    var videoBright by mutableFloatStateOf(1f)
    // Display
    var keepAwake by mutableStateOf(true)

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("maxxidodj", Context.MODE_PRIVATE); prefs = p
        tempoRange = p.getFloat("tempoRange", tempoRange)
        val low = lowEnd(ctx)
        stemQuality = p.getInt("stemQuality", if (p.getBoolean("aiStems", false)) 3 else if (p.getBoolean("betterStems", true)) 1 else 0)
        stemAuto = p.getBoolean("stemAuto", !low)
        prepQuality = p.getInt("prepQuality", prepQuality)
        prepPause = p.getBoolean("prepPause", prepPause)
        prepCapMb = p.getInt("prepCapMb", prepCapMb)
        audioMode = p.getInt("audioMode", if (low) 1 else 0)
        liteUi = p.getBoolean("liteUi", low)
        keylock = p.getBoolean("keylock", keylock)
        crossCurve = p.getInt("crossCurve", crossCurve)
        masterGain = p.getFloat("masterGain", masterGain)
        padMode = p.getInt("padMode", padMode)
        sampleVol = p.getFloat("sampleVol", sampleVol)
        videoBlend = p.getInt("videoBlend", videoBlend)
        videoFollowCross = p.getBoolean("videoFollowCross", videoFollowCross)
        videoFollowTempo = p.getBoolean("videoFollowTempo", videoFollowTempo)
        videoBright = p.getFloat("videoBright", videoBright)
        keepAwake = p.getBoolean("keepAwake", keepAwake)
    }

    fun save() {
        prefs?.edit()
            ?.putFloat("tempoRange", tempoRange)?.putInt("stemQuality", stemQuality)?.putBoolean("stemAuto", stemAuto)
            ?.putInt("prepQuality", prepQuality)?.putBoolean("prepPause", prepPause)?.putInt("prepCapMb", prepCapMb)
            ?.putInt("audioMode", audioMode)?.putBoolean("liteUi", liteUi)?.putBoolean("keylock", keylock)
            ?.putInt("crossCurve", crossCurve)?.putFloat("masterGain", masterGain)
            ?.putInt("padMode", padMode)?.putFloat("sampleVol", sampleVol)
            ?.putInt("videoBlend", videoBlend)?.putBoolean("videoFollowCross", videoFollowCross)
            ?.putBoolean("videoFollowTempo", videoFollowTempo)?.putFloat("videoBright", videoBright)
            ?.putBoolean("keepAwake", keepAwake)
            ?.apply()
    }

    /** Pushes the engine-side settings (call after the engine has started). */
    fun applyToEngine() {
        NativeEngine.setCrossfaderCurve(crossCurve)
        NativeEngine.setMasterGain(masterGain)
        NativeEngine.setSampleVolume(sampleVol)
        NativeEngine.setAudioMode(audioMode)
    }

    /** Budget phone: Android flags it as low-RAM, or it has under ~5 GB (the Itel A80 reports ~3.7 GB). */
    fun lowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return am.isLowRamDevice || mi.totalMem < 5_200_000_000L
    }

    /** HT-Demucs needs well over 1 GB of working memory; on ~4 GB phones Android kills the whole app. */
    fun ramOkForAi(ctx: Context): Boolean {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem >= 5_000_000_000L
    }
}

class DeckState(val id: Int, val accent: Color, val dark: Color) {
    var name by mutableStateOf(if (id == 0) "DECK A" else "DECK B")
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var bpm by mutableFloatStateOf(0f)
    var key by mutableStateOf("")                     // e.g. "8A Am" (Camelot + name), empty until detected
    var duration by mutableDoubleStateOf(0.0)
    var posSec by mutableDoubleStateOf(0.0)

    // Mixer / tempo
    var tempoPct by mutableFloatStateOf(0f)           // -range..+range
    var vol by mutableFloatStateOf(1f)

    // Effects
    var filter by mutableFloatStateOf(0f)
    var reverb by mutableFloatStateOf(0f)
    var room by mutableFloatStateOf(0.6f)
    var phaser by mutableFloatStateOf(0f)
    var phRate by mutableFloatStateOf(0.5f)

    // Stems
    val stemOn = mutableStateListOf(true, true, true, true)
    val stemLevel = mutableStateListOf(1f, 1f, 1f, 1f)
    var stemProg by mutableFloatStateOf(-1f)          // -1 none, 0..1 preparing, 2 ready
    val stemsReady get() = stemProg == 2f

    // Cue / hot cues / loops
    var cue = 0.0
    var wave by mutableStateOf<WaveData?>(null)
    val hotcues = mutableStateListOf(-1.0, -1.0, -1.0, -1.0)
    var loopIn by mutableDoubleStateOf(-1.0)
    var loopOut by mutableDoubleStateOf(-1.0)
    var loopActive by mutableStateOf(false)
    var loopBeats by mutableFloatStateOf(0f)          // 0 = manual loop

    // Key lock + beat sync
    var keylock by mutableStateOf(AppSettings.keylock)
    var beatOffset = 0.0                                // track time of a beat (see BeatGrid)
    var syncOn by mutableStateOf(false)
    private var syncMaster: DeckState? = null
    var curUri: Uri? = null
    var curTitle = ""

    private var prepJob: Job? = null
    private var curMix: File? = null
    private var previewing = false

    val effectiveBpm get() = bpm * (1f + tempoPct)
    fun pos(): Double = NativeEngine.getPosition(id)

    // ---------------------------------------------------------------- transport
    fun togglePlay() { playing = !playing; NativeEngine.setPlaying(id, playing) }

    /** CDJ-style cue: playing -> back to cue & pause; paused elsewhere -> set cue; hold at cue -> preview. */
    fun cueDown() {
        val p = pos()
        when {
            playing -> { NativeEngine.setPlaying(id, false); playing = false; NativeEngine.seek(id, cue) }
            abs(p - cue) < 0.06 -> { previewing = true; NativeEngine.setPlaying(id, true) }
            else -> cue = p
        }
    }
    fun cueUp() {
        if (previewing) { previewing = false; NativeEngine.setPlaying(id, false); NativeEngine.seek(id, cue) }
    }

    fun hotcue(i: Int) {
        if (hotcues[i] < 0) hotcues[i] = pos() else NativeEngine.seek(id, hotcues[i])
    }
    fun clearHotcue(i: Int) { hotcues[i] = -1.0 }

    // ---------------------------------------------------------------- tempo / sync
    /** Tempo fader moved by the user: this also ends sync (the DJ took over). */
    fun applyTempo(p: Float) { syncOn = false; syncMaster = null; setTempoRaw(p, 0f) }

    private fun setTempoRaw(p: Float, nudge: Float) {
        tempoPct = p.coerceIn(-0.5f, 0.5f)
        NativeEngine.setTempo(id, (1f + tempoPct) * (1f + nudge))
    }

    fun toggleKeylock() { keylock = !keylock; NativeEngine.setKeylock(id, keylock) }

    /** Fractional beat position (0..1) of the playhead on this deck's grid, or null without a grid. */
    private fun beatPhase(periodMult: Double = 1.0): Double? {
        if (bpm <= 0f) return null
        val period = 60.0 / bpm * periodMult
        val x = (pos() - beatOffset) / period
        return x - floor(x)
    }

    /**
     * Beat sync: this deck plays at EXACTLY [other]'s BPM (not a hair more or less), jumps to the same place
     * in the beat, and stays locked. The tempo is never nudged: if the beats ever drift apart (after a scratch,
     * say) the deck is re-aligned with a small jump instead. Tap the tempo fader to leave sync.
     */
    fun syncTo(other: DeckState): Boolean {
        if (bpm <= 0f || other.bpm <= 0f) return false
        syncMaster = other; syncOn = true
        updateSync(hard = true)
        return true
    }

    fun stopSync() { syncOn = false; syncMaster = null; setTempoRaw(tempoPct, 0f) }

    private fun updateSync(hard: Boolean) {
        val o = syncMaster ?: return
        if (bpm <= 0f || o.bpm <= 0f) return
        // Exact match whenever the tempo range can reach it (within +/-50 %). Only a track that is further away
        // than that (e.g. 64 vs 128 BPM) is matched in half / double time.
        var ratio = o.effectiveBpm / bpm
        var mult = 1.0                                   // my beat = mult of the other deck's beats
        while (ratio > 1.5f) { ratio /= 2f; mult *= 2.0 }
        while (ratio < 0.5f) { ratio *= 2f; mult /= 2.0 }
        val mine = beatPhase() ?: run { setTempoRaw(ratio - 1f, 0f); return }
        val theirs = o.beatPhase(mult) ?: run { setTempoRaw(ratio - 1f, 0f); return }
        var e = theirs - mine                            // beats; + means the other deck is ahead of me
        e -= Math.rint(e)
        setTempoRaw(ratio - 1f, 0f)                      // identical BPM, no nudging
        if (abs(e) > (if (hard) 0.004 else 0.06)) NativeEngine.seek(id, (pos() + e * 60.0 / bpm).coerceAtLeast(0.0))
    }

    /** ~5x per second from the UI loop: keeps a synced deck locked to its partner. */
    fun syncTick() {
        if (!syncOn || !playing || !loaded) return
        val m = syncMaster ?: return
        if (!m.playing) return
        updateSync(hard = false)
    }

    // ---------------------------------------------------------------- loops
    private fun pushLoop() = NativeEngine.setLoop(id, loopIn, loopOut, loopActive)

    fun beatLoop(beats: Float) {
        if (bpm <= 0f) return
        if (loopActive && loopBeats == beats) { exitLoop(); return }
        val s = pos()
        loopIn = s; loopOut = s + beats * 60.0 / bpm; loopBeats = beats; loopActive = true; pushLoop()
    }
    fun loopInSet() {
        loopIn = pos(); loopBeats = 0f
        if (loopOut > loopIn + 0.02) pushLoop() else { loopOut = -1.0; loopActive = false; pushLoop() }
    }
    /** "Loop out": closes the loop at the current position and starts looping. */
    fun loopOutSet() {
        val p = pos()
        if (loopIn < 0 || p <= loopIn + 0.02) return
        loopOut = p; loopBeats = 0f; loopActive = true; pushLoop()
    }
    fun exitLoop() { loopActive = false; if (loopIn >= 0 && loopOut > loopIn) pushLoop() }
    fun reloop() {
        if (loopIn >= 0 && loopOut > loopIn) { loopActive = true; pushLoop(); NativeEngine.seek(id, loopIn) }
    }
    fun halveLoop() {
        if (!loopActive) return
        val len = (loopOut - loopIn) / 2
        if (len < 0.02) return
        loopOut = loopIn + len; if (loopBeats > 0) loopBeats /= 2; pushLoop()
    }
    fun doubleLoop() {
        if (!loopActive) return
        loopOut = loopIn + (loopOut - loopIn) * 2; if (loopBeats > 0) loopBeats *= 2; pushLoop()
    }

    // ---------------------------------------------------------------- effects
    fun pushFilter() = NativeEngine.setFilter(id, filter)

    /** EQ gains in dB for bass / mid / treble (-26 = almost off, +12 = boost). */
    val eq = mutableStateListOf(0f, 0f, 0f)
    fun setEq(band: Int, db: Float) { eq[band] = db; NativeEngine.setEq(id, band, db) }
    fun pushReverb() = NativeEngine.setReverb(id, reverb, room)
    fun pushPhaser() = NativeEngine.setPhaser(id, phaser, phRate)
    fun pushVolume() = NativeEngine.setVolume(id, vol)
    fun resetFx() {
        filter = 0f; reverb = 0f; phaser = 0f; pushFilter(); pushReverb(); pushPhaser()
        for (b in 0..2) setEq(b, 0f)
    }

    // ---------------------------------------------------------------- stems
    private fun stemGain(i: Int) = if (stemOn[i]) stemLevel[i] else 0f
    fun pushStem(i: Int) = NativeEngine.setStemVolume(id, i, stemGain(i))
    fun toggleStem(i: Int) { stemOn[i] = !stemOn[i]; pushStem(i) }
    fun setStemLevel(i: Int, v: Float) { stemLevel[i] = v; if (!stemOn[i] && v > 0f) stemOn[i] = true; pushStem(i) }
    fun soloStem(i: Int) { for (j in 0..3) { stemOn[j] = j == i; pushStem(j) } }
    fun muteOnly(i: Int) { for (j in 0..3) { stemOn[j] = j != i; pushStem(j) } }
    fun resetStems() { for (j in 0..3) { stemOn[j] = true; stemLevel[j] = 1f; pushStem(j) } }

    // ---------------------------------------------------------------- loading
    fun load(ctx: Context, scope: CoroutineScope, uri: Uri) {
        prepJob?.cancel()
        // Video files carry their picture along: it is locked to this deck's track position.
        val layer = VideoMixer.layers[id]
        if (ctx.contentResolver.getType(uri)?.startsWith("video/") == true) layer.load(ctx, uri, lock = true)
        else if (layer.locked) layer.clear()
        stemProg = -1f; resetStemsUi(); wave = null
        syncOn = false; syncMaster = null; setTempoRaw(tempoPct, 0f); beatOffset = 0.0
        curUri = uri
        loading = true; loaded = false; name = "Loading…"; playing = false; bpm = 0f; key = ""; duration = 0.0; posSec = 0.0
        NativeEngine.setPlaying(id, false)
        NativeEngine.setKeylock(id, keylock)
        cue = 0.0; loopActive = false; loopIn = -1.0; loopOut = -1.0; loopBeats = 0f
        for (i in 0..3) hotcues[i] = -1.0
        scope.launch {
            try {
                // Prepared track (made ahead of time with PREP): instant, no decoding or analysis.
                val prep = withContext(Dispatchers.IO) { Prepared.find(ctx, uri) }
                if (prep != null && withContext(Dispatchers.IO) { NativeEngine.loadDeckStems(id, prep.stemPaths, prep.rate) }) {
                    curMix?.delete(); curMix = null
                    name = displayName(ctx, uri); curTitle = name
                    duration = NativeEngine.getDuration(id)
                    bpm = prep.bpm; beatOffset = prep.offset; key = prep.key; wave = prep.wave
                    stemProg = 2f
                    loading = false; loaded = true
                    return@launch
                }
                // Unique file per load: never overwrite a file the engine has mapped.
                val f = File(ctx.cacheDir, "deck${id}_${System.nanoTime()}.pcm")
                val rate = withContext(Dispatchers.IO) {
                    // Slightly lower priority than the UI and audio threads, so loading a long file does not make the screen stutter.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                    try { Decoder.decode(ctx, uri, f) } finally { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
                }
                withContext(Dispatchers.IO) { NativeEngine.loadDeck(id, f.absolutePath, rate) }   // maps the file: keep it off the UI thread
                curMix?.delete(); curMix = f
                name = displayName(ctx, uri); curTitle = name
                duration = NativeEngine.getDuration(id)
                loading = false; loaded = true
                launch(analysisDispatcher) {
                    val g = BpmDetector.grid(f, rate)
                    if (curMix == f && g != null) { beatOffset = g.offsetSec; bpm = g.bpm }
                    val k = KeyDetector.detect(f, rate)
                    if (curMix == f) key = if (k != null) "${k.camelot} ${k.name}" else ""
                    val wd = WaveAnalyzer.analyze(f, rate); if (curMix == f) wave = wd
                }
                // Track is playable right away. Stems are prepared in the background only when allowed
                // (budget phones: use PREP ahead of time instead, so nothing heavy runs during a set).
                if (AppSettings.stemAuto) {
                    stemProg = 0f
                    val sep: StemSeparator = when (AppSettings.stemQuality) { 0 -> LiteSeparator(); 2 -> ProSeparator(); else -> HpssSeparator() }
                    prepJob = scope.launch(prepDispatcher) {
                        val key = uri.toString().hashCode().toUInt().toString(16)
                        var paths: Array<String>? = null
                        if (AppSettings.aiStems && AppSettings.ramOkForAi(ctx)) {
                            paths = StemCache.prepare(ctx, f, rate, key, { stemProg = it.coerceIn(0f, 1f) }, { !isActive }, DemucsSeparator(ctx))
                            if (paths == null && isActive) stemProg = 0f
                        }
                        if (paths == null && isActive) {
                            paths = StemCache.prepare(ctx, f, rate, key, { stemProg = it.coerceIn(0f, 1f) }, { !isActive }, sep)
                        }
                        if (isActive && paths != null) { NativeEngine.setStems(id, paths); stemProg = 2f }
                        else if (isActive) stemProg = -1f
                    }
                }
            } catch (e: Exception) { name = "Error: ${e.message}"; loading = false }
        }
    }

    private fun resetStemsUi() { for (i in 0..3) { stemOn[i] = true; stemLevel[i] = 1f } }

    private fun displayName(ctx: Context, uri: Uri): String {
        var n: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0)
            }
        } catch (_: Exception) {}
        return (n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Loaded").substringBeforeLast('.')
    }
}

/** Estimates BPM from a 75 s slice of the decoded track (onset envelope + autocorrelation). */
/** Constant-tempo beat grid: beats fall at offsetSec + k * 60 / bpm (track time). */
class BeatGrid(val bpm: Float, val offsetSec: Double)

object BpmDetector {
    fun detect(pcm: File, rate: Int): Float = grid(pcm, rate)?.bpm ?: 0f
    fun grid(pcm: File, rate: Int): BeatGrid? = try { analyze(pcm, rate) } catch (_: Exception) { null }

    private fun analyze(pcm: File, rate: Int): BeatGrid? {
        val hop = 256
        val total = pcm.length() / 4
        val start = if (total > rate * 110L) rate * 30L else 0L
        val count = minOf(total - start, rate * 75L)
        if (count < rate * 8L) return null
        val n = (count / hop).toInt()
        val env = FloatArray(n)
        RandomAccessFile(pcm, "r").use { raf ->
            raf.seek(start * 4)
            val blocksPer = 64
            val buf = ByteArray(hop * 4 * blocksPer)
            val a = (1.0 - exp(-2.0 * PI * 200.0 / rate)).toFloat()
            var lp = 0f; var idx = 0
            while (idx < n) {
                val blocks = minOf(blocksPer, n - idx)
                raf.readFully(buf, 0, blocks * hop * 4)
                val sb = ByteBuffer.wrap(buf, 0, blocks * hop * 4).order(ByteOrder.nativeOrder()).asShortBuffer()
                for (b in 0 until blocks) {
                    var eLow = 0f; var eAll = 0f
                    for (i in 0 until hop) {
                        val j = (b * hop + i) * 2
                        val m = (sb.get(j) + sb.get(j + 1)) * (0.5f / 32768f)
                        lp += a * (m - lp); eLow += lp * lp; eAll += m * m
                    }
                    env[idx++] = sqrt(eLow / hop) + 0.5f * sqrt(eAll / hop)
                }
            }
        }
        // Onset strength: positive change of compressed energy, minus local mean.
        val onset = FloatArray(n)
        for (i in 1 until n) onset[i] = max(0f, ln(1f + 100f * env[i]) - ln(1f + 100f * env[i - 1]))
        val win = 70
        val sm = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) { acc += onset[i]; if (i >= win) acc -= onset[i - win]; sm[i] = acc / min(i + 1, win) }
        for (i in 0 until n) onset[i] = max(0f, onset[i] - sm[i])

        val envRate = rate / hop.toDouble()
        val minLag = floor(envRate * 60 / 180).toInt()
        val maxLag = ceil(envRate * 60 / 65).toInt()
        val maxAc = min(maxLag * 2 + 1, n / 2)
        val ac = FloatArray(maxAc + 1)
        for (lag in minLag..maxAc) {
            var s = 0f
            for (i in 0 until n - lag) s += onset[i] * onset[i + lag]
            ac[lag] = s / (n - lag)
        }
        var best = -1; var bestScore = 0f
        for (lag in minLag..min(maxLag, maxAc)) {
            val s = ac[lag] + if (lag * 2 <= maxAc) 0.5f * ac[lag * 2] else 0f
            if (s > bestScore) { bestScore = s; best = lag }
        }
        if (best <= minLag || best >= maxAc) return null
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val den = y0 - 2 * y1 + y2
        val off = if (den != 0f) (0.5f * (y0 - y2) / den).coerceIn(-0.5f, 0.5f) else 0f
        var coarse = 60.0 * envRate / (best + off)
        while (coarse < 80.0) coarse *= 2.0
        while (coarse > 175.0) coarse /= 2.0

        // Refine: try every tempo within +-1.5 BPM in 0.01 steps. For the right tempo all onsets fold onto one
        // phase of the beat, so the folded histogram has the sharpest peak. That peak is also the beat phase.
        val P = 64
        val hist = DoubleArray(P)
        var bestPeak = -1.0; var bestBpm = coarse; var bestPhase = 0.0
        var cand = coarse - 1.5
        while (cand <= coarse + 1.5 + 1e-9) {
            val period = envRate * 60.0 / cand
            java.util.Arrays.fill(hist, 0.0)
            for (i in 0 until n) {
                val o = onset[i]
                if (o <= 0f) continue
                val ph = i / period
                val pos = (ph - floor(ph)) * P
                val b0 = pos.toInt() % P
                val fr = pos - floor(pos)
                hist[b0] += o * (1.0 - fr); hist[(b0 + 1) % P] += o * fr
            }
            var mx = 0.0; var mi = 0
            for (b in 0 until P) {
                val sB = hist[(b + P - 1) % P] + 2 * hist[b] + hist[(b + 1) % P]
                if (sB > mx) { mx = sB; mi = b }
            }
            if (mx > bestPeak) {
                bestPeak = mx; bestBpm = cand
                val l = hist[(mi + P - 1) % P]; val c = hist[mi]; val r = hist[(mi + 1) % P]
                bestPhase = ((mi + (r - l) / (l + c + r + 1e-12)) / P).let { it - floor(it) }
            }
            cand += 0.01
        }
        val periodSec = 60.0 / bestBpm
        val firstInSlice = bestPhase * (envRate * 60.0 / bestBpm) / envRate     // seconds after the slice start
        var offset = (start.toDouble() / rate + firstInSlice) % periodSec
        if (offset < 0) offset += periodSec
        return BeatGrid((bestBpm * 100.0).roundToInt() / 100f, offset)
    }
}


/** EQ fader position (0..1, middle = flat) <-> dB. Down to -26 dB (nearly a kill), up to +12 dB (boost). */
fun eqFromFrac(f: Float): Float = if (f < 0.5f) -26f * (1f - 2f * f) else 12f * (2f * f - 1f)
fun eqToFrac(db: Float): Float = if (db < 0f) 0.5f - db / 52f else 0.5f + db / 24f
fun eqText(db: Float): String = if (db < -25f) "KILL" else if (abs(db) < 0.5f) "FLAT" else "%+.0f dB".format(db)
