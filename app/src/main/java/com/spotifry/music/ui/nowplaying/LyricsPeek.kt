package com.spotifry.music.ui.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.ui.Icons

/** The line the peek shows: the one being sung (the first one before it starts), or a plain song's first line; null without lyrics. */
internal fun peekLine(lyrics: Lyrics?, at: Long): String? = when (lyrics) {
    is Lyrics.Timed -> lyrics.lines.takeIf { it.isNotEmpty() }?.let { it[it.indexAt(at).coerceAtLeast(0)].text.ifBlank { "♪" } }
    is Lyrics.Plain -> lyrics.lines.firstOrNull { it.isNotBlank() }
    null -> null
}

/**
 * Under the title: a note, the line being sung, and ">". Tapping opens lyrics mode. The row keeps its height with no
 * lyrics too, so the controls don't move between songs or while lyrics load.
 */
@Composable
internal fun LyricsPeek(lyrics: LyricsUi, sung: () -> Long, onShowAll: () -> Unit, onRetry: () -> Unit = {}, modifier: Modifier = Modifier) {
    val ready = (lyrics as? LyricsUi.Ready)?.lyrics
    // changes only when the sung line does, so the position ticks stop here
    val line by remember(ready, sung) { derivedStateOf { peekLine(ready, sung()) } }
    val base = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lyrics peek")
    when {
        line != null -> Row(
            base.clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Show all lyrics", onClick = onShowAll)
                // one stop for a screen reader, reading the line being sung; the rest isn't read out as it changes
                .clearAndSetSemantics { role = Role.Button; contentDescription = "Show all lyrics. $line"; onClick("Show all lyrics") { onShowAll(); true } },
            Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
        ) {
            Icon(Icons.Note, null, Modifier.size(20.dp), tint = Faint)
            Crossfade(line, Modifier.weight(1f), animationSpec = tween(150), label = "lyric peek") { t ->
                Text(t.orEmpty(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Chevron, null, Modifier.size(18.dp), tint = Faint)
        }
        // no line to show: a quiet row of the same shape, so there is no hole under the title
        lyrics == LyricsUi.Loading -> QuietPeek(base, "Looking for lyrics…", false)
        lyrics == LyricsUi.Failed -> QuietPeek(base.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Try again", onClick = onRetry), "Couldn't load lyrics · Try again", true)
        else -> QuietPeek(base.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Show all lyrics", onClick = onShowAll), "Lyrics", true)
    }
}

@Composable
private fun QuietPeek(modifier: Modifier, text: String, chevron: Boolean) {
    Row(modifier, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        Icon(Icons.Note, null, Modifier.size(20.dp), tint = Faint)
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(Icons.Chevron, null, Modifier.size(18.dp), tint = Faint)
    }
}
