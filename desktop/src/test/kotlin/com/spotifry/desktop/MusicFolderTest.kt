package com.spotifry.desktop

import com.spotifry.music.data.MemoryPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class MusicFolderTest {
    @Test fun readsTagsNamesAndHidesClips(): Unit = runBlocking {
        val dir = makeSongs()
        val hide = MutableStateFlow(true)
        val folder = MusicFolder(File(dir, ".cache.json"), MemoryPrefs(), hide).apply { choose(dir) }
        folder.refresh()
        val songs = settled(folder, 4)
        assertEquals(listOf("Hati-Hati di Jalan", "Kala Biru", "Morning Light", "Sore Hari"), songs.map { it.title })
        assertEquals(listOf("Tulus", "Hindia", "Nadin Amizah", "Barasuara"), songs.map { it.artist })
        assertEquals(184, songs[1].durationSec)
        assertTrue(songs.all { it.id == "local:" + File(dir, it.id.substringAfter(dir.path + "/")).path })
        assertTrue(songs[1].thumbnailUrl!!.endsWith("Album A/cover.jpg"))
        assertTrue(songs[3].thumbnailUrl!!.endsWith("Loose/sore.mp3"))
        assertTrue(MusicFolder.art(songs[3].thumbnailUrl!!.removePrefix("file://"))!!.isNotEmpty())
        hide.value = false
        settled(folder, 6)
        // read again from the cache: same answer
        val again = MusicFolder(File(dir, ".cache.json"), MemoryPrefs(), MutableStateFlow(true)).apply { choose(dir) }
        again.refresh()
        assertEquals(songs, settled(again, 4))
    }

    @Test fun namesWithoutTags() {
        assertEquals("Tulus" to "Hati", MusicFolder.nameParts("04 Tulus - Hati"))
        assertEquals(null to "beach mix", MusicFolder.nameParts("beach mix"))
    }

    /** The list once it holds [n] songs: it follows the read a moment later. */
    private suspend fun settled(f: MusicFolder, n: Int) = kotlinx.coroutines.withTimeoutOrNull(5000) { f.songs.first { it.size == n } } ?: f.songs.value

    companion object {
        /** A small music folder made with ffmpeg: tagged songs, a cover, a picture inside a song, a clip and a voice memo. */
        fun makeSongs(): File {
            assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
            val dir = Files.createTempDirectory("spotifry-music").toFile()
            fun ff(vararg a: String) = ProcessBuilder(listOf("ffmpeg", "-v", "quiet", "-y") + a).directory(dir).start().waitFor()
            listOf("Album A", "Loose", "Voice").forEach { File(dir, it).mkdirs() }
            ff("-f", "lavfi", "-i", "color=c=orange:s=600x600", "-frames:v", "1", "Album A/cover.jpg")
            ff("-f", "lavfi", "-i", "color=c=teal:s=500x500", "-frames:v", "1", ".art.png")
            ff("-f", "lavfi", "-i", "sine=f=440:d=95", "-metadata", "title=Morning Light", "-metadata", "artist=Nadin Amizah", "Album A/01 morning.mp3")
            ff("-f", "lavfi", "-i", "sine=f=330:d=184", "-metadata", "TITLE=Kala Biru", "-metadata", "ARTIST=Hindia", "Album A/02.flac")
            ff("-f", "lavfi", "-i", "sine=f=550:d=130", "-i", ".art.png", "-map", "0:a", "-map", "1", "-c:v", "png", "-disposition:v", "attached_pic", "-metadata", "title=Sore Hari", "-metadata", "artist=Barasuara", "Loose/sore.mp3")
            ff("-f", "lavfi", "-i", "sine=f=220:d=70", "-c:a", "libopus", "Loose/04 Tulus - Hati-Hati di Jalan.opus")
            ff("-f", "lavfi", "-i", "sine=f=660:d=8", "Loose/ding.mp3")
            ff("-f", "lavfi", "-i", "sine=f=200:d=60", "Voice/memo 1.m4a")
            return dir
        }
    }
}
