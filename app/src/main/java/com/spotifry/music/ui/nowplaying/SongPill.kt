// One pill for lyrics and the queue is an idea from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.

package com.spotifry.music.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.player.PlayerUiState
import kotlin.math.abs

/**
 * What the pill says, null to show nothing. Untimed lyrics have no current line, and their first line would look stuck,
 * so the pill just offers them. Nothing shows while lyrics are looked up: "Next" would only flash before the lyric.
 */
internal fun pillLabel(lyrics: LyricsUi, next: String?, at: () -> Long): String? = when (val l = (lyrics as? LyricsUi.Ready)?.lyrics) {
    is Lyrics.Timed -> l.lines[l.lines.indexAt(at()).coerceAtLeast(0)].text
    is Lyrics.Plain -> "Show lyrics"
    null -> if (lyrics == LyricsUi.Loading) null else next?.let { "Next: $it" }
}

/** The song Next goes to, so repeat all and shuffle are followed; nothing when Next would stay on this one. */
internal fun PlayerUiState.nextTitle(): String? = nextIndex.takeIf { it != C.INDEX_UNSET && it != currentIndex }?.let { queue.getOrNull(it)?.title }

/** Under the controls: tap for the lyrics (the queue when there are none), swipe sideways for the queue. */
@Composable
internal fun SongPill(lyrics: LyricsUi, next: String?, position: () -> Long, onLyrics: () -> Unit, onQueue: () -> Unit) {
    // only changes when the line does, so the position ticks stop here
    val label by remember(lyrics, next, position) { derivedStateOf { pillLabel(lyrics, next, position) } }
    val swipe = with(LocalDensity.current) { 40.dp.toPx() }
    val open by rememberUpdatedState(onQueue)
    // the space stays when it's empty, so the controls don't jump when lyrics arrive or the queue runs out
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp).height(48.dp)) {
        val text = label ?: return@Box
        val hasLyrics = lyrics is LyricsUi.Ready
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .pointerInput(swipe) {
                    var moved = 0f
                    detectHorizontalDragGestures(onDragStart = { moved = 0f }) { change, dx ->
                        change.consume()
                        val before = abs(moved)
                        moved += dx
                        if (before < swipe && abs(moved) >= swipe) open()
                    }
                }
                .semantics { if (hasLyrics) customActions = listOf(CustomAccessibilityAction("Open queue") { onQueue(); true }) }
                .clickable(role = Role.Button, onClickLabel = if (hasLyrics) "Show all lyrics" else "Open queue", onClick = if (hasLyrics) onLyrics else onQueue)
                // the visible pill is a little slimmer than the place to touch it
                .padding(vertical = 4.dp).background(Color.White.copy(alpha = .12f), CircleShape).padding(horizontal = 16.dp),
            Alignment.Center,
        ) {
            Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
