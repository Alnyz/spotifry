package com.spotifry.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.source.Song

/** The artists picked (on the phone, or here) and a few songs from each once their pages have answered. */
class HomeArtists(val artists: List<PickedArtist>, val songs: List<Song>)

/** Home's artists, loaded like the phone's: the artists at once, their songs when the pages answer. Pages looked up are kept in the picks. */
@Composable
fun rememberHomeArtists(services: Services): HomeArtists {
    val settings = services.library.settings
    val loader = services.artistSongs
    val picked by settings.artists.collectAsState()
    var home by remember { mutableStateOf(HomeArtists(picked, loader.known(picked))) }
    LaunchedEffect(picked) {
        loader.keepOnly(picked)
        home = HomeArtists(picked, loader.known(picked))
        if (picked.isEmpty()) return@LaunchedEffect
        val got = loader.load(picked)
        // an artist picked without a page now has one: keep it, unless the picks changed meanwhile
        if (got.artists != picked && settings.artists.value == picked) settings.setArtists(got.artists)
        home = HomeArtists(got.artists, got.songs)
    }
    return home
}

/** The round artist: opens its page; one without a page yet is looked up by name first. */
@Composable
fun ArtistCircle(a: PickedArtist, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Cover(a.thumbnailUrl, 104.dp, CircleShape)
        Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
