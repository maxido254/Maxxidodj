package com.example.djapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * Sample packs: any folder on the phone (picked once, permission is remembered) becomes a pack.
 * 4 banks x 8 pads. Samples are decoded (max 30 s) into the cache and memory-mapped by the engine.
 */
object Sampler {
    const val BANKS = 4
    const val PER_BANK = 8

    class Pad {
        var name by mutableStateOf("")
        var loaded by mutableStateOf(false)
        var playing by mutableStateOf(false)
        var uri = ""
        var file: File? = null
    }
    class Pack(val uri: Uri, val name: String)
    class Entry(val name: String, val uri: Uri, val folder: String)

    val pads = List(BANKS * PER_BANK) { Pad() }
    val packs = mutableStateListOf<Pack>()
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var selectedPack by mutableIntStateOf(-1)
    var folder by mutableStateOf("")                 // "" = every folder of the pack
    var bank by mutableIntStateOf(0)
    var selected by mutableIntStateOf(0)             // pad (0..7) that the next browsed file goes to
    var browse by mutableStateOf(false)              // browser open (panel grows to full height)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val prefs get() = app.getSharedPreferences("maxxidodj_sampler", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        val granted = app.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        try {
            val a = JSONArray(prefs.getString("packs", "[]"))
            for (i in 0 until a.length()) {
                val u = Uri.parse(a.getString(i))
                if (u.toString() in granted) packs.add(Pack(u, packName(u)))
            }
        } catch (_: Exception) {}
        if (packs.isNotEmpty()) selectPack(0)
        // Restore pad assignments in the background.
        scope.launch {
            try {
                val us = JSONArray(prefs.getString("padUris", "[]")); val ns = JSONArray(prefs.getString("padNames", "[]"))
                for (i in 0 until minOf(us.length(), pads.size)) {
                    val u = us.getString(i)
                    if (u.isNotEmpty()) loadPad(i, Uri.parse(u), ns.optString(i, ""))
                }
            } catch (_: Exception) {}
        }
    }

    private fun packName(tree: Uri): String = try {
        DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Storage" }
    } catch (_: Exception) { "Pack" }

    private fun save() {
        val pa = JSONArray(); packs.forEach { pa.put(it.uri.toString()) }
        val pu = JSONArray(); val pn = JSONArray()
        pads.forEach { pu.put(if (it.loaded) it.uri else ""); pn.put(it.name) }
        prefs.edit().putString("packs", pa.toString()).putString("padUris", pu.toString()).putString("padNames", pn.toString()).apply()
    }

    // ------------------------------------------------------------------ packs / browsing
    fun addPack(ctx: Context, tree: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val existing = packs.indexOfFirst { it.uri == tree }
        if (existing >= 0) { selectPack(existing); return }
        packs.add(Pack(tree, packName(tree)))
        save()
        selectPack(packs.size - 1)
    }

    fun removePack(i: Int) {
        if (i !in packs.indices) return
        packs.removeAt(i); save()
        if (packs.isEmpty()) { selectedPack = -1; entries = emptyList() } else selectPack(0)
    }

    fun selectPack(i: Int) {
        if (i !in packs.indices) return
        selectedPack = i; folder = ""
        val tree = packs[i].uri
        scope.launch {
            scanning = true
            val list = try { listAudio(tree) } catch (_: Exception) { emptyList() }
            if (selectedPack == i) entries = list
            scanning = false
        }
    }

    val folders: List<String> get() = entries.map { it.folder }.filter { it.isNotEmpty() }.distinct()
    fun visible(): List<Entry> = if (folder.isEmpty()) entries else entries.filter { it.folder == folder }

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in setOf("wav", "mp3", "ogg", "flac", "m4a", "aac", "opus")

    private fun listAudio(tree: Uri): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(docId: String, rel: String, depth: Int) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = ArrayList<Pair<String, String>>()
            app.contentResolver.query(
                kids,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: ""; val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 3) dirs.add(id to name) }
                    else if (isAudio(name, mime)) out.add(Entry(name.substringBeforeLast('.'), DocumentsContract.buildDocumentUriUsingTree(tree, id), rel))
                }
            }
            for ((id, n) in dirs) walk(id, if (rel.isEmpty()) n else "$rel/$n", depth + 1)
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
    }

    // ------------------------------------------------------------------ pads
    private fun loadPad(slot: Int, uri: Uri, name: String) {
        val p = pads[slot]
        val f = File(app.cacheDir, "smp${slot}_${System.nanoTime()}.pcm")
        try {
            val rate = Decoder.decode(app, uri, f, 0.0, 30.0)
            if (NativeEngine.loadSample(slot, f.absolutePath, rate)) {
                p.file?.delete(); p.file = f
                p.uri = uri.toString(); p.name = name; p.loaded = true; p.playing = false
            } else { f.delete() }
        } catch (_: Exception) { f.delete() }
    }

    /** Puts a browsed file on the selected pad and moves the selection on to the next one. */
    fun assignSelected(entry: Entry) {
        val slot = bank * PER_BANK + selected
        selected = (selected + 1) % PER_BANK
        scope.launch {
            loadPad(slot, entry.uri, entry.name); save()
            if (pads[slot].loaded) { NativeEngine.triggerSample(slot, false) }   // audition
        }
    }

    fun autoFill() {
        val list = visible().take(PER_BANK); val b = bank
        scope.launch { list.forEachIndexed { i, e -> loadPad(b * PER_BANK + i, e.uri, e.name) }; save() }
    }

    fun clearBank() {
        for (i in 0 until PER_BANK) {
            val p = pads[bank * PER_BANK + i]
            NativeEngine.clearSample(bank * PER_BANK + i)
            p.file?.delete(); p.file = null; p.loaded = false; p.name = ""; p.uri = ""; p.playing = false
        }
        save()
    }

    // ------------------------------------------------------------------ playing
    fun down(slot: Int) {
        val p = pads[slot]
        if (!p.loaded) return
        if (AppSettings.padMode == 2) {
            if (p.playing) { NativeEngine.stopSample(slot); p.playing = false }
            else { NativeEngine.triggerSample(slot, true); p.playing = true }
        } else { NativeEngine.triggerSample(slot, false); p.playing = true }
    }

    fun up(slot: Int) {
        if (AppSettings.padMode == 1 && pads[slot].loaded) NativeEngine.stopSample(slot)
    }

    fun stopAll() { for (i in pads.indices) if (pads[i].loaded) { NativeEngine.stopSample(i); pads[i].playing = false } }

    fun poll() {
        for (i in pads.indices) {
            val p = pads[i]
            if (!p.loaded) continue
            val pl = NativeEngine.isSamplePlaying(i)
            if (p.playing != pl) p.playing = pl
        }
    }
}
