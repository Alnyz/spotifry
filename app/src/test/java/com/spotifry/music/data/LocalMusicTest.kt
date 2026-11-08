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

    @Test fun nonMusicIsTold() {
        val song = AudioRow(200_000, "audio/mpeg", "/storage/emulated/0/Music/a.mp3", system = false, recording = false)
        assertTrue(!song.isNonMusic())
        assertTrue(song.copy(durationMs = 29_999).isNonMusic())
        assertTrue(song.copy(mime = "audio/x-wav").isNonMusic())
        assertTrue(song.copy(mime = null, path = "/sdcard/Music/a.WAV").isNonMusic())
        assertTrue(song.copy(system = true).isNonMusic())
        assertTrue(song.copy(recording = true).isNonMusic())
        assertTrue(song.copy(path = "/storage/emulated/0/Recordings/Voice Recorder/x.m4a").isNonMusic())
        assertTrue(!song.copy(path = "/storage/emulated/0/Music/Voices of Spring.mp3").isNonMusic())
    }
}
