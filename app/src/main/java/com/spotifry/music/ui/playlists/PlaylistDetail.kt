package com.spotifry.music.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.PlaylistOrder
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.matches
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.Pill
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.components.countsLabel
import com.spotifry.music.ui.home.SearchField
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(vm: PlaylistsViewModel, id: Long, onBack: () -> Unit, onMore: (Song, Long) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier, onShare: (String, List<Song>) -> Unit = { _, _ -> }) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    val playlist = all.firstOrNull { it.id == id }
    var order by remember(id) { mutableStateOf(vm.orderOf(id)) }
    // keyed on the order so a new choice swaps the list at once
    val songs by remember(id, order) { vm.songsOf(id, order) }.collectAsStateWithLifecycle(null)
    var query by remember(id) { mutableStateOf("") }
    var editing by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val name = playlist?.name.orEmpty()

    Box(modifier.fillMaxSize()) {
        val list = songs
        if (editing && list != null) {
            // always edits your own order, whatever order is showing
            val own by remember(id) { vm.songsOf(id) }.collectAsStateWithLifecycle(null)
            own?.let { OrderEditor(it, bottomInset) { ids -> vm.saveOrder(id, ids); order = PlaylistOrder.CUSTOM; editing = false } }
        } else PlaylistDetailContent(
            name, list.orEmpty(), query, { query = it }, order,
            onOrder = { vm.setOrder(id, it); order = it },
            onEditOrder = { query = ""; editing = true },
            onMixUp = {
                scope.launch {
                    val old = vm.mixUp(id)
                    order = PlaylistOrder.CUSTOM
                    if (snackbar.showSnackbar("Order mixed up", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.saveOrder(id, old)
                }
            },
            onPlay = { l, i -> vm.play(l, i, name) }, onMore = { onMore(it, id) }, onMenu = { menu = true }, onBack = onBack,
            bottomInset = bottomInset, subtitle = playlist?.let(::countsLabel), loading = list == null,
        ) {
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text("Share") }, enabled = !list.isNullOrEmpty(), onClick = { menu = false; onShare(name, list.orEmpty().map { it.toSong() }) })
                DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; deleting = true })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp))
    }
    if (renaming) NameDialog("Rename playlist", "Save", name, { renaming = false }) { vm.rename(id, it); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this playlist?") },
        text = { Text("The songs stay on the phone. Only the playlist goes.") },
        confirmButton = { TextButton({ vm.delete(id); deleting = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep it") } },
    )
}

@Composable
fun PlaylistDetailContent(
    name: String, songs: List<SongEntity>, query: String, onQuery: (String) -> Unit,
    order: PlaylistOrder, onOrder: (PlaylistOrder) -> Unit, onEditOrder: () -> Unit, onMixUp: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit, onMore: (Song) -> Unit, onMenu: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, subtitle: String? = null, loading: Boolean = false, menuHost: @Composable () -> Unit = {},
) {
    val all = remember(songs) { songs.map { it.toSong() } }
    val searching = query.isNotBlank()
    val shown = remember(songs, query) { if (searching) songs.indices.filter { songs[it].matches(query) } else songs.indices.toList() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item {
            ScreenHeader(name, subtitle = subtitle, onBack = onBack, trailing = {
                Box { IconButton(onMenu, Modifier.size(48.dp)) { Icon(Icons.More, "Playlist options") }; menuHost() }
            })
            if (songs.isNotEmpty() && !searching) PlayButtons({ onPlay(all, 0) }, { onPlay(all.shuffled(), 0) }, Modifier.padding(start = 20.dp, top = 16.dp))
            if (songs.size > 1 || searching) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                SearchField(query, onQuery, {}, Modifier.weight(1f), hint = "Search in $name")
                if (!searching) OrderButton(order, onOrder, onEditOrder, onMixUp)
            }
            if (searching) Text(
                "${shown.size} of ${songs.size} songs", Modifier.padding(start = 20.dp, top = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (songs.isEmpty() && !loading) item { EmptyState("Nothing here yet", "Use the three dots next to any song to add one.") }
        // a found song plays from its place in the whole playlist, so the rest follows after it
        items(shown.size, key = { songs[shown[it]].id }) { k ->
            val i = shown[k]
            val s = songs[i]
            SongRow(all[i], s.isSaved, { onPlay(all, i) }, { onMore(all[i]) }, Modifier.padding(start = 20.dp, end = 8.dp), localCover = s.thumbPath, highlight = query.takeIf { searching })
        }
    }
}

@Composable
private fun OrderButton(order: PlaylistOrder, onOrder: (PlaylistOrder) -> Unit, onEdit: () -> Unit, onMixUp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Pill(Icons.Sort, order.label, scheme.surface, scheme.onSurface, { open = true })
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp)) {
            PlaylistOrder.entries.forEach { o ->
                val on = o == order
                DropdownMenuItem(
                    text = { Text(o.label, color = if (on) scheme.primary else scheme.onSurface, fontWeight = if (on) FontWeight.SemiBold else null) },
                    leadingIcon = { if (on) Icon(Icons.Check, null, tint = scheme.primary) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; onOrder(o) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
            DropdownMenuItem(text = { Text("Change your order") }, leadingIcon = { Icon(Icons.Reorder, null) }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Mix up your order") }, leadingIcon = { Icon(Icons.Shuffle, null) }, onClick = { open = false; onMixUp() })
        }
    }
}

/** Hold a song (or its handle) and drag it; the list scrolls by itself near the edges. Done or back saves. */
@Composable
private fun OrderEditor(songs: List<SongEntity>, bottomInset: Dp, onDone: (List<String>) -> Unit) {
    var list by remember(songs) { mutableStateOf(songs) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = list.indexOfFirst { it.id == from.key }
        val b = list.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) list = list.toMutableList().apply { add(b, removeAt(a)) }
    }
    BackHandler { onDone(list.map { it.id }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Change order", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Button({ onDone(list.map { it.id }) }) { Text("Done") }
        }
        Text(
            "Hold a song and drag it up or down.", Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f), listState, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = bottomInset + 16.dp)) {
            itemsIndexed(list, key = { _, s -> s.id }) { _, s ->
                ReorderableItem(reorder, key = s.id) { dragging ->
                    val lift by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "lift")
                    Surface(
                        Modifier.longPressDraggableHandle(), shape = RoundedCornerShape(14.dp), shadowElevation = lift,
                        color = if (dragging) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                    ) {
                        Row(Modifier.fillMaxWidth().height(70.dp).padding(start = 10.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                            Cover(s.id, s.thumbnailUrl, s.thumbPath, 52.dp, radius = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({}, Modifier.draggableHandle().size(48.dp)) { Icon(Icons.DragHandle, "Drag to move ${s.title}") }
                        }
                    }
                }
            }
        }
    }
}
