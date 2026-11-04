package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextTest {
    private val a = Song("dQ7xk2LmPa0", "Paper Lanterns", "Mira Vale", 200, null)
    private val b = Song("Hk2p9QvLm3s", "Rock – Live", "The Pines", 200, null)
    private val file = Song("local:5", "beach-mix-final", "Unknown artist", 60, null)

    @Test fun playlistRoundTrip() {
        val text = ShareText.playlist("Road trip", listOf(a, file, b))
        assertEquals(
            "Road trip · 3 songs, shared from Spotifry\n\nPaper Lanterns – Mira Vale\nyoutu.be/dQ7xk2LmPa0\n" +
                "beach-mix-final – Unknown artist\n(a file on my phone, no link)\nRock – Live – The Pines\nyoutu.be/Hk2p9QvLm3s",
            text,
        )
        val got = ShareText.read(text)!!
        assertEquals("Road trip", got.name)
        assertEquals(1, got.phoneFiles)
        assertEquals(listOf("dQ7xk2LmPa0" to "Paper Lanterns", "Hk2p9QvLm3s" to "Rock – Live"), got.songs.map { it.id to it.title })
        assertEquals(listOf("Mira Vale", "The Pines"), got.songs.map { it.artist })
    }

    @Test fun olderNumberedListsStillRead() {
        val old = "Road trip · 1 song, shared from Spotifry\n\n1. Paper Lanterns – Mira Vale\nhttps://youtu.be/dQ7xk2LmPa0"
        assertEquals(listOf("Paper Lanterns"), ShareText.read(old)!!.songs.map { it.title })
    }

    @Test fun oneSongAndOtherLinkShapes() {
        val got = ShareText.read(ShareText.song(a))!!
        assertNull(got.name)
        assertEquals("Paper Lanterns", got.title)
        assertEquals(listOf("dQ7xk2LmPa0"), got.songs.map { it.id })
        val other = ShareText.read("Song A – X\nhttps://music.youtube.com/watch?v=dQ7xk2LmPa0&si=abc\nSong B\nhttps://www.youtube.com/watch?v=Hk2p9QvLm3s")!!
        assertEquals(listOf("Song A" to "X", "Song B" to ""), other.songs.map { it.title to it.artist })
        assertEquals("Shared songs", other.title)
    }

    @Test fun bareLinksAndNoiseAreSkipped() {
        assertNull(ShareText.read("https://youtu.be/dQ7xk2LmPa0"))
        assertNull(ShareText.read("hello there"))
    }

    @Test fun takenNamesGetANumber() {
        assertEquals("Road trip", ShareText.freeName("Road trip", listOf("Chill")))
        assertEquals("Road trip (3)", ShareText.freeName("Road trip", listOf("Road trip", "Road trip (2)")))
    }
}
