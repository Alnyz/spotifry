package com.spotifry.desktop

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asSkiaBitmap
import sh.calvin.reorderable.ReorderableItem
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
                val art = covers.filter { it.playlistId == p.id }.take(4).map { it.song.thumbnailUrl ?: it.song.thumbPath }
                val custom = remember(p.cover) { services.library.playlistCovers.file(p.cover)?.path }
                CoverCard(p.name, "${p.songCount} songs · ${p.savedCount} saved", { w -> Mosaic(art, w, custom = custom) }, onClick = { go(Route.Playlist(p.id)) })
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
    // in Your order the songs can be dragged: the list moves here while dragging and is written when the song is let go
    var rows by remember(raw, order) { mutableStateOf(order.arrange(raw)) }
    val songs = rows.map(SongEntity::toSong)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
        val a = rows.indexOfFirst { it.id == from.key }; val b = rows.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) rows = rows.toMutableList().apply { add(b, removeAt(a)) }
    }
    val canDrag = order == PlaylistOrder.CUSTOM
    val state by services.player.state.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pencil by remember { mutableStateOf(false) }
    var coverDialog by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<org.jetbrains.skia.Image?>(null) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val coverRows by db.playlists().observeCoverRows().collectAsState(emptyList())
    val coverSongs = coverRows.filter { it.playlistId == id }.take(4).map { it.song.thumbnailUrl ?: it.song.thumbPath }
    val covers = lib.playlistCovers
    // the order before Mix up, so it can be put back
    var undoMix by remember(id) { mutableStateOf<List<String>?>(null) }
    if (meta == null) { LaunchedEffect(Unit) { go(Route.Playlists) }; return }
    if (renaming) NameDialog("Rename playlist", "Rename", meta.name, { renaming = false }) { name -> scope.launch { db.playlists().rename(id, name) }; renaming = false }
    if (deleting) ConfirmDialog("Delete this playlist?", "The songs stay saved. Only the playlist goes.", "Delete", { deleting = false }) {
        scope.launch { val cover = db.playlists().coverOf(id); db.playlists().delete(id); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { covers.delete(cover) } }
        deleting = false; go(Route.Playlists)
    }
    val customPath = remember(meta.cover) { covers.file(meta.cover)?.path }

    /** Saves the new cover (null is the default one) and offers Undo; the old picture is deleted once Undo is no longer possible. */
    fun changeCover(picture: org.jetbrains.skia.Image?, box: com.spotifry.music.data.CropBox?) = scope.launch {
        val old = db.playlists().coverOf(id)
        val name = if (picture != null && box != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { covers.save(id, picture, box) }.getOrNull() } else null
        if (picture != null && name == null) { snackbar.showSnackbar("Couldn't save that cover"); return@launch }
        db.playlists().setCover(id, name)
        var undone = false
        try {
            undone = snackbar.showSnackbar(if (name == null) "Back to the default cover" else "Cover changed", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { if (undone) { db.playlists().setCover(id, old); covers.delete(name) } else covers.delete(old) }
        }
    }
    if (coverDialog) ChangeCoverDialog(
        hasCustom = meta.cover != null, canPickSong = songs.isNotEmpty(),
        onPicture = {
            coverDialog = false
            scope.launch {
                val file = FilePicker.picture("Pick a cover picture") ?: return@launch
                val picture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()) }.getOrNull() }
                if (picture != null) cropping = picture else snackbar.showSnackbar("Couldn't open that picture")
            }
        },
        onSongs = { coverDialog = false; picking = true },
        onDefault = { coverDialog = false; changeCover(null, null) },
        onDismiss = { coverDialog = false },
    )
    if (picking) SongCoverDialog(rows, { s ->
        picking = false
        scope.launch {
            val art = (s.thumbnailUrl ?: s.thumbPath)?.let { Covers.load(com.spotifry.music.data.source.artAt(it, true)) }
            val picture = art?.let { org.jetbrains.skia.Image.makeFromBitmap(it.asSkiaBitmap()) }
            if (picture == null) snackbar.showSnackbar("Couldn't load that cover")
            else changeCover(picture, com.spotifry.music.data.cropBox(picture.width, picture.height, 1f, 0f, 0f))
        }
    }, { picking = false })
    cropping?.let { pic -> CropDialog(pic, { box -> cropping = null; changeCover(pic, box) }, { cropping = null }) }

    Box(modifier) {
    LazyColumn(Modifier.fillMaxSize(), listState) {
        item { Text("‹ Playlists", color = Palette.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { go(Route.Playlists) }) }
        item {
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                Mosaic(coverSongs, 200.dp, radius = 18.dp, custom = customPath)
                Column {
                    Text("Playlist", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(meta.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${meta.songCount} songs · ${meta.savedCount} saved on this computer", color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                    // the buttons wrap onto a second line on a narrow window instead of squeezing
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (songs.isNotEmpty()) {
                            Pill("Play all", Icons.Play, primary = true) { services.player.playList(songs, 0, meta.name) }
                            Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(songs, meta.name) }
                            if (meta.songCount > meta.savedCount) Pill("Save ${meta.songCount - meta.savedCount} songs", Icons.Download) {
                                scope.launch { songs.forEach { s -> if (db.songs().get(s.id)?.isSaved != true) launch { lib.downloads.save(s) } } }
                            }
                        }
                        if (songs.isNotEmpty()) Pill("Share") { services.sharing = ShareTarget.of(meta.name, songs) }
                        if (songs.size > 1) Pill("Mix up your order", Icons.Shuffle) {
                            scope.launch {
                                val old = db.playlists().observeSongs(id).first().map { it.id }
                                db.playlists().setOrder(id, old.shuffled()); order = PlaylistOrder.CUSTOM; lib.orders.set(id, PlaylistOrder.CUSTOM); undoMix = old
                            }
                        }
                        undoMix?.let { old -> Pill("Undo") { scope.launch { db.playlists().setOrder(id, old) }; undoMix = null } }
                        Box {
                            Pill("Edit", Icons.Edit) { pencil = true }
                            androidx.compose.material3.DropdownMenu(pencil, { pencil = false }, containerColor = Palette.surface) {
                                DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Edit, null, Modifier.size(20.dp)) }, onClick = { pencil = false; renaming = true })
                                DropdownMenuItem({ Text("Change cover") }, leadingIcon = { Icon(Icons.Picture, null, Modifier.size(20.dp)) }, onClick = { pencil = false; coverDialog = true })
                            }
                        }
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
        if (canDrag && songs.size > 1) item { Text("Drag a song by its handle to move it.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            ReorderableItem(reorder, key = s.id, enabled = canDrag) { dragging ->
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (dragging) Palette.surface else androidx.compose.ui.graphics.Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                    if (canDrag) Icon(
                        Icons.DragHandle, "Drag to move ${s.title}",
                        Modifier.size(30.dp).draggableHandle(onDragStopped = { scope.launch { db.playlists().setOrder(id, rows.map { it.id }) } }).padding(5.dp),
                        tint = Palette.ink2,
                    )
                    Box(Modifier.weight(1f)) {
                        SongLine(
                            services, i + 1, s, state.current?.id == s.id, saved = rows[i].isSaved,
                            extra = { close -> DropdownMenuItem({ Text("Remove from this playlist") }, onClick = { scope.launch { db.playlists().removeSong(id, s.id) }; close() }) },
                            onClick = { services.player.playList(songs, i, meta.name) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomInset.current + 8.dp))
    }
}
