package com.spotifry.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ExportTest {
    @Test fun nameIsArtistDashTitleWithTheFileExtension() = assertEquals("Queen - Bohemian Rhapsody.flac", exportName("Bohemian Rhapsody", "Queen", "flac"))
    @Test fun charactersAFileSystemRefusesBecomeUnderscores() = assertEquals("AC_DC - Back In Black_ Live.m4a", exportName("Back In Black: Live", "AC/DC", "m4a"))
    @Test fun topicSuffixAndBlankArtistAreLeftOut() {
        assertEquals("Queen - Song.webm", exportName("Song", "Queen - Topic", "webm"))
        assertEquals("Song.webm", exportName("Song", "", "webm"))
    }

    private fun song(dir: File) = File(dir, "abc.flac").apply { writeBytes(ByteArray(1000) { it.toByte() }) }

    @Test fun copiesIntoTheSpotifryFolderOfMusic() {
        val dir = createTempDir("export")
        val out = exportToMusic(File(dir, "Music"), song(dir), "Song", "Queen")!!
        assertEquals(File(dir, "Music/Spotifry/Queen - Song.flac"), out)
        assertArrayEquals(song(dir).readBytes(), out.readBytes())
        assertEquals(listOf("Queen - Song.flac"), out.parentFile.list()!!.toList()) // no .part left behind
        dir.deleteRecursively()
    }

    @Test fun anotherSongUnderTheSameNameIsNeverOverwritten() {
        val dir = createTempDir("export")
        val music = File(dir, "Music")
        val first = exportToMusic(music, song(dir), "Song", "Queen")!!
        val other = File(dir, "other.flac").apply { writeBytes(ByteArray(500)) }
        val second = exportToMusic(music, other, "Song", "Queen")!!
        assertEquals("Queen - Song (1).flac", second.name)
        assertEquals("Queen - Song.flac", first.name)
        assertEquals(1000, first.length())
        dir.deleteRecursively()
    }

    @Test fun copyingTheSameSongAgainKeepsTheOneFile() {
        val dir = createTempDir("export")
        val music = File(dir, "Music")
        val first = exportToMusic(music, song(dir), "Song", "Queen")!!
        val again = exportToMusic(music, song(dir), "Song", "Queen")!!
        assertEquals(first, again)
        assertEquals(listOf("Queen - Song.flac"), first.parentFile.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun aMissingFileGivesNull() {
        val dir = createTempDir("export")
        assertNull(exportToMusic(File(dir, "Music"), File(dir, "gone.flac"), "Song", "Queen"))
        dir.deleteRecursively()
    }
}
