package com.example.djapp

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** Browse every audio file on the device, with BPM + key analysed offline, sortable, loadable to either deck. */
@Composable
fun LibraryPanel(onLoad: (Int, Uri) -> Unit, onBrowse: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
               else android.Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // Videos need their own permission on Android 13+; ask once (people who allowed audio with an older version never saw it).
    val askVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scope.launch { Library.scan(ctx) } }
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        ) {
            val sp = ctx.getSharedPreferences("maxxidodj", android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("askedVideo", false)) { sp.edit().putBoolean("askedVideo", true).apply(); askVideo.launch(android.Manifest.permission.READ_MEDIA_VIDEO) }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !Library.addFolder(ctx, uri))
            android.widget.Toast.makeText(ctx, "Pick a folder inside the phone storage, not the storage root", android.widget.Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { Library.loadFolders(ctx) }

    // Refresh the file list every time the panel opens (picks up newly added tracks; analysis results are kept).
    LaunchedEffect(granted) { if (granted) Library.scan(ctx) }

    if (!granted) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Allow access to the audio files on this device to build your library. " +
                    "Tracks are read and analysed on the phone; nothing is uploaded.",
                color = TextGray, fontSize = 11.sp
            )
            PadButton("ALLOW ACCESS", Modifier.width(150.dp), active = true) { ask.launch(perm) }
        }
        return
    }

    val allTracks = Library.tracks
    val folder = Library.folders.getOrNull(Library.activeFolder)
    val all = remember(allTracks, folder) { if (folder == null) allTracks else allTracks.filter { Library.inFolder(it, folder) } }
    val analysedCount = remember(all) { all.count { it.analysed } }
    val rows = remember(all, Library.sortBy, Library.ascending) { Library.sorted(all, Library.sortBy, Library.ascending) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    Library.scanning && all.isEmpty() -> "Scanning…"
                    Library.analysing -> "${all.size} tracks · analysing BPM + key…  $analysedCount / ${all.size}"
                    else -> "${all.size} tracks · $analysedCount analysed"
                },
                color = TextGray, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f)
            )
            PadButton("FILE A", Modifier.width(64.dp).height(28.dp), fontSize = 10) { onBrowse(0) }
            Spacer(Modifier.width(4.dp))
            PadButton("FILE B", Modifier.width(64.dp).height(28.dp), fontSize = 10) { onBrowse(1) }
            Spacer(Modifier.width(6.dp))
            PadButton("RESCAN", Modifier.width(76.dp).height(28.dp), enabled = !Library.scanning, fontSize = 10) {
                scope.launch { Library.scan(ctx) }
            }
            Spacer(Modifier.width(6.dp))
            PadButton(
                if (Library.analysing) "STOP" else "ANALYSE", Modifier.width(90.dp).height(28.dp),
                active = Library.analysing, color = Accent, fontSize = 10,
                enabled = Library.analysing || (all.isNotEmpty() && analysedCount < all.size)
            ) { if (Library.analysing) Library.stopAnalysis() else Library.startAnalysis(ctx) { t -> Library.inFolder(t, folder) } }
        }

        // Folder chooser: show one folder (e.g. a single mix or video folder) instead of every file on the phone.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PadButton("+ FOLDER", Modifier.width(80.dp).height(26.dp), active = true, color = Accent, fontSize = 10) { pickFolder.launch(null) }
            Spacer(Modifier.width(6.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { PadButton("ALL", Modifier.width(44.dp).height(26.dp), active = folder == null, fontSize = 10) { Library.activeFolder = -1 } }
                items(Library.folders.size) { i ->
                    PadButton(
                        Library.folders[i].name, Modifier.widthIn(min = 56.dp, max = 140.dp).height(26.dp),
                        active = Library.activeFolder == i, fontSize = 10
                    ) { Library.activeFolder = i }
                }
            }
            if (folder != null) {
                Spacer(Modifier.width(6.dp))
                PadButton("REMOVE", Modifier.width(70.dp).height(26.dp), fontSize = 9) { Library.removeActiveFolder(ctx) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    PrepQueue.running && PrepQueue.paused -> "PREP paused while a deck plays · ${PrepQueue.current} · ${PrepQueue.queue.size} queued"
                    PrepQueue.running -> "PREP ${(PrepQueue.progress * 100).toInt()}% · ${PrepQueue.current} · ${PrepQueue.queue.size} queued"
                    else -> "P = prepare a track's stems ahead of time (instant load, no CPU while you play)"
                },
                color = if (PrepQueue.running) Accent else TextGray, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f)
            )
            PadButton("PREP ALL", Modifier.width(76.dp).height(24.dp), enabled = rows.isNotEmpty(), fontSize = 9) {
                rows.forEach { PrepQueue.add(ctx, it.uri, it.title) }
            }
            Spacer(Modifier.width(4.dp))
            PadButton("STOP PREP", Modifier.width(76.dp).height(24.dp), enabled = PrepQueue.running, fontSize = 9) { PrepQueue.stop() }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in SortBy.values()) {
                val on = Library.sortBy == s
                PadButton(
                    s.label + if (on) (if (Library.ascending) " ▲" else " ▼") else "",
                    Modifier.weight(1f).height(26.dp), active = on, fontSize = 10
                ) {
                    if (on) Library.ascending = !Library.ascending
                    else { Library.sortBy = s; Library.ascending = true }
                }
            }
        }

        if (rows.isEmpty() && !Library.scanning) {
            Text(if (folder == null) "No audio files found on this device." else "No audio or video found in this folder.", color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                items(rows, key = { it.id }) { t -> TrackRow(t, onLoad, ctx) }
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, onLoad: (Int, Uri) -> Unit, ctx: android.content.Context) {
    val pending = if (t.analysed) "–" else "···"
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text((if (t.isVideo) "▶ " else "") + t.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.artist.isNotEmpty())
                Text(t.artist, color = TextGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (t.bpm > 0f) "%.1f".format(t.bpm) else pending,
            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(54.dp)
        )
        Text(
            if (t.camelot.isNotEmpty()) "${t.camelot} ${t.key}" else pending,
            color = Accent, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(64.dp)
        )
        Text(
            "%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60),
            color = TextGray, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp)
        )
        val prepared = Prepared.has(t.uri)
        val queued = PrepQueue.queue.any { it.uri == t.uri }
        PadButton(
            if (prepared) "●" else if (queued) "…" else "P", Modifier.width(30.dp).height(30.dp),
            active = prepared, color = Accent, enabled = !prepared && !queued
        ) { PrepQueue.add(ctx, t.uri, t.title) }
        Spacer(Modifier.width(4.dp))
        PadButton("A", Modifier.width(34.dp).height(30.dp), active = true, color = DeckALight) { onLoad(0, t.uri) }
        Spacer(Modifier.width(4.dp))
        PadButton("B", Modifier.width(34.dp).height(30.dp), active = true, color = DeckB) { onLoad(1, t.uri) }
    }
}
