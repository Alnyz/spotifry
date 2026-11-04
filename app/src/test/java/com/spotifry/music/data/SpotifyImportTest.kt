package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyImportTest {
    private fun song(id: String, title: String, sec: Long) = Song(id, title, "YOASOBI", sec, null)

    @Test fun readsPlaylistIdFromShareLink() {
        assertEquals("2wauuVc0v7DBWGylJ6nFFr", SpotifyImport.playlistId("https://open.spotify.com/playlist/2wauuVc0v7DBWGylJ6nFFr?si=abc&pi=x"))
        assertNull(SpotifyImport.playlistId("https://youtube.com/watch?v=1"))
    }

    @Test fun picksSameTitleOverSameLength() {
        val t = SpotifyTrack("Into The Night", "YOASOBI", 259)
        val results = listOf(song("jp", "夜に駆ける", 262), song("en", "Into The Night", 260), song("long", "Into The Night (Extended)", 400))
        assertEquals("en", SpotifyImport.pick(t, results)?.id)
    }

    @Test fun noMatchWhenLengthIsOff() {
        assertNull(SpotifyImport.pick(SpotifyTrack("A", "B", 200), listOf(song("x", "A", 260))))
    }
}

class RecentSearchesTest {
    @Test fun newestFirstNoRepeatsAtMostTen() {
        var saved = emptyList<String>()
        val r = RecentSearches(listOf("creed")) { saved = it }
        r.add("linkin"); r.add("Creed "); r.add("  "); (1..12).forEach { r.add("q$it") }
        assertEquals(10, r.list.value.size)
        assertEquals("q12", r.list.value.first())
        assertEquals(listOf("q12", "q11"), saved.take(2))
        r.add("x"); r.add("x")
        assertEquals(1, r.list.value.count { it == "x" })
    }
}

class SpotifyPickTest {
    @Test fun karaokeAndOtherArtistsLose() {
        val t = SpotifyTrack("Shot Me Down", "David Guetta, Skylar Grey", 191)
        val results = listOf(
            Song("k", "Shot Me Down (Karaoke Version)", "Sunfly Karaoke", 191, null),
            Song("h", "Shot Me Down", "Hip Hop Nation", 190, null),
            Song("d", "Shot Me Down (feat. Skylar Grey)", "David Guetta", 192, null),
        )
        assertEquals("d", SpotifyImport.pick(t, results)?.id)
    }
}
