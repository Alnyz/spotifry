package com.spotifry.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.components.CardRowShape
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.components.fadeIn

/** Home's rows for the artists picked on first launch: the artists, then a few songs from each. Nothing when none were picked. */
fun LazyListScope.artistItems(a: ArtistsHome, onOpen: (PickedArtist) -> Unit, onPlay: (List<Song>, Int) -> Unit, onEdit: () -> Unit = {}, fade: Boolean = false) {
    if (a.artists.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            SectionTitle("Your artists", Modifier.weight(1f))
            Box(
                Modifier.padding(end = 6.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Edit your artists", onClick = onEdit).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 14.dp),
                Alignment.Center,
            ) { Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }
    }
    item {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(a.artists, key = { _, x -> x.key }) { _, x -> ArtistCircle(x, onOpen) }
        }
    }
    if (a.songs.isEmpty() && !a.loading) return
    // the heading is there from the first frame; grey cards the size of the songs hold the place until they arrive
    item { SectionTitle("From your artists") }
    if (a.songs.isEmpty()) {
        item { CardRowShape(132.dp, 8.dp) }
        return
    }
    item {
        LazyRow(Modifier.fadeIn(fade), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(a.songs, key = { _, s -> "artist-" + s.id }) { i, s -> ArtistSong(s) { onPlay(a.songs, i) } }
        }
    }
}

/** On an empty Home with no artists picked: the way back to the artist screen. */
fun LazyListScope.pickArtistsItem(onPick: () -> Unit) {
    item {
        Text(
            "Pick artists for Home",
            Modifier.padding(start = 20.dp, top = 14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onPick).padding(horizontal = 16.dp, vertical = 11.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ArtistCircle(a: PickedArtist, onOpen: (PickedArtist) -> Unit) {
    Column(
        Modifier.width(76.dp).clickable(enabled = a.url.isNotBlank(), onClickLabel = "Open ${a.name}") { onOpen(a) },
        Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally,
    ) {
        Cover(a.key, a.thumbnailUrl, null, 72.dp, shape = CircleShape, shelf = true)
        Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ArtistSong(s: Song, onPlay: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClickLabel = "Play", onClick = onPlay)) {
        CoverArt(s.id, s.thumbnailUrl, s.localThumbPath, Modifier.size(132.dp), RoundedCornerShape(14.dp), shelf = true)
        Text(s.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
