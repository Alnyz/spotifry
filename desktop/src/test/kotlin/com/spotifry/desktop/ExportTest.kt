package com.spotifry.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ExportTest {
    @Test fun nameIsArtistDashTitleWithTheFileExtension() = assertEquals("Queen - Bohemian Rhapsody.flac", exportName("Bohemian Rhapsody", "Queen", "flac"))
    @Test fun charactersAFileSystemRefusesBecomeUnderscores() = assertEquals("AC_DC - Back In Black_ Live.m4a", exportName("Back In Black: Live", "AC/DC", "m4a"))
    @Test fun topicSuffixAndBlankArtistAreLeftOut() {
        assertEquals("Queen - Song.webm", exportName("Song", "Queen - Topic", "webm"))
        assertEquals("Song.webm", exportName("Song", "", "webm"))
    }
    @Test fun webmBecomesOpusAndOthersKeepTheirExtension() {
        assertEquals("opus", exportExtension("webm"))
        assertEquals("flac", exportExtension("FLAC"))
    }
    @Test fun aJpegOrPngBecomesAPictureBlockAndAnythingElseIsLeftOut() {
        assertNotNull(pictureBlock(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)))
        assertNotNull(pictureBlock(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)))
        assertNull(pictureBlock("RIFFxxxxWEBP".toByteArray()))
    }

    private fun song(dir: File) = File(dir, "abc.flac").apply { writeBytes(ByteArray(1000) { it.toByte() }) }

    @Test fun withoutFfmpegItCopiesIntoTheSpotifryFolderOfMusic() {
        val dir = createTempDir("export")
        val out = exportToMusic(File(dir, "Music"), song(dir), "Song", "Queen", ffmpeg = null)!!
        assertEquals(File(dir, "Music/Spotifry/Queen - Song.flac"), out)
        assertArrayEquals(song(dir).readBytes(), out.readBytes())
        assertEquals(listOf("Queen - Song.flac"), out.parentFile.list()!!.toList()) // no .part left behind
        dir.deleteRecursively()
    }

    @Test fun copyingTheSameSongAgainKeepsTheOneFile() {
        val dir = createTempDir("export")
        val music = File(dir, "Music")
        val first = exportToMusic(music, song(dir), "Song", "Queen", ffmpeg = null)!!
        val again = exportToMusic(music, song(dir), "Song", "Queen", ffmpeg = null)!!
        assertEquals(first, again)
        assertEquals(listOf("Queen - Song.flac"), first.parentFile.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun aFileThatIsNotAudioStillCopiesAsItIs() {
        // ffmpeg cannot read these 1000 made-up bytes: the copy falls back to the plain file and leaves nothing else behind
        val ffmpeg = Platform.find("ffmpeg")
        assumeTrue(ffmpeg != null)
        val dir = createTempDir("export")
        val out = exportToMusic(File(dir, "Music"), song(dir), "Song", "Queen", ffmpeg = ffmpeg)!!
        assertArrayEquals(song(dir).readBytes(), out.readBytes())
        assertEquals(listOf("Queen - Song.flac"), out.parentFile.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun aWebmBecomesAnOpusFileWithItsTagsAndCover() {
        val ffmpeg = Platform.find("ffmpeg")
        val ffprobe = Platform.find("ffprobe")
        assumeTrue(ffmpeg != null && ffprobe != null)
        val dir = createTempDir("export")
        val webm = File(dir, "abc.webm")
        fun sh(vararg a: String) = ProcessBuilder(*a).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        assumeTrue(sh(ffmpeg!!, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=1", "-c:a", "libopus", webm.path) == 0)
        val cover = File(dir, "abc.jpg")
        assumeTrue(sh(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=64x64", "-frames:v", "1", cover.path) == 0)
        val out = exportToMusic(File(dir, "Music"), webm, "Song", "Queen", cover, ffmpeg)!!
        assertEquals("Queen - Song.opus", out.name)
        val probe = ProcessBuilder(ffprobe!!, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", out.path).start().inputStream.bufferedReader().readText()
        assertTrue(probe, probe.contains("audio") && probe.contains("video")) // the video stream is the cover
        assertEquals(listOf("Queen - Song.opus"), out.parentFile.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun aMissingFileGivesNull() {
        val dir = createTempDir("export")
        assertNull(exportToMusic(File(dir, "Music"), File(dir, "gone.flac"), "Song", "Queen", ffmpeg = null))
        dir.deleteRecursively()
    }
}
