package com.spotifry.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.theme.savedMarkColor

/** One song: cover, title, artist (with the saved mark), and the three-dot menu. */
@Composable
fun SongRow(
    song: Song,
    saved: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    localCover: String? = null,
    subtitleExtra: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    /** Words being searched for: lightly marked in the name and artist. */
    highlight: String? = null,
) {
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = .22f)
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(vertical = 9.dp),
            Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
        ) {
            Cover(song.id, song.thumbnailUrl, localCover ?: song.localThumbPath, 52.dp, radius = 10.dp)
            Column(Modifier.weight(1f)) {
                Text(marked(song.title, highlight, mark), color = titleColor, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (saved) Icon(Icons.Download, "Saved on this phone", Modifier.size(14.dp).testTag("saved_mark"), tint = savedMarkColor())
                    Text(
                        marked(listOfNotNull(song.artist.ifBlank { null }, subtitleExtra).joinToString(" · "), highlight, mark),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onMore != null) IconButton(onMore, Modifier.size(48.dp)) { Icon(Icons.More, "More options") }
        }
    }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(vertical = 9.dp).testTag("loading_row"), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(bar))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(150.dp, 14.dp).clip(RoundedCornerShape(3.dp)).background(bar))
            Box(Modifier.size(90.dp, 11.dp).clip(RoundedCornerShape(3.dp)).background(bar))
        }
    }
}

/** [text] with every word of [query] given a soft background, wherever it appears. */
internal fun marked(text: String, query: String?, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        query?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.forEach { word ->
            var i = text.indexOf(word, ignoreCase = true)
            while (i >= 0) {
                addStyle(androidx.compose.ui.text.SpanStyle(background = color), i, i + word.length)
                i = text.indexOf(word, i + word.length, ignoreCase = true)
            }
        }
    }
