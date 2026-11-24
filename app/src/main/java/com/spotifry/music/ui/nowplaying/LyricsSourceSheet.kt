package com.spotifry.music.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.LyricsKind
import com.spotifry.music.data.kind
import com.spotifry.music.ui.Icons

/** What a source is, in a few words. Fixed text: the app can't tell more about a source's recording than that. */
private fun note(source: String) = when (source) {
    Lyrics.KUGOU, Lyrics.PORTATO -> "Karaoke timing"
    Lyrics.BETTER_LYRICS, Lyrics.LRC_RED -> "Apple Music timing"
    Lyrics.SIMPMUSIC, Lyrics.UNISON, Lyrics.LRCLIB -> "Written by listeners"
    Lyrics.NETEASE -> "From NetEase Cloud Music"
    Lyrics.DEEZER -> "From Deezer"
    else -> ""
}

/**
 * Where the lyrics come from, and a way to change it while the song plays. [rows] fill in as each source answers;
 * [pick] is the source chosen for this song (null: Automatic) and [now] the one the lyrics on screen come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSourceSheet(rows: List<NowPlayingViewModel.SourceRow>, pick: String?, now: String?, onPick: (String?) -> Unit, onClose: () -> Unit) {
    var showMissing by remember { mutableStateOf(false) }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Lyrics source", Modifier.padding(horizontal = 24.dp), style = SheetTitle)
            Text(
                "Tap one to use it for this song. The app remembers your pick.", Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 10.dp),
                fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceItem("Automatic", "The best match." + (now?.let { " Now $it." } ?: ""), selected = pick == null) { onPick(null); onClose() }
            val found = rows.filter { !it.checking && it.lyrics != null }
            listOf(LyricsKind.WORD to "Word by word", LyricsKind.LINE to "Line by line", LyricsKind.PLAIN to "Plain text").forEach { (kind, title) ->
                val group = found.filter { it.lyrics!!.kind() == kind }
                if (group.isNotEmpty()) {
                    Group(title)
                    group.forEach { r -> SourceItem(r.name, note(r.name), selected = pick == r.name) { onPick(r.name); onClose() } }
                }
            }
            val checking = rows.filter { it.checking }
            if (checking.isNotEmpty()) {
                Group("Checking")
                checking.forEach { r -> SourceItem(r.name, "Checking…", selected = false, waiting = true, onClick = null) }
            }
            val missing = rows.filter { !it.checking && it.lyrics == null }
            if (missing.isNotEmpty()) {
                SourceItem(
                    "Not found for this song (${missing.size})", null, selected = false, quiet = true, chevron = if (showMissing) Icons.Down else Icons.Chevron,
                ) { showMissing = !showMissing }
                if (showMissing) missing.forEach { r -> SourceItem(r.name, "Nothing for this song", selected = false, quiet = true, onClick = null) }
            }
        }
    }
}

@Composable
private fun Group(title: String) =
    Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Faint)

@Composable
private fun SourceItem(
    title: String, note: String?, selected: Boolean, waiting: Boolean = false, quiet: Boolean = false,
    chevron: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: (() -> Unit)?,
) {
    val tint = if (quiet) Faint else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
            .semantics { this.selected = selected }
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chevron != null) { Icon(chevron, null, Modifier.size(18.dp), tint = tint); androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (quiet) FontWeight.Medium else FontWeight.SemiBold, color = tint)
            if (note != null) Text(note, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (waiting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else if (selected) Icon(Icons.Check, "Chosen", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
