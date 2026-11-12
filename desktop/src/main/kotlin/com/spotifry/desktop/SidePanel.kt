package com.spotifry.desktop

import com.spotifry.music.data.source.isLocal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.Lyrics
import kotlinx.coroutines.launch
import com.spotifry.music.data.indexAt
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode

/** What plays, on the right: cover, name, a few lines of lyrics and what comes next. The controls are in the bar below. */
@Composable
fun SidePanel(services: Services, state: PlayerUiState, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val player = services.player
    val song = state.current
    Column(modifier.width(width).fillMaxHeight().background(if (Palette.dark) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.45f)).padding(top = 30.dp, bottom = 112.dp, start = if (width < 380.dp) 24.dp else 32.dp, end = if (width < 380.dp) 24.dp else 32.dp)) {
        if (song == null) {
            Spacer(Modifier.weight(1f))
            Text("Nothing playing", fontFamily = Display, fontSize = 24.sp, color = Palette.ink)
            Text("Pick a song and it shows up here.", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            return@Column
        }
        // the cover gives way when the window is short, so the controls and what is up next always fit
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = (height - 340.dp).coerceIn(150.dp, maxWidth)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(song.thumbnailUrl ?: song.localThumbPath, side, RoundedCornerShape(12.dp), large = true) }
        }
        Text(song.title, fontFamily = Display, fontSize = 26.sp, lineHeight = 28.sp, maxLines = 1, color = Palette.ink, modifier = Modifier.padding(top = 20.dp))
        Text(song.artist, color = Palette.accent, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        state.quality?.let { QualityPill(it, Modifier.padding(top = 12.dp)) }
        MiniLyrics(services, song, Modifier.padding(top = 12.dp).fillMaxWidth().weight(1f))
        UpNext(services, state, Modifier.padding(bottom = 16.dp))
    }
}

/** The line before, the line being sung and the line after. */
@Composable
private fun MiniLyrics(services: Services, song: com.spotifry.music.data.source.Song, modifier: Modifier) {
    val lyrics = lyricsFor(services, song)
    val position by services.player.position.collectAsState()
    val shift by services.library.settings.lyricsShift.collectAsState()
    val timed = ((lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Timed)?.lines
    BoxWithConstraints(modifier) {
        // three lines when the panel is short, five when there is room
        val reach = if (maxHeight > 190.dp) 2 else 1
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            if (timed == null) {
                Text(when (lyrics) { LyricsState.Loading -> "Looking for lyrics…"; is LyricsState.Found -> "Lyrics without timing"; else -> "No lyrics for this song" }, fontFamily = Display, fontSize = 15.sp, color = Palette.ink.copy(alpha = 0.4f))
                return@Column
            }
            val i = timed.indexAt(position + (shift[song.id] ?: 0))
            fun line(k: Int) = timed.getOrNull(k)?.text?.ifBlank { "♪" } ?: ""
            for (k in -reach..reach) {
                if (k == 0) Text(line(i), fontFamily = Display, fontSize = 19.sp, color = Palette.ink, maxLines = 3, modifier = Modifier.padding(vertical = 4.dp))
                else Text(line(i + k), fontFamily = Display, fontSize = 15.sp, color = Palette.ink.copy(alpha = if (k == -1 || k == 1) 0.45f else 0.25f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun UpNext(services: Services, state: PlayerUiState, modifier: Modifier) {
    val upcoming = state.upNextIndices().filter { it != state.currentIndex }.take(2)
    Column(modifier) {
        Kicker("Up next", Modifier.padding(bottom = 4.dp))
        if (upcoming.isEmpty()) Text("Nothing after this song.", color = Palette.ink2, fontSize = 13.sp)
        upcoming.forEach { i ->
            val s = state.queue.getOrNull(i) ?: return@forEach
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { services.player.skipTo(i) }.padding(vertical = 5.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(s.thumbnailUrl ?: s.localThumbPath, 32.dp, RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontFamily = Display, fontSize = 14.sp, maxLines = 1, color = Palette.ink)
                    Artist(s, 11.5.sp)
                }
            }
        }
    }
}

/** One round icon button in the bar, with its name shown on hover. */
@Composable
private fun BarIcon(icon: ImageVector, name: String, tint: Color, pad: Dp = 6.dp, onClick: () -> Unit) =
    Tip(name) { Icon(icon, name, Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick).padding(pad), tint = tint) }

/** The player along the bottom: song on the left, controls and seek in the middle, save, more, queue and volume on the right. */
@Composable
fun PlayerBar(services: Services, state: PlayerUiState, haze: dev.chrisbanes.haze.HazeState, queueOpen: Boolean, onQueue: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val player = services.player
    val song = state.current ?: return
    Row(
        modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(18.dp))
            // real glass: what scrolls behind the bar is blurred, then tinted
            .hazeEffect(haze) {
                style = HazeStyle(backgroundColor = Palette.bg, tint = HazeTint((if (Palette.dark) Color.Black else Color.White).copy(alpha = if (Palette.dark) 0.4f else 0.5f)), blurRadius = 28.dp)
            }
            .border(1.dp, Palette.ink.copy(alpha = 0.1f), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(song.thumbnailUrl ?: song.localThumbPath, 56.dp, RoundedCornerShape(8.dp))
            Column {
                Text(song.title, fontFamily = Display, fontSize = 17.sp, maxLines = 1, color = Palette.ink)
                Text(song.artist, fontSize = 13.sp, maxLines = 1, color = Palette.ink2)
            }
        }
        Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // steps through off, Shuffle, Smart shuffle, as on the phone
                BarIcon(if (state.smart) Icons.SmartShuffle else Icons.Shuffle, when { state.smart -> "Smart shuffle on"; state.shuffle -> "Shuffle on"; else -> "Shuffle off" }, if (state.shuffle) Palette.accent else Palette.ink2) { player.cycleShuffle() }
                BarIcon(Icons.Prev, "Previous", Palette.ink, 4.dp) { player.previous() }
                Tip(if (state.wantsPlay) "Pause" else "Play") {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Palette.ink).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                        Icon(if (state.wantsPlay) Icons.Pause else Icons.Play, if (state.wantsPlay) "Pause" else "Play", Modifier.size(26.dp), tint = Palette.bg)
                    }
                }
                BarIcon(Icons.Next, "Next", Palette.ink, 4.dp) { player.next() }
                BarIcon(Icons.Repeat, when (state.repeatMode) { RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat this song"; else -> "Repeat off" }, if (state.repeatMode != RepeatMode.OFF) Palette.accent else Palette.ink2) { player.cycleRepeat() }
            }
            BarSeek(player, state)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val lib = services.library
            val scope = rememberCoroutineScope()
            val savedSongs by lib.db.songs().observeSaved().collectAsState(emptyList())
            val saved = savedSongs.any { it.id == song.id }
            if (!song.isLocal) BarIcon(if (saved) Icons.Check else Icons.Download, if (saved) "Saved on this computer" else "Save on this computer", if (saved) Palette.accent else Palette.ink2, 7.dp) {
                scope.launch { if (saved) lib.downloads.remove(song.id) else lib.downloads.save(song) }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                BarIcon(Icons.More, "More", Palette.ink2) { menu = true }
                if (menu) SongMenu(services, song, { menu = false }) {}
            }
            BarIcon(Icons.Queue, "Queue", if (queueOpen) Palette.accent else Palette.ink2, 6.dp, onQueue)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Volume, "Volume", Modifier.size(20.dp), tint = Palette.ink2)
            var volume by remember { mutableStateOf(100f) }
            Track(volume, 100f, Palette.ink2, { volume = it; services.setVolume(it.toInt()) }, {}, Modifier.padding(start = 8.dp).width(110.dp))
        }
    }
}

/** Seek bar with the time on each side, in one line. */
@Composable
private fun BarSeek(player: MusicPlayer, s: PlayerUiState) {
    val position by player.position.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = s.durationMs.toFloat().coerceAtLeast(1f)
    val shown = (dragging ?: position.toFloat()).coerceIn(0f, max)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimeText(clock(shown.toLong()), Palette.ink.copy(alpha = 0.5f), 12.sp)
        Track(shown, max, Palette.accent, { dragging = it }, { dragging?.let { player.seekTo(it.toLong()) }; dragging = null }, Modifier.weight(1f))
        TimeText(clock(s.durationMs), Palette.ink.copy(alpha = 0.5f), 12.sp)
    }
}
