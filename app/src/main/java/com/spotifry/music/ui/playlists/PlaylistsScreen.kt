package com.spotifry.music.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.countsLabel
import com.spotifry.music.ui.components.PlaylistTile
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.Pill
import com.spotifry.music.ui.components.SongRow

@Composable
fun PlaylistsScreen(vm: PlaylistsViewModel, importVm: ImportViewModel, onOpen: (Long) -> Unit, onSearch: (String) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier, onShared: (com.spotifry.music.data.ShareText.Shared) -> Unit = {}) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val covers by vm.covers.collectAsStateWithLifecycle()
    val importing by importVm.state.collectAsStateWithLifecycle()
    PlaylistsContent(playlists, onOpen, { creating = true }, modifier, bottomInset, covers, onImport = importVm::open)
    ImportSheet(
        importing, (importing as? ImportState.Done)?.let { covers[it.playlistId] }.orEmpty(), importVm::start,
        onOpen = { importVm.close(); onOpen(it) }, onSearch = { importVm.close(); onSearch("${it.title} ${it.artist}") },
        onRetry = importVm::retry, onClose = importVm::close, onCancel = importVm::cancel,
        onShared = { importVm.close(); onShared(it) },
    )
    if (creating) NameDialog("New playlist", "Create", "", { creating = false }) { vm.create(it); creating = false }
}

@Composable
fun PlaylistsContent(playlists: List<PlaylistWithCounts>, onOpen: (Long) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, covers: Map<Long, List<SongEntity>> = emptyMap(), onImport: () -> Unit = {}) {
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    ScreenHeader("Playlists", Modifier.offset(x = (-20).dp), subtitle = if (playlists.isEmpty()) null else "${playlists.size} ${if (playlists.size == 1) "playlist" else "playlists"}")
                    Pill(Icons.Download, "Import from Spotify", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, onImport, Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
            }
            // the floating button is the one way to make a playlist, so the empty state does not repeat it
            if (playlists.isEmpty()) item(span = { GridItemSpan(2) }) { EmptyState("No playlists yet", "Tap New playlist, then add songs from the three dots next to any song.") }
            items(playlists, key = { it.id }) { PlaylistTile(it, covers[it.id].orEmpty(), { onOpen(it.id) }) }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = (bottomInset - 28.dp).coerceAtLeast(16.dp)),
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            icon = { Icon(Icons.Plus, null) }, text = { Text("New playlist") },
        )
    }
}

@Composable
fun NameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(16.dp)) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
