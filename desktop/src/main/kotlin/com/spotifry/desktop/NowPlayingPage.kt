package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.RepeatMode
import kotlinx.coroutines.launch

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Failed : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

/** The big view of the song playing: its cover, the lyrics following the singing, and what comes next. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NowPlayingPage(services: Services, modifier: Modifier = Modifier) {
    val player = services.player
    val lib = services.library
    val state by player.state.collectAsState()
    val song = state.current
    val scope = rememberCoroutineScope()
    val savedIds by lib.db.songs().observeSaved().collectAsState(emptyList())
    val saved = song != null && savedIds.any { it.id == song.id }
    val shift by lib.settings.lyricsShift.collectAsState()

    Row(modifier) {
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                Cover(song.thumbnailUrl ?: song.localThumbPath, 280.dp, RoundedCornerShape(22.dp), large = true)
                Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 40.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (state.wantsPlay) "Playing" else "Paused", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(song.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    Text(song.artist, color = Palette.ink2, fontSize = 17.sp, maxLines = 1)
                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tool(Icons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", state.shuffle) { player.cycleShuffle() }
                        Tool(Icons.Repeat, when (state.repeatMode) { RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat this song"; else -> "Repeat off" }, state.repeatMode != RepeatMode.OFF) { player.cycleRepeat() }
                        Tool(if (saved) Icons.Check else Icons.Download, if (saved) "Saved on this computer" else "Save on this computer", saved) {
                            scope.launch { if (saved) lib.downloads.remove(song.id) else lib.downloads.save(song) }
                        }
                    }
                    LyricsPanel(services, song, shift[song.id] ?: 0, Modifier.padding(top = 18.dp).weight(1f).fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        QueuePanel(services, state, Modifier.width(300.dp).fillMaxHeight())
    }
}

@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (on) Palette.accent.copy(alpha = 0.18f) else Palette.high).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (on) Palette.accent else Palette.ink2)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Palette.accent else Palette.ink2)
    }
}

@Composable
private fun LyricsPanel(services: Services, song: Song, shiftMs: Int, modifier: Modifier) {
    val lyrics by produceState<LyricsState>(LyricsState.Loading, song.id) {
        value = LyricsState.Loading
        value = try { services.lyrics(song)?.let { LyricsState.Found(it) } ?: LyricsState.None } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { LyricsState.Failed }
    }
    Box(modifier) {
        when (val l = lyrics) {
            LyricsState.Loading -> Text("Looking for lyrics…", color = Palette.ink2)
            LyricsState.None -> Text("No lyrics found for this song.", color = Palette.ink2)
            LyricsState.Failed -> Text("Couldn't load lyrics. Check your connection.", color = Palette.ink2)
            is LyricsState.Found -> when (val x = l.lyrics) {
                is Lyrics.Timed -> TimedLyrics(services, x, shiftMs)
                is Lyrics.Plain -> Column {
                    Text("Lyrics from ${Lyrics.LRCLIB} · not timed", color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn { itemsIndexed(x.lines) { _, line -> Text(line.ifBlank { " " }, fontSize = 18.sp, lineHeight = 26.sp, color = Palette.ink.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold) } }
                }
            }
        }
    }
}

@Composable
private fun TimedLyrics(services: Services, lyrics: Lyrics.Timed, shiftMs: Int) {
    val position by services.player.position.collectAsState()
    // a positive shift runs the lyrics earlier: they are looked up that much ahead of the song
    val at = position + shiftMs
    val index = lyrics.lines.indexAt(at)
    val list = rememberLazyListState()
    LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == index
            Text(
                lineText(line, current, at), fontSize = if (current) 26.sp else 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                color = if (current) Palette.ink else Palette.ink.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { services.player.seekTo((line.atMs - shiftMs).coerceAtLeast(0)) },
            )
        }
    }
}

/** The line as text; the current one, when its words are timed, shows the words already sung in full colour. */
@Composable
private fun lineText(line: LyricLine, current: Boolean, at: Long): AnnotatedString {
    val text = line.text.ifBlank { "♪" }
    if (!current || line.words.isEmpty()) return AnnotatedString(text)
    val sung = Palette.ink; val ahead = Palette.ink.copy(alpha = 0.45f)
    return buildAnnotatedString { line.words.forEach { w -> withStyle(SpanStyle(color = if (w.startMs <= at) sung else ahead)) { append(w.text) } } }
}

@Composable
private fun QueuePanel(services: Services, state: com.spotifry.music.player.PlayerUiState, modifier: Modifier) {
    val upcoming = state.upNextIndices().filter { it != state.currentIndex }.take(60)
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(vertical = 18.dp, horizontal = 10.dp)) {
        Text("Coming up", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (state.from != null) Text("Playing from ${state.from}", color = Palette.ink2, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp))
        if (upcoming.isEmpty()) Text("Nothing after this song.", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(upcoming, key = { _, i -> "$i-${state.queue.getOrNull(i)?.id}" }) { _, i ->
                val s = state.queue.getOrNull(i) ?: return@itemsIndexed
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).clickable { services.player.skipTo(i) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Cover(s.thumbnailUrl ?: s.localThumbPath, 36.dp, RoundedCornerShape(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Text(s.artist, color = Palette.ink2, fontSize = 12.sp, maxLines = 1)
                    }
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { services.player.remove(i, s.id) }, contentAlignment = Alignment.Center) {
                        Text("×", color = Palette.ink2, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
