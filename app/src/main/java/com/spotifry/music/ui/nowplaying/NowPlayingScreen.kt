package com.spotifry.music.ui.nowplaying

import com.spotifry.music.player.SleepState
import com.spotifry.music.player.clockText
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.components.coverTint
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.ui.theme.darkColors

sealed interface SaveUi {
    data object Unsaved : SaveUi
    data class Saving(val progress: Float) : SaveUi
    data object Saved : SaveUi
    data object Failed : SaveUi
}

internal val Faint = Color.White.copy(alpha = .72f)

/**
 * Layout: the background takes its colour from the cover. Where the music comes from at the top, then the big cover,
 * title with the save button beside it, the progress bar, the controls, and the queue button. Below the fold, the
 * songs coming up, whose top edge peeks in.
 */
@Composable
fun NowPlayingContent(
    state: PlayerUiState, save: SaveUi,
    onClose: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onSave: () -> Unit, onQueue: () -> Unit, onMore: () -> Unit,
    modifier: Modifier = Modifier,
    saved: Set<String> = emptySet(), onPick: (Int) -> Unit = {},
    lyrics: LyricsUi = LyricsUi.None, onRetryLyrics: () -> Unit = {},
    showLyrics: Boolean = false, onShowLyrics: (Boolean) -> Unit = {},
    position: () -> Long = { 0L },
    sleep: SleepState = SleepState.Off, sleepLeft: () -> Long? = { null }, onSleep: () -> Unit = {},
    /** "1.25× · +2" when speed or pitch isn't normal, else null. */
    speedLabel: String? = null, onSpeed: () -> Unit = {},
) {
    val song = state.current ?: return
    // a new song closes the full lyrics of the old one
    androidx.compose.runtime.LaunchedEffect(song.id) { onShowLyrics(false) }
    val client = LocalImageClient.current
    val files = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val tint by produceState<Color?>(null, song.id, client) { value = coverTint(client, song.thumbnailUrl, song.localThumbPath, files) }
    val top by animateColorAsState(tint ?: Color(0xFF2A2E35), tween(500), label = "cover colour")
    // the background is always dark: white status and gesture bar icons while shown, the theme's own back on leaving
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val nav = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (status != null) bars.isAppearanceLightStatusBars = status
            if (nav != null) bars.isAppearanceLightNavigationBars = nav
        }
    }
    // always dark: white text on the cover colour, in light mode too
    MaterialTheme(colorScheme = darkColors(com.spotifry.music.ui.theme.LocalAccent.current), typography = MaterialTheme.typography) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, lerp(top, Color.Black, .6f))))) {
        val screen = maxHeight
        // the cover keeps its size when a pill shows: the free space on the page takes up the pill instead
        val pills = sleep != SleepState.Off || speedLabel != null
        val cover = minOf(maxWidth - 48.dp, screen * 0.44f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // at least one screen tall (minus the peek of the card below), taller on small screens so nothing is cut off
            Column(
                Modifier.fillMaxWidth().heightIn(min = screen - 64.dp).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                TopBar(state.from, onClose, onMore)
                if (pills) Row(Modifier.fillMaxWidth().padding(top = 2.dp), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    if (speedLabel != null) InfoPill(Icons.Speed, speedLabel, "Change speed and pitch", onSpeed)
                    if (sleep != SleepState.Off) SleepPill(sleep, sleepLeft, onSleep)
                }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp), Alignment.Center) {
                    Cover(song.id, song.thumbnailUrl, song.localThumbPath, cover, radius = 12.dp)
                }
                }
                Column(Modifier.padding(top = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, Modifier.basicMarquee(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1)
                        Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // a file on the phone is already there: nothing to save
                    if (!song.isLocal) SaveButton(save, onSave)
                }
                ProgressBar(state, position, Modifier.padding(horizontal = 24.dp).padding(top = 14.dp), onSeek)
                Controls(state, onToggle, onNext, onPrev, onShuffle, onRepeat, Modifier.padding(horizontal = 14.dp).padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                }
            }
            LyricsCard(lyrics, position, state.isPlaying, lerp(top, Color.White, .14f), { onShowLyrics(true) }, onRetryLyrics)
            ComingUp(state, saved, lerp(top, Color.Black, .35f), onPick, onQueue)
        }
        val ready = (lyrics as? LyricsUi.Ready)?.lyrics
        // back is handled by the app shell, which closes these lyrics first and Now playing after
        if (showLyrics && ready != null) LyricsScreen(state, position, ready, lerp(top, Color.White, .14f), { onShowLyrics(false) }, onSeek, onToggle)
    }
    }
    }
}

@Composable
private fun TopBar(from: String?, onClose: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose, Modifier.size(48.dp)) { Icon(Icons.Down, "Close player") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (from != null) {
                Text("Playing from", style = MaterialTheme.typography.labelMedium, color = Faint)
                Text(from, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Now playing", style = MaterialTheme.typography.titleSmall)
            }
        }
        IconButton(onMore, Modifier.size(48.dp)) { Icon(Icons.More, "More options") }
    }
}

/** Download outline when not saved, a filling ring while saving, an orange check once saved, red when it failed. */
@Composable
private fun SaveButton(save: SaveUi, onSave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = saveLabel(save)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = save is SaveUi.Unsaved || save is SaveUi.Failed, onClick = onSave)
            .semantics { contentDescription = label },
        Alignment.Center,
    ) {
        when (save) {
            SaveUi.Unsaved -> Box(Modifier.size(30.dp).border(1.5.dp, Faint, CircleShape), Alignment.Center) { Icon(Icons.Download, null, Modifier.size(18.dp)) }
            is SaveUi.Saving -> CircularProgressIndicator(progress = { save.progress }, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = scheme.primary)
            SaveUi.Saved -> Box(Modifier.size(30.dp).clip(CircleShape).background(scheme.primary), Alignment.Center) { Icon(Icons.Check, null, Modifier.size(20.dp), tint = scheme.onPrimary) }
            SaveUi.Failed -> Box(Modifier.size(30.dp).border(1.5.dp, scheme.error, CircleShape), Alignment.Center) { Icon(Icons.Download, null, Modifier.size(18.dp), tint = scheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressBar(state: PlayerUiState, position: () -> Long, modifier: Modifier, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.durationSec ?: 0) * 1000)
    val fraction = drag ?: if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Slider(
            value = fraction, onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) },
            track = {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color.White))
                }
            },
            modifier = Modifier.height(28.dp).semantics { contentDescription = "Song position" },
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime((fraction * duration).toLong()), style = MaterialTheme.typography.bodySmall, color = Faint)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = Faint)
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState, onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, modifier: Modifier,
) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        ToggleIcon(Icons.Shuffle, "Shuffle", state.shuffle, onShuffle)
        IconButton(onPrev, Modifier.size(56.dp)) { Icon(Icons.Prev, "Previous song", Modifier.size(36.dp)) }
        Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White).clickable(onClick = onToggle), Alignment.Center) {
            Icon(if (state.isPlaying) Icons.Pause else Icons.Play, if (state.isPlaying) "Pause" else "Play", Modifier.size(34.dp), tint = Color(0xFF0C0D10))
        }
        IconButton(onNext, Modifier.size(56.dp)) { Icon(Icons.Next, "Next song", Modifier.size(36.dp)) }
        ToggleIcon(
            Icons.Repeat,
            when (state.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" },
            state.repeatMode != Player.REPEAT_MODE_OFF, onRepeat,
        )
    }
}

/** The card under the fold: the next songs, in the order they will play. "Open queue" opens the full list. */
@Composable
private fun ComingUp(state: PlayerUiState, saved: Set<String>, color: Color, onPick: (Int) -> Unit, onQueue: () -> Unit) {
    val next = state.upNextIndices().drop(1).take(5)
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Coming up", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(
                "Open queue", Modifier.clip(CircleShape).clickable(onClick = onQueue).padding(horizontal = 12.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge, color = Faint,
            )
        }
        if (next.isEmpty()) {
            Text("Similar songs are added when this one ends.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Faint)
        }
        next.forEach { i ->
            val s = state.queue[i]
            SongRow(s, s.id in saved, { onPick(i) }, null)
        }
    }
    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(16.dp))
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp), Alignment.Center) {
        IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, description, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

private fun saveLabel(save: SaveUi) = when (save) {
    SaveUi.Unsaved -> "Save on this phone"
    is SaveUi.Saving -> "Saved on this phone" // shown as saved at once; the file finishes in the background
    SaveUi.Saved -> "Saved on this phone"
    SaveUi.Failed -> "Save failed. Retry"
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** "Stops in 28:41" under the top bar while the sleep timer runs. Tapping it opens the timer sheet. */
@Composable
private fun SleepPill(sleep: SleepState, left: () -> Long?, onClick: () -> Unit) {
    val text by produceState("", sleep) {
        while (true) {
            value = if (sleep is SleepState.Minutes) "Stops in ${clockText(left() ?: 0)}" else "Stops after this song"
            kotlinx.coroutines.delay(1_000)
        }
    }
    InfoPill(Icons.Clock, text, "Change sleep timer", onClick)
}

@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, clickLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable(onClickLabel = clickLabel, onClick = onClick)
            .height(32.dp).padding(start = 10.dp, end = 14.dp),
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"))
    }
}
