package com.spotifry.music.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.BackupFile
import com.spotifry.music.data.BackupPlaylist
import com.spotifry.music.data.BackupPlaylistSong
import com.spotifry.music.data.decodeBackup
import com.spotifry.music.data.encode
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The same database the phone uses, opened the way the desktop app opens it: plain Java, bundled SQLite. */
class DesktopDbTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }

    @After fun close() = db.close()

    @Test fun playlistsKeepSongsInOrder() = runBlocking {
        db.songs().upsert(Song("a", "Atap Rumah", "Rumah Timur", 190, null).toEntity())
        db.songs().upsert(Song("b", "Lampu Kota", "Nara Senja", 197, null).toEntity())
        val id = db.playlists().create("Road trip")
        db.playlists().addSong(id, "b"); db.playlists().addSong(id, "a")
        assertEquals(listOf("b", "a"), db.playlists().observeSongs(id).first().map { it.id })
    }

    @Test fun aBackupMergesTwiceWithoutDoubling() = runBlocking {
        val file = BackupFile(
            "0.13.0", 1L, listOf(SongEntity("a", "Atap Rumah", "Rumah Timur", 190, null, 5L, null, null)),
            listOf(BackupPlaylist("Road trip", 1L, null, listOf(BackupPlaylistSong("a", 2L)))),
            emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(),
        ).let { decodeBackup(it.encode()) }
        db.backup().merge(file); db.backup().merge(file)
        assertEquals(1, db.backup().playlists().size)
        assertEquals(1, db.backup().playlistSongs().size)
        assertEquals(1, db.backup().songs().size)
    }
}

/** An older desktop file is upgraded by the steps, the way the phone's older files are. */
class DesktopUpgradeTest {
    @Test fun aFileAtVersionFiveGetsTheNotesTable() = runBlocking {
        val file = java.io.File.createTempFile("music", ".db").also { it.deleteOnExit() }
        fun open() = Room.databaseBuilder<AppDatabase>(file.path, factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).addMigrations(*AppDatabase.driverMigrations()).setQueryCoroutineContext(Dispatchers.IO).build()
        open().apply { songs().upsert(Song("s", "T", "A", 100, null).toEntity()); close() }
        // wind the file back to how version 5 left it
        BundledSQLiteDriver().open(file.path).use { c ->
            c.prepare("DROP TABLE song_notes").use { it.step() }
            c.prepare("PRAGMA user_version = 5").use { it.step() }
        }
        open().apply {
            notes().put(SongNoteEntity("s", "kept", 1))
            assertEquals("kept", notes().observe("s").first())
            assertEquals("T", songs().get("s")!!.title)
            close()
        }
        Unit
    }
}
