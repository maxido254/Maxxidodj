package com.example.djapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One audio file on the device, plus its analysis (once done). */
class Track(
    val id: Long, val uri: Uri, val title: String, val artist: String,
    val durationMs: Long, val size: Long, val modified: Long,
    val analysed: Boolean = false, val bpm: Float = 0f, val key: String = "", val camelot: String = "",
    val isVideo: Boolean = false, val path: String = ""
) {
    /** Changes when the file changes, so edited files get re-analysed. */
    val cacheKey: String get() = "${id}_${size}_$modified"

    fun withAnalysis(bpm: Float, key: KeyResult?) =
        Track(id, uri, title, artist, durationMs, size, modified, true, bpm, key?.name ?: "", key?.camelot ?: "", isVideo, path)
}

/** A folder the user chose to browse (path relative to the storage root, e.g. "Music/Mixes"). */
class LibFolder(val rel: String, val name: String)

enum class SortBy(val label: String) { TITLE("TITLE"), ARTIST("ARTIST"), BPM("BPM"), KEY("KEY"), LENGTH("TIME") }

/** One low-priority thread for library analysis, so the audio thread and the UI are never starved. */
private val analysisDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "library-analysis")
}.asCoroutineDispatcher()

/**
 * The in-app music library: scans the device's audio (MediaStore), analyses BPM + key of every track
 * offline in the background, remembers the results on disk, and sorts them.
 */
object Library {
    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var analysing by mutableStateOf(false)
        private set
    var sortBy by mutableStateOf(SortBy.TITLE)
    var ascending by mutableStateOf(true)

    // Folders the user picked: the library can show just one of them instead of every file on the phone.
    val folders = mutableStateListOf<LibFolder>()
    var activeFolder by mutableStateOf(-1)          // -1 = everything on the phone
    private var foldersLoaded = false

    fun inFolder(t: Track, f: LibFolder?) = f == null || t.path.contains("/${f.rel}/", ignoreCase = true)

    fun loadFolders(ctx: Context) {
        if (foldersLoaded) return
        foldersLoaded = true
        try {
            val a = org.json.JSONArray(ctx.getSharedPreferences("maxxidodj", Context.MODE_PRIVATE).getString("libFolders", "[]"))
            for (i in 0 until a.length()) { val r = a.getString(i); folders.add(LibFolder(r, r.substringAfterLast('/'))) }
        } catch (_: Exception) { }
    }

    private fun saveFolders(ctx: Context) {
        val a = org.json.JSONArray(); folders.forEach { a.put(it.rel) }
        ctx.getSharedPreferences("maxxidodj", Context.MODE_PRIVATE).edit().putString("libFolders", a.toString()).apply()
    }

    /** Adds the folder picked with the system folder picker. Returns false when it is not a usable folder (e.g. the storage root). */
    fun addFolder(ctx: Context, tree: Uri): Boolean {
        val rel = try { DocumentsContract.getTreeDocumentId(tree).substringAfter(':', "").trim('/') } catch (_: Exception) { "" }
        if (rel.isEmpty()) return false
        loadFolders(ctx)
        var i = folders.indexOfFirst { it.rel.equals(rel, ignoreCase = true) }
        if (i < 0) { folders.add(LibFolder(rel, rel.substringAfterLast('/'))); i = folders.size - 1; saveFolders(ctx) }
        activeFolder = i
        return true
    }

    fun removeActiveFolder(ctx: Context) {
        if (activeFolder in folders.indices) { folders.removeAt(activeFolder); saveFolders(ctx) }
        activeFolder = -1
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + analysisDispatcher)
    private var job: Job? = null
    private var cache = JSONObject()
    private var cacheLoaded = false

    // ------------------------------------------------------------ scanning
    suspend fun scan(ctx: Context) {
        if (scanning) return
        scanning = true
        try {
            val app = ctx.applicationContext
            val found = withContext(Dispatchers.IO) { query(app) }
            synchronized(lock) { tracks = found }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // no permission / provider problem: keep whatever list we had
        } finally {
            scanning = false
        }
    }

    private fun query(ctx: Context): List<Track> {
        loadCache(ctx)
        val a = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA
        )
        // Anything at least 45 s long that is not a ringtone / alarm / notification.
        val sel = "${MediaStore.Audio.Media.DURATION} >= 45000 AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0 " +
            "AND ${MediaStore.Audio.Media.IS_ALARM} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0"
        val out = ArrayList<Track>()
        ctx.contentResolver.query(a, proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val fileName = (c.getString(iName) ?: "").substringBeforeLast('.')
                val title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: fileName.ifBlank { "Track $id" }
                var artist = c.getString(iArtist) ?: ""
                if (artist == "<unknown>") artist = ""
                val uri = ContentUris.withAppendedId(a, id)
                var t = Track(id, uri, title, artist, c.getLong(iDur), c.getLong(iSize), c.getLong(iMod), path = c.getString(iData) ?: "")
                val hit = synchronized(lock) { cache.optJSONObject(t.cacheKey) }
                if (hit != null) {
                    t = Track(
                        t.id, t.uri, t.title, t.artist, t.durationMs, t.size, t.modified, true,
                        hit.optDouble("b", 0.0).toFloat(), hit.optString("k", ""), hit.optString("c", ""), t.isVideo, t.path
                    )
                }
                out.add(t)
            }
        }
        // Video files too: their sound plays like a track and the picture is mixed on the VID screen.
        try {
            val v = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val vp = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA
            )
            ctx.contentResolver.query(v, vp, "${MediaStore.Video.Media.DURATION} >= 20000", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val vid = c.getLong(0)
                    val fileName = (c.getString(5) ?: "").substringBeforeLast('.')
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: fileName.ifBlank { "Video $vid" }
                    val id = vid + (1L shl 40)                       // never collides with an audio id
                    var t = Track(id, ContentUris.withAppendedId(v, vid), title, "", c.getLong(2), c.getLong(3), c.getLong(4), isVideo = true, path = c.getString(6) ?: "")
                    val hit = synchronized(lock) { cache.optJSONObject(t.cacheKey) }
                    if (hit != null) t = Track(t.id, t.uri, t.title, t.artist, t.durationMs, t.size, t.modified, true,
                        hit.optDouble("b", 0.0).toFloat(), hit.optString("k", ""), hit.optString("c", ""), true, t.path)
                    out.add(t)
                }
            }
        } catch (_: Exception) { /* no video permission: audio library still works */ }
        return out
    }

    // ------------------------------------------------------------ analysis
    /** Analyses every track that has no BPM/key yet, one after another, in the background. */
    fun startAnalysis(ctx: Context, only: ((Track) -> Boolean)? = null) {
        if (analysing) return
        val app = ctx.applicationContext
        analysing = true
        job = scope.launch {
            var sinceSave = 0
            try {
                while (isActive) {
                    val next = synchronized(lock) { tracks.firstOrNull { !it.analysed && (only?.invoke(it) ?: true) } } ?: break
                    val done = analyseOne(app, next)
                    synchronized(lock) {
                        cache.put(done.cacheKey, JSONObject().put("b", done.bpm.toDouble()).put("k", done.key).put("c", done.camelot))
                        tracks = tracks.map { if (it.id == done.id) done else it }
                    }
                    if (++sinceSave >= 10) { saveCache(app); sinceSave = 0 }
                }
            } finally {
                saveCache(app)
                analysing = false
            }
        }
    }

    fun stopAnalysis() { job?.cancel() }

    /** Decodes ~80 s from the middle of the track, then runs both detectors on it. */
    private fun analyseOne(ctx: Context, t: Track): Track {
        val tmp = File(ctx.cacheDir, "analysis_${t.id}.pcm")
        return try {
            val startSec = if (t.durationMs > 110_000L) 30.0 else 0.0
            val rate = Decoder.decode(ctx, t.uri, tmp, startSec, 80.0)
            t.withAnalysis(BpmDetector.detect(tmp, rate), KeyDetector.detect(tmp, rate))
        } catch (_: Exception) {
            t.withAnalysis(0f, null)        // unreadable file: remembered as "no result" so it isn't retried every time
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ sorting
    /** Tracks without the sorted-by value (not analysed yet) always go to the end, whichever direction. */
    fun sorted(list: List<Track>, by: SortBy, asc: Boolean): List<Track> {
        val (have, missing) = list.partition {
            when (by) {
                SortBy.BPM -> it.bpm > 0f
                SortBy.KEY -> it.camelot.isNotEmpty()
                else -> true
            }
        }
        val byTitle = compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val cmp: Comparator<Track> = when (by) {
            SortBy.TITLE -> byTitle
            SortBy.ARTIST -> compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle)
            SortBy.BPM -> compareBy<Track> { it.bpm }.then(byTitle)
            SortBy.KEY -> compareBy<Track> { KeyDetector.camelotOrder(it.camelot) }.thenBy { it.bpm }.then(byTitle)
            SortBy.LENGTH -> compareBy<Track> { it.durationMs }.then(byTitle)
        }
        return have.sortedWith(if (asc) cmp else cmp.reversed()) + missing.sortedWith(byTitle)
    }

    // ------------------------------------------------------------ cache on disk
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library_cache.json")

    private fun loadCache(ctx: Context) {
        synchronized(lock) {
            if (cacheLoaded) return
            cache = try { JSONObject(cacheFile(ctx).readText()) } catch (_: Exception) { JSONObject() }
            cacheLoaded = true
        }
    }

    private fun saveCache(ctx: Context) {
        try {
            val text = synchronized(lock) { cache.toString() }
            val tmp = File(ctx.filesDir, "library_cache.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(cacheFile(ctx))
        } catch (_: Exception) { }
    }
}
