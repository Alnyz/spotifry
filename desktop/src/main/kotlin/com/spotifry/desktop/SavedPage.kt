package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.RestoreStatus
import com.spotifry.music.ui.saved.SavedFilter
import kotlinx.coroutines.launch
import com.spotifry.music.ui.saved.filterSaved

/** The chip picked on Saved; kept outside the page so it stays when you go to another page and back. */
internal var savedFilter by mutableStateOf(SavedFilter.All)

/** The phone's filters in the desktop's words. */
private val SavedFilter.desktopLabel get() = if (this == SavedFilter.Phone) "On this computer" else label
private val SavedFilter.desktopQueue get() = if (this == SavedFilter.Phone) "On this computer" else queueName

@Composable
fun SavedPage(services: Services, go: (Route) -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val saved by lib.db.songs().observeSaved().collectAsState(emptyList())
    val files by lib.musicFolder.songs.collectAsState()
    val recent by lib.db.history().observeRecent().collectAsState(emptyList())
    val state by services.player.state.collectAsState()
    val filter = savedFilter
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { lib.musicFolder.refresh() }
    val rows = remember(filter, saved, files, recent) { filterSaved(filter, saved, files, recent) }
    val songs = rows.map { it.song }
    LazyColumn(modifier) {
        item {
            Kicker("Your music")
            Text(androidx.compose.ui.text.buildAnnotatedString {
                append("Saved"); if (songs.isNotEmpty()) { append(", "); pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.accent, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)); append(songs.size.toString()); pop(); append(if (songs.size == 1) " song." else " songs.") }
            }, fontFamily = Heading, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1).sp, color = Palette.ink, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
            SavedFilters(Route.Saved, go)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedFilter.entries.forEach { f -> Chip(f.desktopLabel, f == filter) { savedFilter = f } }
            }
            if (songs.isNotEmpty()) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Play all", Icons.Play, primary = true) { services.player.playList(songs, 0, filter.desktopQueue) }
                Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(songs, filter.desktopQueue) }
            }
        }
        item { RestoreCard(services) }
        if (songs.isEmpty()) item {
            when (filter) {
                SavedFilter.Phone -> Column {
                    EmptyState("No music files here", "Spotifry looks for songs in ${lib.musicFolder.folder.value.path.replaceFirst(System.getProperty("user.home"), "~")} and every folder inside it.")
                    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) { Pill("Pick another folder") { scope.launch { FilePicker.folder("Pick your music folder", lib.musicFolder.folder.value)?.let { lib.musicFolder.choose(it); lib.musicFolder.refresh() } } } }
                }
                SavedFilter.Lately -> EmptyState("Nothing played lately", "Songs you play from Saved show up here.")
                else -> EmptyState("Nothing saved yet", "Open any song's menu and choose Save on this computer. It plays here without internet.")
            }
        }
        itemsIndexed(rows, key = { _, r -> r.song.id }) { i, r ->
            SongLine(services, i + 1, r.song, state.current?.id == r.song.id, saved = r.saved, onClick = { services.player.playList(songs, i, filter.desktopQueue) })
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

/** After a backup is restored: the saved songs whose files didn't come with it, and what to do about them. */
@Composable
private fun RestoreCard(services: Services) {
    val queue = services.library.restoreQueue
    val ids by queue.ids.collectAsState()
    val status by queue.status.collectAsState()
    if (ids.isEmpty()) return
    val n = ids.size
    val (title, text) = when (val s = status) {
        RestoreStatus.Running -> "Downloading your saved songs" to "$n left. You can keep listening."
        RestoreStatus.NeedsWifi -> "$n waiting for Wi-Fi" to "Connect to Wi-Fi, then try again."
        is RestoreStatus.Failed -> "${s.count} ${if (s.count == 1) "song" else "songs"} didn't download" to "Check your connection, then try again."
        RestoreStatus.Idle -> "$n ${if (n == 1) "song was" else "songs were"} saved in your backup" to "Their music files didn't come with the backup. Download them to play without internet."
    }
    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Palette.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(Icons.Download, null, Modifier.size(24.dp), tint = Palette.accent) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(text, color = Palette.ink2, fontSize = 14.sp)
        }
        if (status == RestoreStatus.Running) Pill("Stop") { queue.stop() }
        else {
            Pill("Forget them") { queue.forget() }
            Pill(if (n == 1) "Download 1 song" else "Download $n songs", primary = true) { queue.start(wifiOnly = false) }
        }
    }
}
