package com.spotifry.music.ui.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt

/** The line height of the three lines; the cover rule counts them, so it has one home. */
internal val PEEK_LINE = 22.sp

/** What the three slots say; null is a blank slot. */
internal data class PeekLines(val before: String?, val now: String?, val next: String?)

/**
 * The lines around [index], the one being sung (-1 before the first starts). Before the first line only the next slot is
 * filled, with line 1; after the last, the last stays as the current one and the next slot is blank.
 */
internal fun peekLines(lines: List<LyricLine>, index: Int): PeekLines {
    val i = index.coerceIn(-1, lines.lastIndex)
    return PeekLines(if (i >= 1) lines[i - 1].text else null, if (i >= 0) lines[i].text else null, lines.getOrNull(i + 1)?.text)
}

/**
 * Between the quality label and the title: the line before, the one being sung, the one after. The space is always
 * kept, lyrics or not, so the title and controls don't move between songs or while lyrics load. Only timed lyrics show;
 * tapping opens all of them.
 */
@Composable
internal fun LyricsPeek(lyrics: LyricsUi, sung: () -> Long, onShowAll: () -> Unit) {
    val lines = ((lyrics as? LyricsUi.Ready)?.lyrics as? Lyrics.Timed)?.lines?.takeIf { it.isNotEmpty() }
    // changes only when the sung line does, so the position ticks stop here
    val peek by remember(lines, sung) { derivedStateOf { if (lines == null) PeekLines(null, null, null) else peekLines(lines, lines.indexAt(sung())) } }
    val base = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp).testTag("lyrics peek")
    Column(
        if (lines == null) base else base.clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Show all lyrics", onClick = onShowAll)
            // one stop for a screen reader, reading the line being sung when it's reached; the rest isn't read out as it changes
            .clearAndSetSemantics { role = Role.Button; contentDescription = "Show all lyrics. ${peek.now.orEmpty()}"; onClick("Show all lyrics") { onShowAll(); true } }
            .padding(horizontal = 12.dp),
    ) {
        Slot(peek.before, Faint, FontWeight.Normal)
        Slot(peek.now, Color.White, FontWeight.Bold)
        Slot(peek.next, Faint, FontWeight.Normal)
    }
}

/** One line on one line, or blank space of the same height. A change fades softly (not at all with animations off). */
@Composable
private fun Slot(text: String?, color: Color, weight: FontWeight) {
    Crossfade(text, animationSpec = tween(150), label = "lyric peek") { t ->
        Text(
            t ?: " ", Modifier.fillMaxWidth(), color = color, textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = PEEK_LINE, fontWeight = weight),
        )
    }
}
