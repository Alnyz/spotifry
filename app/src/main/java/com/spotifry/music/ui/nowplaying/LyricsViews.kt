package com.spotifry.music.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons

private val Sung = Color.White
// dimmed, but still readable on the mid-tone cover colours
private val Past = Color.White.copy(alpha = .55f)
private val Ahead = Color.White.copy(alpha = .72f)
// words of the line being sung that aren't reached yet: dimmer than other lines, so the lit words stand out
private val NotYet = Color.White.copy(alpha = .45f)

/**
 * The song's position, counted forward every frame between the player's twice-a-second updates, so words light up
 * on time instead of in pairs. Only runs while shown.
 */
@Composable
private fun smoothPosition(position: () -> Long, playing: Boolean): androidx.compose.runtime.State<Long> {
    val smooth = remember { androidx.compose.runtime.mutableLongStateOf(position()) }
    // the newest lambda: a trim change shifts where lyrics count from
    val pos = androidx.compose.runtime.rememberUpdatedState(position)
    LaunchedEffect(playing) {
        var raw = pos.value(); var at = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val now = System.nanoTime(); val p = pos.value()
            if (p != raw) { raw = p; at = now }
            // never more than 0.6 s past the last real update, in case the player stalls
            smooth.longValue = if (playing) raw + ((now - at) / 1_000_000).coerceAtMost(600) else raw
        }
    }
    return smooth
}

/** The line with its sung words lit and the rest dim; a line without word times is all one [lit] colour. */
@Composable
private fun sungLine(line: LyricLine, position: androidx.compose.runtime.State<Long>, lit: Color): androidx.compose.ui.text.AnnotatedString {
    if (line.words.isEmpty()) return androidx.compose.ui.text.AnnotatedString(line.text)
    // redraws when the next word starts, not every frame
    val reached by remember(line) { derivedStateOf { line.words.count { it.startMs <= position.value } } }
    return androidx.compose.ui.text.buildAnnotatedString {
        line.words.forEachIndexed { i, w ->
            withStyle(androidx.compose.ui.text.SpanStyle(color = if (i < reached) lit else NotYet)) { append(w.text) }
        }
    }
}

/** Under the controls on Now playing: four lines around the one being sung. Hidden when the song has no lyrics. */
@Composable
internal fun LyricsCard(ui: LyricsUi, position: () -> Long, playing: Boolean, color: Color, onShowAll: () -> Unit, onRetry: () -> Unit) {
    if (ui == LyricsUi.None) return
    val ready = ui as? LyricsUi.Ready
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color)
            .let { if (ready != null) it.clickable(onClickLabel = "Show all lyrics", onClick = onShowAll) else it }
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleMedium)
            val action = when (ui) { is LyricsUi.Ready -> "Show all"; LyricsUi.Failed -> "Try again"; else -> null }
            if (action != null) Text(
                action, Modifier.clip(CircleShape).clickable(onClick = if (ready != null) onShowAll else onRetry).padding(horizontal = 12.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge, color = Faint,
            )
        }
        when (ui) {
            LyricsUi.Loading -> Text("Looking for lyrics…", style = MaterialTheme.typography.bodyMedium, color = Faint)
            LyricsUi.Failed -> Text("Couldn't load lyrics. Check your connection.", style = MaterialTheme.typography.bodyMedium, color = Faint)
            is LyricsUi.Ready -> when (val l = ui.lyrics) {
                is Lyrics.Timed -> {
                    // redraws when the sung line changes, not on every position tick
                    val now by remember(l, position) { derivedStateOf { l.lines.indexAt(position()) } }
                    val first = (now - 1).coerceIn(0, (l.lines.size - 4).coerceAtLeast(0))
                    val smooth = smoothPosition(position, playing)
                    l.lines.drop(first).take(4).forEachIndexed { k, line ->
                        if (first + k == now) CardLine(sungLine(line, smooth, Sung), Sung) else CardLine(androidx.compose.ui.text.AnnotatedString(line.text), Ahead)
                    }
                }
                is Lyrics.Plain -> l.lines.take(4).forEach { CardLine(androidx.compose.ui.text.AnnotatedString(it), Sung) }
            }
            LyricsUi.None -> Unit
        }
    }
}

@Composable
private fun CardLine(text: androidx.compose.ui.text.AnnotatedString, color: Color) {
    val c by animateColorAsState(color, label = "lyric line")
    Text(text, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 27.sp), color = c, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** "Show all": every line, scrolling with the song. Tapping a timed line jumps there. */
@Composable
internal fun LyricsScreen(state: PlayerUiState, position: () -> Long, lyrics: Lyrics, color: Color, onClose: () -> Unit, onSeek: (Long) -> Unit, onToggle: () -> Unit) {
    val song = state.current ?: return
    val timed = lyrics as? Lyrics.Timed
    // lyric times count from the whole song's start, the player from the trim's
    val trimStart = state.trim?.startMs ?: 0L
    val sung = remember(trimStart) { { position() + trimStart } }
    val now by remember(timed, trimStart) { derivedStateOf { timed?.lines?.indexAt(sung()) ?: -1 } }
    val list = rememberLazyListState()
    val smooth = smoothPosition(sung, state.isPlaying)
    // keeps the sung line about a third of the way down, where the eye rests
    LaunchedEffect(now) { if (now >= 0) list.animateScrollToItem(now, -list.layoutInfo.viewportSize.height / 3) }
    Column(Modifier.fillMaxSize().background(color).clickable(enabled = false) {}) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)).padding(start = 4.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClose, Modifier.size(48.dp)) { Icon(Icons.Down, "Close lyrics") }
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (timed != null) itemsIndexed(timed.lines) { i, line ->
                val c by animateColorAsState(when { i == now -> Sung; i < now -> Past; else -> Ahead }, label = "lyric line")
                Text(
                    if (i == now) sungLine(line, smooth, Sung) else androidx.compose.ui.text.AnnotatedString(line.text),
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Play from here") { onSeek((line.atMs - trimStart).coerceAtLeast(0)) }, style = BigLine, color = c,
                )
            }
            else itemsIndexed((lyrics as Lyrics.Plain).lines) { _, line -> Text(line, style = BigLine, color = Sung) }
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 0.dp, end = 20.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(state, position, Modifier.weight(1f).padding(horizontal = 24.dp), onSeek)
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable(onClick = onToggle), Alignment.Center) {
                    Icon(if (state.isPlaying) Icons.Pause else Icons.Play, if (state.isPlaying) "Pause" else "Play", Modifier.size(26.dp), tint = Color(0xFF0C0D10))
                }
            }
            Text(if (timed == null) "Lyrics from LRCLIB · not timed" else "Lyrics from ${timed.from}", Modifier.padding(start = 24.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Faint)
        }
    }
}

private val BigLine @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp)
