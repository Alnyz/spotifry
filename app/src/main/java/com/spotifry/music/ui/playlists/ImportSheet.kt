package com.spotifry.music.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.SpotifyTrack
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.CoverMosaic
import com.spotifry.music.ui.components.Pill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    state: ImportState, covers: List<SongEntity>, onImport: (String) -> Unit, onOpen: (Long) -> Unit,
    onSearch: (SpotifyTrack) -> Unit, onRetry: () -> Unit, onClose: () -> Unit, onCancel: () -> Unit = onClose,
    onShared: (com.spotifry.music.data.ShareText.Shared) -> Unit = {},
) {
    if (state == ImportState.Closed) return
    ModalBottomSheet(
        onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            when (state) {
                is ImportState.Input -> LinkStep(state.error, onImport, onShared)
                ImportState.Opening -> { Opening(); CancelButton(onCancel) }
                is ImportState.Running -> { Running(state); CancelButton(onCancel) }
                is ImportState.Done -> Done(state, covers, onOpen, onSearch, onClose)
                is ImportState.Failed -> Failed(state.message, onRetry, onClose)
                ImportState.Closed -> Unit
            }
        }
    }
}

@Composable
internal fun Title(text: String, sub: String?) {
    Text(text, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (sub != null) Text(sub, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LinkStep(error: String?, onImport: (String) -> Unit, onShared: (com.spotifry.music.data.ShareText.Shared) -> Unit) {
    val clip = LocalClipboardManager.current
    // a song list a friend sent from Spotifry, copied from the chat: offered as is, no Spotify needed
    val shared = remember { clip.getText()?.text?.let(com.spotifry.music.data.ShareText::read) }
    // a playlist link already copied is filled in, so the usual case is one tap
    var link by remember { mutableStateOf(clip.getText()?.text?.takeIf(::isPlaylistLink).orEmpty()) }
    // the error belongs to the link that caused it; typing clears it
    var shownFor by remember(error) { mutableStateOf(link) }
    val err = error?.takeIf { link == shownFor }
    val keyboard = LocalSoftwareKeyboardController.current
    Title("Import a playlist", "Paste a Spotify or YouTube playlist link: Share, then Copy link.")
    OutlinedTextField(
        link, { link = it }, Modifier.fillMaxWidth().padding(top = 18.dp), singleLine = true,
        placeholder = { Text("Spotify or YouTube link") }, leadingIcon = { Icon(Icons.Link, null) },
        isError = err != null, shape = RoundedCornerShape(16.dp),
        supportingText = { Text(err ?: "Public playlists only.") },
    )
    val scheme = MaterialTheme.colorScheme
    Pill(null, "Import", scheme.primary, scheme.onPrimary, { keyboard?.hide(); onImport(link) }, Modifier.fillMaxWidth().padding(top = 12.dp))
    if (shared != null) Pill(
        Icons.List, "Open the copied song list: ${shared.title}", scheme.surfaceContainerHigh, scheme.onSurface,
        { onShared(shared) }, Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun Opening() {
    Title("Import a playlist", "Opening the playlist…")
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 20.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
internal fun Running(s: ImportState.Running) {
    Title(s.name, "Finding each song on YouTube")
    LinearProgressIndicator(
        { s.done / s.total.toFloat() }, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp).height(6.dp),
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
    )
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Small("${s.done} of ${s.total}")
        s.secondsLeft?.let { Small(if (it < 60) "Under a minute left" else "About ${(it + 30) / 60} ${if (it < 90) "minute" else "minutes"} left") }
    }
    Spacer(Modifier.height(8.dp))
    // room for five rows from the start, so the sheet does not grow as songs come in
    Column(Modifier.height(52.dp * 5)) { s.recent.forEach { (t, found) -> TrackRow(t, found, if (found == false) "not found" else null) } }
}

@Composable
private fun Done(s: ImportState.Done, covers: List<SongEntity>, onOpen: (Long) -> Unit, onSearch: (SpotifyTrack) -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CoverMosaic(covers, Modifier.size(64.dp))
        Column { Title(s.name, "${s.added} of ${s.total} songs added") }
    }
    s.note?.let { Small(it, Modifier.padding(top = 12.dp)) }
    Row(Modifier.padding(top = 18.dp), Arrangement.spacedBy(10.dp)) {
        Pill(null, "Open playlist", scheme.primary, scheme.onPrimary, { onOpen(s.playlistId) }, Modifier.weight(1f))
        Pill(null, "Done", scheme.surfaceContainerHigh, scheme.onSurface, onClose, Modifier.weight(1f))
    }
    if (s.missing.isNotEmpty()) {
        Text("${s.missing.size} not found", Modifier.padding(top = 22.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
        s.missing.forEach { t -> TrackRow(t, false, null) { TextButton({ onSearch(t) }) { Text("Search") } } }
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Title("Import a playlist", message)
    Row(Modifier.padding(top = 18.dp), Arrangement.spacedBy(10.dp)) {
        Pill(null, "Try again", scheme.primary, scheme.onPrimary, onRetry, Modifier.weight(1f))
        Pill(null, "Close", scheme.surfaceContainerHigh, scheme.onSurface, onClose, Modifier.weight(1f))
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Pill(null, "Cancel import", scheme.surfaceContainerHigh, scheme.error, onCancel, Modifier.fillMaxWidth().padding(top = 16.dp))
}

@Composable
private fun TrackRow(t: SpotifyTrack, found: Boolean?, note: String?, trailing: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        when (found) {
            null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            true -> Icon(Icons.Check, "Found", Modifier.size(18.dp), tint = scheme.primary)
            false -> Icon(Icons.Close, "Not found", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.artist.ifBlank { null }, note).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
