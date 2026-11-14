package com.spotifry.desktop

import com.spotifry.music.data.BackupFile
import com.spotifry.music.data.BackupPlaylist
import com.spotifry.music.data.BackupPlaylistSong
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.readBackup
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The restart after a restore: what was restored must be there when the files are opened again. */
class LibraryTest {
    private val source = object : MusicSource {
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun resolve(song: Song, low: Boolean) = ResolvedStream("", "", 0, 0)
        override suspend fun related(song: Song) = emptyList<Song>()
    }
    private fun open(dir: File) = Library.open(dir, OkHttpClient(), StreamResolver(source))

    @Test fun aRestoredBackupIsStillThereAfterReopening() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("spotifry-lib").toFile()
        val backup = BackupFile(
            "0.13.0", 1L, listOf(SongEntity("a", "Atap Rumah", "Rumah Timur", 190, null, 5L, null, null), SongEntity("b", "Lampu Kota", "Nara Senja", 197, null, null, null, null)),
            listOf(BackupPlaylist("Road trip", 1L, null, listOf(BackupPlaylistSong("b", 2L), BackupPlaylistSong("a", 3L)))),
            emptyList(), emptyList(), emptyList(),
            mapOf("user_name" to "Test", "theme" to "LIGHT", "accent" to "BLUE", "fade_seconds" to 6, "speed" to 1.25f, "keep_screen_on" to true), emptyMap(),
        )
        val first = open(dir)
        first.backups.restore(backup)
        first.db.close()

        val again = open(dir)
        assertEquals("Test", again.settings.name.value); assertEquals(ThemeMode.LIGHT, again.settings.theme.value); assertEquals("BLUE", again.settings.accent.value)
        assertEquals(6, again.settings.fadeSeconds.value); assertEquals(1.25f, again.settings.speed.value)
        assertEquals(listOf("b", "a"), again.db.playlists().observeSongs(1).first().map { it.id })
        // the saved song waits for its file
        assertEquals(listOf("a"), again.restoreQueue.ids.value)
        // a setting this app doesn't use comes back out in the next backup
        assertEquals(true, again.backups.build().settings["keep_screen_on"])
        again.db.close()
        Unit
    }

    @Test fun aRealBackupSurvivesBeingWrittenToDiskAndReopened() = runBlocking {
        val path = System.getenv("SPOTIFRY_BACKUP") ?: return@runBlocking
        val original = readBackup(File(path).inputStream())
        val dir = java.nio.file.Files.createTempDirectory("spotifry-real").toFile()
        val first = open(dir); first.backups.restore(original); first.db.close()
        val again = open(dir)
        val back = again.backups.build()
        assertEquals(original.songs.map { it.id }.toSet(), back.songs.map { it.id }.toSet())
        assertEquals(original.playlists.map { it.name to it.songs.size }, back.playlists.map { it.name to it.songs.size })
        assertEquals(original.plays.toSet(), back.plays.toSet()); assertEquals(original.listens.toSet(), back.listens.toSet())
        assertEquals(original.settings.keys - "first_launch_seen", back.settings.keys - "first_launch_seen")
        assertTrue(back.settings.all { (k, v) -> original.settings[k] == v || k == "first_launch_seen" })
        again.db.close()
        Unit
    }
}
