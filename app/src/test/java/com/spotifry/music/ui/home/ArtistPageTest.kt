package com.spotifry.music.ui.home

import androidx.compose.ui.graphics.Color
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistPageTest {
    private fun song(id: String) = Song(id, id, "Artist", 200, null)
    private val link = PageLink(SearchType.ARTISTS, "https://x/c/1", "Lune Aveline", "", "https://yt3.googleusercontent.com/a=w120-h120-l90-rj")

    @Test fun playedSongsComeFirstMostPlayedFirstThenTheRestInOrder() {
        val songs = listOf("a", "b", "c", "d", "e").map(::song)
        assertEquals(listOf("d", "b", "c", "a", "e"), topSongsOrder(songs, mapOf("b" to 3, "c" to 3, "d" to 9)).map { it.id })
    }

    @Test fun noPlaysKeepsTheOrder() = assertEquals(listOf("a", "b"), topSongsOrder(listOf("a", "b").map(::song), emptyMap()).map { it.id })

    @Test fun thePhotoIsAskedForAt1080() {
        assertEquals("https://yt3.googleusercontent.com/a=w1080-h1080-l90-rj", heroArt(link.thumbnailUrl))
        assertEquals("https://yt3.ggpht.com/b=w1080-h1080-c-k-c0x00ffffff-no-rj", heroArt("https://yt3.ggpht.com/b=s176-c-k-c0x00ffffff-no-rj"))
        assertEquals("https://example.com/p.jpg", heroArt("https://example.com/p.jpg"))
        assertEquals(null, heroArt(null))
    }

    @Test fun theTintIsThePhotosColourTakenDarker() {
        val photo = Color(0xFF8A3D1F)
        val t = pageTint(photo)
        assertTrue(t.red < photo.red && t.green < photo.green)
        assertEquals(Color(0xFF1C1F26), pageTint(null))
    }

    @Test fun theStarAddsAndRemoves() {
        val added = toggleArtist(listOf(PickedArtist("Queen", "u", null)), link)
        assertEquals(listOf("Queen", "Lune Aveline"), added.map { it.name })
        assertEquals(listOf("Queen"), toggleArtist(added, link.copy(name = "  lune aveline ")).map { it.name })
    }

    @Test fun playsAndAlbumWords() {
        assertEquals("1 play", playsText(1))
        assertEquals("48 plays", playsText(48))
        assertEquals("Album", albumLine(link))
        assertEquals("Album · 12 songs", albumLine(link.copy(songs = 12)))
        assertEquals("Album · 1 song", albumLine(link.copy(songs = 1)))
    }
}
