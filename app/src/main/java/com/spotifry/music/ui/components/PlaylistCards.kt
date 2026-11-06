package com.spotifry.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.Icons

fun countsLabel(p: PlaylistWithCounts) = "${p.songCount} ${if (p.songCount == 1) "song" else "songs"} · ${p.savedCount} saved"

/** A playlist as a square of covers with its name and counts under it. */
@Composable
fun PlaylistTile(p: PlaylistWithCounts, covers: List<SongEntity>, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = true) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        CoverMosaic(covers, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), large)
        Text(p.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countsLabel(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Up to four covers of the playlist's first songs in a 2 by 2 square; an empty playlist shows a plain tile with the list icon. */
@Composable
fun CoverMosaic(songs: List<SongEntity>, modifier: Modifier = Modifier, large: Boolean = true) {
    when {
        songs.isEmpty() -> Box(modifier.background(MaterialTheme.colorScheme.surface), Alignment.Center) {
            Icon(Icons.List, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // fewer than four songs: one full cover reads better than the same picture repeated
        songs.size < 4 -> CoverArt(songs[0].id, songs[0].thumbnailUrl, songs[0].thumbPath, modifier, large = large)
        else -> Column(modifier) {
            songs.take(4).chunked(2).forEach { pair ->
                Row(Modifier.weight(1f)) {
                    pair.forEach { s -> CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.weight(1f).fillMaxHeight(), large = large) }
                }
            }
        }
    }
}
