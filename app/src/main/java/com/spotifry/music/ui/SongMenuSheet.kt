package com.spotifry.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.components.SongRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.font.FontWeight
import com.spotifry.music.player.SleepState
import com.spotifry.music.player.clockText
import kotlinx.coroutines.delay

data class MenuAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

@Composable
private fun ActionRow(label: String, icon: ImageVector? = null, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        Arrangement.spacedBy(16.dp), Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = color.takeOrElse { LocalContentColor.current }) else Spacer(Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (color.isSpecified) FontWeight.SemiBold else null)
    }
}

/** The three-dot menu for any song row. [actions] are the choices that depend on where the row lives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(song: Song, actions: List<MenuAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(song.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(song.artist, Modifier.padding(horizontal = 24.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            actions.forEach { a -> ActionRow(a.label, a.icon) { a.onClick(); onDismiss() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(playlists: List<PlaylistWithCounts>, onPick: (Long) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("Add to playlist", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            playlists.forEach { p -> ActionRow(p.name, Icons.List) { onPick(p.id); onDismiss() } }
            ActionRow("New playlist", Icons.Plus) { onNew(); onDismiss() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: PlayerUiState, saved: Set<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // fully open: half open, the bottom of the list (and its padding) sits off screen, under the gesture bar
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Queue", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            QueueList(state, saved) { onPick(it); onDismiss() }
        }
    }
}

/** Scrolls, because the queue grows by about 20 similar songs every time it runs out. */
@Composable
fun QueueList(state: PlayerUiState, saved: Set<String> = emptySet(), onPick: (Int) -> Unit) {
    LazyColumn(Modifier.testTag("queue_list")) {
        items(state.upNextIndices()) { i ->
            val s = state.queue[i]
            val playing = i == state.currentIndex
            SongRow(
                s, s.id in saved, { onPick(i) }, null, subtitleExtra = if (playing) "Playing" else null,
                titleColor = if (playing) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
    }
}

/** Sleep timer choices, from the Now playing menu or its countdown pill. Each choice starts over from now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(state: SleepState, left: () -> Long?, onMinutes: (Int) -> Unit, onEndOfSong: () -> Unit, onOff: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Sleep timer", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            val note by produceState("", state) {
                while (true) {
                    value = when (state) {
                        SleepState.Off -> "Music fades out and stops. The app keeps your place, so it starts again from there."
                        is SleepState.Minutes -> "Stops in ${clockText(left() ?: 0)}. Pick a new time to start over."
                        SleepState.EndOfSong -> "Stops after this song. Pick a new time to start over."
                    }
                    delay(1_000)
                }
            }
            Text(note, Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state != SleepState.Off) {
                ActionRow("Turn off timer", Icons.Close, MaterialTheme.colorScheme.error) { onOff(); onDismiss() }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            val picked = MaterialTheme.colorScheme.primary
            listOf(15, 30, 45, 60).forEach { m ->
                val on = (state as? SleepState.Minutes)?.minutes == m
                ActionRow(if (m == 60) "1 hour" else "$m minutes", if (on) Icons.Check else Icons.Clock, if (on) picked else Color.Unspecified) { onMinutes(m); onDismiss() }
            }
            val end = state == SleepState.EndOfSong
            ActionRow("End of this song", if (end) Icons.Check else Icons.Clock, if (end) picked else Color.Unspecified) { onEndOfSong(); onDismiss() }
        }
    }
}
