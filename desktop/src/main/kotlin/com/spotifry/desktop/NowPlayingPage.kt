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

internal sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Failed : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

/** The big view of the song playing: its cover, the lyrics following the singing, and what comes next. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NowPlayingPage(services: Services, state: com.spotifry.music.player.PlayerUiState, queueOpen: Boolean, onShowQueue: () -> Unit, modifier: Modifier = Modifier) {
    val player = services.player
    val lib = services.library
    val song = state.current
    val scope = rememberCoroutineScope()
    val savedIds by lib.db.songs().observeSaved().collectAsState(emptyList())
    val saved = song != null && savedIds.any { it.id == song.id }
    val shift by lib.settings.lyricsShift.collectAsState()

    if (song == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Nothing playing", fontFamily = Serif, fontSize = 40.sp, color = Palette.ink)
            Text("Pick a song and it shows up here.", color = Palette.ink2, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    // the song on the left, what is sung and what comes next beside it
    val lyrics = lyricsFor(services, song)
    val has = (lyrics as? LyricsState.Found)?.lyrics != null
    BoxWithConstraints(modifier) {
        // the cover gives way on a short window so the queue under it keeps some room
        val cover = (maxHeight - 400.dp).coerceIn(150.dp, 340.dp)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight().padding(top = 8.dp)) {
                Cover(song.thumbnailUrl ?: song.localThumbPath, cover, RoundedCornerShape(12.dp), large = true)
                Text(song.title, fontFamily = Serif, fontSize = 28.sp, lineHeight = 30.sp, maxLines = 2, color = Palette.ink, modifier = Modifier.padding(top = 18.dp))
                Text(song.artist, color = Palette.accent, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                state.quality?.let { QualityPill(it, Modifier.padding(top = 12.dp)) }
                QueueList(services, state, queueOpen, onShowQueue, Modifier.weight(1f).fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (has) LyricsPanel(services, song, shift[song.id] ?: 0, Modifier.padding(top = 8.dp).fillMaxSize())
                else if (lyrics != LyricsState.Loading) Text("No lyrics for this song", fontFamily = Serif, fontSize = 20.sp, color = Palette.ink.copy(alpha = 0.4f), modifier = Modifier.padding(top = 12.dp))
            }
        }
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

/** The lyrics of [song], looked up once per song. */
@Composable
internal fun lyricsFor(services: Services, song: Song): LyricsState {
    val l by produceState<LyricsState>(LyricsState.Loading, song.id) {
        value = LyricsState.Loading
        value = try { services.lyrics(song)?.let { LyricsState.Found(it) } ?: LyricsState.None } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { LyricsState.Failed }
    }
    return l
}

@Composable
private fun LyricsPanel(services: Services, song: Song, shiftMs: Int, modifier: Modifier) {
    val lyrics = lyricsFor(services, song)
    Box(modifier) {
        when (val l = lyrics) {
            LyricsState.Loading -> Text("Looking for lyrics…", fontFamily = Serif, fontSize = 22.sp, color = Palette.ink2)
            LyricsState.None -> Unit
            LyricsState.Failed -> Text("Couldn't load lyrics. Check your connection.", fontFamily = Serif, fontSize = 22.sp, color = Palette.ink2)
            is LyricsState.Found -> when (val x = l.lyrics) {
                is Lyrics.Timed -> TimedLyrics(services, x, shiftMs)
                is Lyrics.Plain -> Column {
                    Text("Lyrics from ${Lyrics.LRCLIB} · not timed", color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn { itemsIndexed(x.lines) { _, line -> Text(line.ifBlank { " " }, fontFamily = Serif, fontSize = 24.sp, lineHeight = 32.sp, color = Palette.ink.copy(alpha = 0.85f)) } }
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
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = LocalBottomInset.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == index
            Text(
                lineText(line, current, at), fontFamily = Serif, fontSize = if (current) 30.sp else 22.sp, lineHeight = if (current) 36.sp else 28.sp, letterSpacing = (-0.5).sp,
                color = if (current) Palette.ink else Palette.ink.copy(alpha = 0.4f),
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

/** What plays next, with a button to drop a song from the queue. */
@Composable
private fun QueueList(services: Services, state: com.spotifry.music.player.PlayerUiState, queueOpen: Boolean, onShowQueue: () -> Unit, modifier: Modifier) {
    val all = state.upNextIndices().filter { it != state.currentIndex }
    val upcoming = all.take(4)
    Column(modifier) {
        SectionTitle(if (state.from != null) "Coming up · from ${state.from}" else "Coming up")
        if (upcoming.isEmpty()) Text("Nothing after this song.", color = Palette.ink2, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
            itemsIndexed(upcoming, key = { _, i -> "$i-${state.queue.getOrNull(i)?.id}" }) { _, i ->
                val s = state.queue.getOrNull(i) ?: return@itemsIndexed
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).clickable { services.player.skipTo(i) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Cover(s.thumbnailUrl ?: s.localThumbPath, 40.dp, RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontFamily = Serif, fontSize = 17.sp, maxLines = 1)
                        Text(s.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
                    }
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { services.player.remove(i, s.id) }, contentAlignment = Alignment.Center) {
                        Text("×", color = Palette.ink2, fontSize = 18.sp)
                    }
                }
            }
            if (all.size > upcoming.size) item { Text(if (queueOpen) "Hide queue" else "Show all ${all.size}", color = Palette.accent, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowQueue).padding(horizontal = 10.dp, vertical = 8.dp)) }
        }
    }
}
