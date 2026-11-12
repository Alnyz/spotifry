package com.spotifry.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.PlaylistOrder
import com.spotifry.music.data.db.SongEntity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun PlaylistsPage(services: Services, go: (Route) -> Unit, modifier: Modifier = Modifier) {
    val db = services.library.db
    val lists by db.playlists().observePlaylists().collectAsState(emptyList())
    val covers by db.playlists().observeCoverRows().collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (creating) NameDialog("New playlist", "Create", "", { creating = false }) { name -> scope.launch { db.playlists().create(name) }; creating = false }
    Column(modifier) {
        Kicker("On this computer")
        PageHeader("Playlists", if (lists.isEmpty()) null else "${lists.size} ${if (lists.size == 1) "playlist" else "playlists"}") {
            Pill("Import a playlist", Icons.Download) { services.importer.open() }
            Pill("+ New playlist", primary = true) { creating = true }
        }
        SavedFilters(Route.Playlists, go)
        if (lists.isEmpty()) EmptyState("No playlists yet", "Press New playlist, then add songs from the menu next to any song.")
        else LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.padding(top = 24.dp), contentPadding = PaddingValues(bottom = LocalBottomInset.current), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items(lists, key = { it.id }) { p ->
                val art = covers.filter { it.playlistId == p.id }.take(4).map { it.song.thumbnailUrl }
                CoverCard(p.name, "${p.songCount} songs · ${p.savedCount} saved", { w -> Mosaic(art, w) }, onClick = { go(Route.Playlist(p.id)) })
            }
        }
    }
}

@Composable
fun PlaylistPage(services: Services, id: Long, go: (Route) -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val db = lib.db
    val scope = rememberCoroutineScope()
    val all by db.playlists().observePlaylists().collectAsState(null)
    val loaded = all ?: return
    val meta = loaded.firstOrNull { it.id == id }
    var order by remember(id) { mutableStateOf(lib.orders.get(id)) }
    val raw by remember(id, order) { if (order == PlaylistOrder.RECENT) db.playlists().observeSongsNewestFirst(id) else db.playlists().observeSongs(id) }.collectAsState(emptyList())
    val rows = remember(raw, order) { order.arrange(raw) }
    val songs = rows.map(SongEntity::toSong)
    val state by services.player.state.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (meta == null) { LaunchedEffect(Unit) { go(Route.Playlists) }; return }
    if (renaming) NameDialog("Rename playlist", "Rename", meta.name, { renaming = false }) { name -> scope.launch { db.playlists().rename(id, name) }; renaming = false }
    if (deleting) ConfirmDialog("Delete this playlist?", "The songs stay saved. Only the playlist goes.", "Delete", { deleting = false }) { scope.launch { db.playlists().delete(id) }; deleting = false; go(Route.Playlists) }

    LazyColumn(modifier) {
        item { Text("‹ Playlists", color = Palette.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { go(Route.Playlists) }) }
        item {
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                Mosaic(songs.take(4).map { it.thumbnailUrl }, 200.dp, radius = 18.dp)
                Column {
                    Text("Playlist", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(meta.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${meta.songCount} songs · ${meta.savedCount} saved on this computer", color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (songs.isNotEmpty()) {
                            Pill("Play all", Icons.Play, primary = true) { services.player.playList(songs, 0, meta.name) }
                            Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(songs, meta.name) }
                            if (meta.songCount > meta.savedCount) Pill("Save ${meta.songCount - meta.savedCount} songs", Icons.Download) {
                                scope.launch { songs.forEach { s -> if (db.songs().get(s.id)?.isSaved != true) launch { lib.downloads.save(s) } } }
                            }
                        }
                        if (songs.isNotEmpty()) Pill("Share") { services.sharing = ShareTarget.of(meta.name, songs) }
                        Pill("Rename") { renaming = true }
                        Pill("Delete") { deleting = true }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistOrder.entries.forEach { o -> Chip(o.label, o == order) { order = o; lib.orders.set(id, o) } }
            }
        }
        if (songs.isEmpty()) item { EmptyState("No songs yet", "Use the menu next to a song and choose Add to a playlist.") }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            SongLine(
                services, i + 1, s, state.current?.id == s.id, saved = rows[i].isSaved,
                extra = { close -> DropdownMenuItem({ Text("Remove from this playlist") }, onClick = { scope.launch { db.playlists().removeSong(id, s.id) }; close() }) },
                onClick = { services.player.playList(songs, i, meta.name) },
            )
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}
