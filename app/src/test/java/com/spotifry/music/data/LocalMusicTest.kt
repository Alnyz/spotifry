package com.spotifry.music.data

import com.spotifry.music.data.source.isLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMusicTest {
    private val songs = listOf(
        localSong(1, "Fix You", "Coldplay", 295_000, 7),
        localSong(2, "Kiss Me", "Sixpence None the Richer", 200_000, 8),
        localSong(3, "Yellow", "Coldplay", 269_000, 7),
        localSong(4, "Clocks", "Coldplay", 307_000, 7),
        localSong(5, "Viva", "Coldplay", 240_000, 7),
    )

    @Test fun rowBecomesSong() {
        val s = localSong(42, "voice-demo-final", "<unknown>", 61_500, 9)
        assertEquals("local:42", s.id)
        assertTrue(s.isLocal)
        assertEquals("Unknown artist", s.artist)
        assertEquals(61, s.durationSec)
        assertEquals("content://media/external/audio/albumart/9", s.thumbnailUrl)
        assertEquals("Unknown song", localSong(1, " ", null, 0, 0).title)
    }

    @Test fun matchesEveryWordOnTitleOrArtist() {
        assertEquals(listOf("Fix You", "Yellow", "Clocks"), localMatches(songs, "coldplay").map { it.title })
        assertEquals(listOf("Yellow"), localMatches(songs, " yellow  COLD ").map { it.title })
        assertEquals(listOf("Kiss Me"), localMatches(songs, "kiss").map { it.title })
        assertTrue(localMatches(songs, "yellow queen").isEmpty())
        assertTrue(localMatches(songs, "   ").isEmpty())
    }

    @Test fun fileUriOnlyForLocalIds() {
        assertEquals("content://media/external/audio/media/42", LocalMusic.uriOf("local:42").toString())
        assertNull(LocalMusic.uriOf("dQw4w9WgXcQ"))
        assertNull(LocalMusic.uriOf("local:abc"))
    }
}
