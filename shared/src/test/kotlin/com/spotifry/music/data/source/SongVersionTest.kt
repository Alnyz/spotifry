package com.spotifry.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongVersionTest {
    private val video = Song("fKVgm3_x-OI", "Denny Caknan X Happy Asmara - SATRU (Official Music Video)", "DENNY CAKNAN", 332, null)

    @Test fun musicVideosAreToldApartFromSongs() {
        assertTrue(looksLikeVideo(video))
        assertFalse(looksLikeVideo(Song("a", "Satru (feat. Happy Asmara)", "Denny Caknan", 311, null)))
        assertFalse(looksLikeVideo(Song("b", "Kartonyono Medot Janji", "Denny Caknan - Topic", 290, null)))
    }

    @Test fun theSongVersionIsPickedNotTheSequel() {
        val results = listOf(
            Song("s2", "Satru 2", "Denny Caknan, Happy Asmara", 289, null),
            Song("s1", "Satru (feat. Happy Asmara)", "Denny Caknan", 311, null),
        )
        assertEquals("s1", songVersionOf(video, results)?.id)
    }

    @Test fun anotherArtistsSongOfTheSameNameIsNotTaken() {
        assertNull(songVersionOf(video, listOf(Song("x", "Satru", "Someone Else", 300, null))))
    }

    @Test fun theQueryIsTheArtistAndTheSongName() {
        assertEquals("DENNY CAKNAN SATRU", songQuery(video))
    }

    @Test fun aFeatInTheArtistPartKeepsTheSongName() {
        assertEquals("WIDODARI", com.spotifry.music.data.titleForLyrics("DENNY CAKNAN feat. GUYON WATON - WIDODARI (Official Music Video)", "DENNY CAKNAN"))
        assertEquals("Kalih Welasku", com.spotifry.music.data.titleForLyrics("Denny Caknan - Kalih Welasku (Official Music Video) #albumkalihwelasku", "DENNY CAKNAN"))
        assertEquals("Satru", com.spotifry.music.data.titleForLyrics("Satru feat. Happy Asmara", "Denny Caknan"))
        assertEquals("KISINAN 2", com.spotifry.music.data.titleForLyrics("KISINAN 2 - MASDDDHO (OFFICIAL MUSIC VIDEO)", "MASDDDHO"))
    }
}
