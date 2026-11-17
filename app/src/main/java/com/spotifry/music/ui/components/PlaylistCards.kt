package com.spotifry.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.Icons

fun countsLabel(p: PlaylistWithCounts) = "${p.songCount} ${if (p.songCount == 1) "song" else "songs"} · ${p.savedCount} saved"

/** Set once at the app root: where the pictures users chose for their playlists are kept. Absent in tests. */
val LocalPlaylistCovers = staticCompositionLocalOf<com.spotifry.music.data.PlaylistCovers?> { null }

/** The picture file of [p]'s chosen cover, or null when it is on the default cover (or the file is gone). */
@Composable
fun playlistCoverPath(p: PlaylistWithCounts?): String? {
    val covers = LocalPlaylistCovers.current
    return androidx.compose.runtime.remember(p?.cover, covers) { covers?.file(p?.cover)?.path }
}

/** A playlist as a square of covers with its name and counts under it. */
@Composable
fun PlaylistTile(p: PlaylistWithCounts, covers: List<SongEntity>, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = true) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        CoverMosaic(covers, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), large, custom = playlistCoverPath(p))
        Text(p.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countsLabel(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The default playlist cover: the first songs' covers, laid out by how many there are. One song shows its cover whole;
 * two are stacked as two bands; three are one wide band over two squares; four or more are a 2 by 2. An empty playlist
 * shows a plain tile with a list icon, bigger on a bigger cover. [custom] is the picture file the user chose instead,
 * which wins when there is one.
 */
@Composable
fun CoverMosaic(songs: List<SongEntity>, modifier: Modifier = Modifier, large: Boolean = true, custom: String? = null) {
    @Composable fun tile(s: SongEntity, m: Modifier) = CoverArt(s.id, s.thumbnailUrl, s.thumbPath, m, large = large)
    when {
        custom != null -> CoverArt(custom, null, custom, modifier, large = large)
        songs.isEmpty() -> BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val icon = (minOf(maxWidth, maxHeight) * .27f).coerceIn(36.dp, 112.dp)
            // above the middle on a tall cover, where the name sits over the bottom
            Icon(Icons.List, null, Modifier.align(Alignment.Center).padding(bottom = if (maxHeight > maxWidth) maxHeight * .28f else 0.dp).size(icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        songs.size == 1 -> tile(songs[0], modifier)
        songs.size == 2 -> Column(modifier) { songs.take(2).forEach { tile(it, Modifier.weight(1f).fillMaxWidth()) } }
        songs.size == 3 -> Column(modifier) {
            tile(songs[0], Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.weight(1f)) { songs.drop(1).forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) } }
        }
        else -> Column(modifier) {
            songs.take(4).chunked(2).forEach { pair ->
                Row(Modifier.weight(1f)) { pair.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) } }
            }
        }
    }
}
