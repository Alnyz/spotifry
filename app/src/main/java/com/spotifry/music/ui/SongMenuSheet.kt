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
import com.spotifry.music.data.speedPitchLabel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class MenuAction(val label: String, val icon: ImageVector? = null, val lineAbove: Boolean = false, val onClick: () -> Unit)

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
            Text(song.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.padding(horizontal = 24.dp, vertical = 2.dp), Arrangement.spacedBy(5.dp), Alignment.CenterVertically) {
                if (song.suggested) Icon(Icons.Sparkle, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (song.suggested) "${song.artist} · suggested for you" else song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            actions.forEach { a ->
                if (a.lineAbove) HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(a.label, a.icon) { a.onClick(); onDismiss() }
            }
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
fun QueueSheet(
    state: PlayerUiState, saved: Set<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit, onSuggestionMore: (Int) -> Unit = {},
    onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int, String) -> Unit = { _, _ -> }, onUndo: (Int, Song, List<Int>?) -> Unit = { _, _, _ -> },
) {
    // fully open: half open, the bottom of the list (and its padding) sits off screen, under the gesture bar
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // the sheet covers the app's own snackbar, so it has its own
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Box {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text("Queue", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
                QueueList(state, saved, onSuggestionMore, onMove, onRemove = { i ->
                    val s = state.queue[i]
                    // with shuffle on, Undo also needs what was still to come, to put the song back in its place
                    val upcoming = state.order?.drop(1)
                    onRemove(i, s.id)
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch {
                        if (snackbar.showSnackbar("Removed from queue", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onUndo(i, s, upcoming)
                    }
                }) { onPick(it); onDismiss() }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/**
 * Scrolls, because the queue grows by about 20 similar songs every time it runs out. With [onMove] and [onRemove] the
 * songs after the playing one can be dragged by their handle and swiped away; the playing song stays on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueList(
    state: PlayerUiState, saved: Set<String> = emptySet(), onSuggestionMore: (Int) -> Unit = {},
    onMove: ((Int, Int) -> Unit)? = null, onRemove: ((Int) -> Unit)? = null, onPick: (Int) -> Unit,
) {
    // follows the finger while dragging; the player's next change replaces it with what it will really play
    var list by remember(state.queue, state.order, state.currentIndex) { mutableStateOf(state.upNextIndices()) }
    var dragFrom by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index > 0 && to.index > 0) list = list.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LazyColumn(Modifier.testTag("queue_list"), listState) {
        // the queue position is in the key: the same song can be queued twice
        items(list, key = { "${state.queue[it].id}/$it" }) { i ->
            val s = state.queue[i]
            val playing = i == state.currentIndex
            ReorderableItem(reorder, "${s.id}/$i", enabled = !playing && onMove != null) { dragging ->
                val swipe = rememberSwipeToDismissBoxState()
                LaunchedEffect(swipe.currentValue) {
                    if (swipe.currentValue == SwipeToDismissBoxValue.Settled) return@LaunchedEffect
                    list = list - i
                    onRemove?.invoke(i)
                    // back to rest: the list keeps this state and would show the row swiped away if it comes back with Undo
                    swipe.snapTo(SwipeToDismissBoxValue.Settled)
                }
                SwipeToDismissBox(
                    swipe, backgroundContent = { RemoveBackground(swipe.dismissDirection) },
                    gesturesEnabled = !playing && onRemove != null && !dragging,
                ) {
                    val lift by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "lift")
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), shadowElevation = lift, color = MaterialTheme.colorScheme.surface) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongRow(
                                s, s.id in saved, { onPick(i) }, if (s.suggested) { { onSuggestionMore(i) } } else null, Modifier.weight(1f),
                                subtitleExtra = if (playing) "Playing" else null,
                                titleColor = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                            if (onMove != null && !playing) IconButton(
                                {},
                                Modifier.draggableHandle(
                                    onDragStarted = { dragFrom = list.indexOf(i) },
                                    onDragStopped = { list.indexOf(i).let { to -> if (dragFrom > 0 && to > 0 && to != dragFrom) onMove(dragFrom, to) } },
                                ).size(48.dp),
                            ) { Icon(Icons.DragHandle, "Drag to move ${s.title}") }
                        }
                    }
                }
            }
        }
    }
}

/** Shown under a row while it is swiped away. */
@Composable
private fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
        if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) { Icon(Icons.Trash, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer) }
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

/** Speed (half to double) and pitch (6 steps either way), heard at once. Kept until "Back to normal". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPitchSheet(speed: Float, pitch: Int, onSpeed: (Float) -> Unit, onPitch: (Int) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismiss, containerColor = scheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Speed and pitch", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), Arrangement.SpaceBetween) {
                Text("Speed", style = MaterialTheme.typography.bodyLarge)
                Text(speedPitchLabel(speed, 0) ?: "1×", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            }
            // snaps to steps of 0.05 between 0.5 and 2, without a dot drawn for each step
            androidx.compose.material3.Slider(speed, { onSpeed((it * 20).roundToInt() / 20f) }, valueRange = .5f..2f, modifier = Modifier.semantics { contentDescription = "Speed" })
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f, 1.5f).forEach { x ->
                    val on = kotlin.math.abs(speed - x) < .001f
                    androidx.compose.material3.FilterChip(
                        on, { onSpeed(x) }, { Text(speedPitchLabel(x, 0) ?: "1×", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        Modifier.weight(1f), shape = CircleShape,
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = scheme.primary, selectedLabelColor = scheme.onPrimary),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), Arrangement.SpaceBetween) {
                Text("Pitch", style = MaterialTheme.typography.bodyLarge)
                Text(speedPitchLabel(1f, pitch).orEmpty(), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedIconButton({ onPitch(pitch - 1) }, enabled = pitch > -6, modifier = Modifier.size(48.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    when { pitch == 0 -> "Normal"; pitch > 0 -> "$pitch ${if (pitch == 1) "step" else "steps"} higher"; else -> "${-pitch} ${if (pitch == -1) "step" else "steps"} lower" },
                    Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                )
                androidx.compose.material3.OutlinedIconButton({ onPitch(pitch + 1) }, enabled = pitch < 6, modifier = Modifier.size(48.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            androidx.compose.material3.OutlinedButton(
                { onSpeed(1f); onPitch(0) }, Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp), enabled = speed != 1f || pitch != 0,
            ) { Text("Back to normal") }
        }
    }
}
