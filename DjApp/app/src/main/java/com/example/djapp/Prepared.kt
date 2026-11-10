package com.example.djapp

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Its own low-priority thread, so preparing a library never competes with live stem work or the audio thread. */
private val prepQueueDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "prep-queue")
}.asCoroutineDispatcher()

class PrepInfo(
    val dir: File, val rate: Int, val bpm: Float, val offset: Double, val key: String, val title: String, val wave: WaveData?
) {
    val stemPaths: Array<String> get() = STEM_NAMES.map { File(dir, "$it.pcm").absolutePath }.toTypedArray()
}

/**
 * A "prepared" track = one folder in the app's own storage holding the 4 finished stems, the beat grid, the key
 * and the waveform. Loading one needs no decoding, no analysis and no separation: it is instant even on a weak phone,
 * and uses no CPU while you play. (Same idea as VirtualDJ's pre-made stem files.)
 */
object Prepared {
    private val keys = mutableStateMapOf<String, Boolean>()
    private var inited = false

    fun root(ctx: Context) = File(ctx.filesDir, "prep").apply { mkdirs() }

    fun keyOf(uri: Uri): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        root(ctx).listFiles()?.forEach { d ->
            if (d.isDirectory && !d.name.endsWith(".tmp")) {
                if (File(d, "meta.json").exists()) keys[d.name] = true else d.deleteRecursively()
            } else if (d.name.endsWith(".tmp")) d.deleteRecursively()    // interrupted job
        }
    }

    fun has(uri: Uri): Boolean = keys[keyOf(uri)] == true

    fun find(ctx: Context, uri: Uri): PrepInfo? = try {
        val dir = File(root(ctx), keyOf(uri))
        val meta = File(dir, "meta.json")
        if (!meta.exists() || !STEM_NAMES.all { File(dir, "$it.pcm").length() > 0 }) null
        else {
            val j = JSONObject(meta.readText())
            dir.setLastModified(System.currentTimeMillis())
            PrepInfo(dir, j.getInt("rate"), j.optDouble("bpm", 0.0).toFloat(), j.optDouble("offset", 0.0), j.optString("key", ""), j.optString("title", ""), readWave(File(dir, "wave.bin")))
        }
    } catch (_: Exception) { null }

    private fun readWave(f: File): WaveData? = try {
        DataInputStream(f.inputStream().buffered()).use { s ->
            val n = s.readInt(); val per = s.readInt(); val mx = s.readFloat()
            val pk = FloatArray(n) { s.readFloat() }; val lw = FloatArray(n) { s.readFloat() }
            WaveData(pk, lw, per, mx)
        }
    } catch (_: Exception) { null }

    private fun writeWave(f: File, w: WaveData) {
        DataOutputStream(f.outputStream().buffered()).use { s ->
            s.writeInt(w.peak.size); s.writeInt(w.perSec); s.writeFloat(w.maxPeak)
            for (v in w.peak) s.writeFloat(v); for (v in w.low) s.writeFloat(v)
        }
    }

    fun usageBytes(ctx: Context): Long = root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll(ctx: Context) { root(ctx).listFiles()?.forEach { it.deleteRecursively() }; keys.clear() }

    /** Keeps the store under [capMb]: oldest-used tracks go first. Never deletes [keep]. */
    fun prune(ctx: Context, capMb: Int, keep: String) {
        val dirs = root(ctx).listFiles { f -> f.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (d in dirs) {
            if (total <= capMb * 1_048_576L) break
            if (d.name == keep) continue
            val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively(); keys.remove(d.name); total -= sz
        }
    }

    fun commit(ctx: Context, tmp: File, uri: Uri): Boolean {
        val dir = File(root(ctx), keyOf(uri))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) return false
        keys[dir.name] = true
        prune(ctx, AppSettings.prepCapMb, dir.name)
        return true
    }

    fun writeMeta(dir: File, rate: Int, frames: Long, grid: BeatGrid?, key: String, title: String, wave: WaveData?) {
        File(dir, "meta.json").writeText(
            JSONObject().put("v", 1).put("rate", rate).put("frames", frames)
                .put("bpm", (grid?.bpm ?: 0f).toDouble()).put("offset", grid?.offsetSec ?: 0.0)
                .put("key", key).put("title", title).toString()
        )
        if (wave != null) writeWave(File(dir, "wave.bin"), wave)
    }
}

/**
 * Prepares tracks one after another in the background (decode -> beat grid + key -> stems -> waveform) and stores
 * them with [Prepared]. Runs at the lowest priority and, by default, pauses while a deck is playing.
 */
object PrepQueue {
    class Item(val uri: Uri, val title: String)

    val queue = mutableStateListOf<Item>()
    var current by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var running by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    private var stopFlag = false
    private val scope = CoroutineScope(SupervisorJob() + prepQueueDispatcher)

    fun add(ctx: Context, uri: Uri, title: String) {
        if (Prepared.has(uri) || queue.any { it.uri == uri } || current == title && running) return
        queue.add(Item(uri, title))
        start(ctx.applicationContext)
    }

    fun stop() { stopFlag = true; queue.clear() }

    private fun anyDeckPlaying() = NativeEngine.isPlaying(0) || NativeEngine.isPlaying(1)

    private fun start(app: Context) {
        if (running) return
        running = true; stopFlag = false
        scope.launch {
            try {
                while (!stopFlag) {
                    val item = queue.firstOrNull() ?: break
                    current = item.title; progress = 0f
                    try { prepareOne(app, item) } catch (_: Exception) {}
                    queue.remove(item)
                }
            } finally { running = false; current = ""; progress = 0f; paused = false }
        }
    }

    private fun checkpoint(): Boolean {
        // Called very often by the separator: wait here while music is playing so the audio thread keeps the CPU.
        while (!stopFlag && AppSettings.prepPause && anyDeckPlaying()) { paused = true; Thread.sleep(300) }
        paused = false
        return stopFlag
    }

    private fun prepareOne(app: Context, item: Item) {
        val root = Prepared.root(app)
        val tmpDir = File(root, Prepared.keyOf(item.uri) + ".tmp").also { it.deleteRecursively(); it.mkdirs() }
        val mix = File(app.cacheDir, "prep_mix.pcm")
        try {
            if (checkpoint()) return
            val rate = Decoder.decode(app, item.uri, mix)
            progress = 0.05f
            if (checkpoint()) return
            val grid = BpmDetector.grid(mix, rate)
            val k = KeyDetector.detect(mix, rate)
            progress = 0.1f
            val sep: StemSeparator = if (AppSettings.prepQuality == 0) HpssSeparator() else ProSeparator()
            val ok = sep.separate(mix, rate, tmpDir, { progress = 0.1f + 0.85f * it.coerceIn(0f, 1f) }, { checkpoint() })
            if (!ok || stopFlag) return
            val wave = WaveAnalyzer.analyze(mix, rate)
            Prepared.writeMeta(tmpDir, rate, mix.length() / 4, grid, if (k != null) "${k.camelot} ${k.name}" else "", item.title, wave)
            Prepared.commit(app, tmpDir, item.uri)
            progress = 1f
        } finally {
            mix.delete()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }
}
