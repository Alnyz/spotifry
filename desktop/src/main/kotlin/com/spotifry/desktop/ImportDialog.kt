package com.spotifry.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.ui.playlists.ImportState
import com.spotifry.music.ui.playlists.isPlaylistLink
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** Bring in a Spotify or YouTube playlist: the same steps and words as the phone, in a small window over the page. */
@Composable
fun ImportDialog(services: Services, onOpenPlaylist: (Long) -> Unit) {
    val importer = services.importer
    val state by importer.state.collectAsState()
    when (val s = state) {
        ImportState.Closed -> Unit
        is ImportState.Input -> InputStep(s.error, onStart = importer::start, onClose = importer::close)
        ImportState.Opening -> Shell("Bring a playlist", onDismiss = importer::cancel, confirm = {}) { Text("Opening the playlist…", color = Palette.ink2) }
        is ImportState.Running -> Shell(s.name, onDismiss = importer::cancel, confirm = {}, dismissText = "Cancel import") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${s.done} of ${s.total} songs" + (s.secondsLeft?.let { " · about ${if (it >= 60) "${it / 60} min" else "$it s"} left" } ?: ""), color = Palette.ink2, fontSize = 14.sp)
                LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, color = Palette.accent, trackColor = Palette.high, modifier = Modifier.fillMaxWidth())
                s.recent.forEach { (track, found) ->
                    Text("${when (found) { true -> "✓"; false -> "✗"; null -> "…" }}  ${track.title} · ${track.artist}", color = if (found == false) Palette.ink2 else Palette.ink, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        is ImportState.Done -> Shell(s.name, onDismiss = importer::close, dismissText = "Close", confirm = { TextButton({ onOpenPlaylist(s.playlistId); importer.close() }) { Text("Open the playlist", color = Palette.accent) } }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Added ${s.added} of ${s.total} songs.", fontWeight = FontWeight.SemiBold)
                if (s.missing.isNotEmpty()) {
                    Text("${s.missing.size} ${if (s.missing.size == 1) "song wasn't" else "songs weren't"} found on YouTube:", color = Palette.ink2, fontSize = 14.sp)
                    s.missing.take(8).forEach { Text("${it.title} · ${it.artist}", color = Palette.ink2, fontSize = 13.sp, maxLines = 1) }
                    if (s.missing.size > 8) Text("and ${s.missing.size - 8} more", color = Palette.ink2, fontSize = 13.sp)
                }
                s.note?.let { Text(it, color = Palette.ink2, fontSize = 14.sp) }
            }
        }
        is ImportState.Failed -> Shell("Couldn't bring the playlist", onDismiss = importer::close, dismissText = "Close", confirm = { TextButton(importer::retry) { Text("Try again", color = Palette.accent) } }) {
            Text(s.message, color = Palette.ink2)
        }
    }
}

@Composable
private fun InputStep(error: String?, onStart: (String) -> Unit, onClose: () -> Unit) {
    // a playlist link already copied is filled in, as on the phone
    val clip = remember { runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()?.trim()?.takeIf { isPlaylistLink(it) } }
    var link by remember { mutableStateOf(clip.orEmpty()) }
    Shell("Bring a playlist", onDismiss = onClose, confirm = { TextButton({ if (link.isNotBlank()) onStart(link.trim()) }) { Text("Open the playlist", color = Palette.accent) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Paste a link from Spotify or YouTube. Spotify songs are found on YouTube Music. Public playlists only, up to 100 songs.", color = Palette.ink2, fontSize = 14.sp)
            OutlinedTextField(
                link, { link = it }, singleLine = true, label = { Text("Spotify or YouTube link") }, shape = RoundedCornerShape(14.dp), isError = error != null,
                modifier = Modifier.fillMaxWidth().onEnter { if (link.isNotBlank()) onStart(link.trim()) },
            )
            if (error != null) Text(error, color = Palette.danger, fontSize = 13.sp)
            else if (clip != null && link == clip) Text("Link found on the clipboard, filled in for you.", color = Palette.accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Shell(title: String, onDismiss: () -> Unit, dismissText: String = "Cancel", confirm: @Composable () -> Unit, content: @Composable () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Palette.surface, title = { Text(title, fontFamily = Serif, fontSize = 22.sp, maxLines = 2) },
    text = { Box(Modifier.widthIn(min = 420.dp)) { content() } }, confirmButton = confirm,
    dismissButton = { TextButton(onDismiss) { Text(dismissText, color = Palette.ink2) } },
)
