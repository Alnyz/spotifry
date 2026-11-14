package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportTest {
    @Test fun nameIsArtistDashTitleWithTheFileExtension() = assertEquals("Queen - Bohemian Rhapsody.flac", exportName("Bohemian Rhapsody", "Queen", "flac"))
    @Test fun charactersAndroidRefusesInNamesBecomeUnderscores() = assertEquals("AC_DC - Back In Black_ Live.m4a", exportName("Back In Black: Live", "AC/DC", "m4a"))
    @Test fun topicSuffixAndBlankArtistAreLeftOut() {
        assertEquals("Queen - Song.webm", exportName("Song", "Queen - Topic", "webm"))
        assertEquals("Song.webm", exportName("Song", "", "webm"))
    }
    @Test fun mimeFollowsTheExtension() {
        assertEquals("audio/flac", exportMime("flac"))
        assertEquals("audio/webm", exportMime("webm"))
        assertEquals("audio/mp4", exportMime("m4a"))
    }
}
