package com.spotifry.music.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.AppDatabase_Impl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs only when SPOTIFRY_BACKUP points at a real backup file (a phone's, say). The file is private: keep it out of git.
 * Reads it, restores it into an empty desktop database, writes a new backup and checks nothing was lost or changed.
 */
class RealBackupTest {
    @Test fun aRealBackupSurvivesTheDesktop() = runBlocking {
        val path = System.getenv("SPOTIFRY_BACKUP")
        assumeTrue("SPOTIFRY_BACKUP not set", path != null)
        val original = readBackup(File(path!!).inputStream())
        println("read: ${original.summary()}, ${original.history.size} history, ${original.plays.size} plays, ${original.listens.size} listens, ${original.settings.size} settings, ${original.notes.size} notes")

        val db = Room.inMemoryDatabaseBuilder<AppDatabase>(factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val app = MemoryPrefs(); val trims = MemoryPrefs()
            val queue = RestoreQueue(MemoryPrefs(), db.songs(), save = {}, metered = { false })
            val backups = Backups(db.backup(), app, trims, queue, original.appVersion, now = { original.createdAt })
            val skipped = backups.restore(original)
            val again = ByteArrayOutputStream().also { writeBackup(backups.build(), it) }.toByteArray().let { readBackup(it.inputStream()) }

            fun BackupFile.plain() = copy(songs = songs.map { it.copy(savedAt = null) })
            // history is capped at the latest 20 by the app itself, everything else must come back as it was
            assertEquals(original.plain().copy(history = emptyList()).encode().length, again.plain().copy(history = emptyList()).encode().length)
            assertEquals(original.songs.map { it.id }.toSet(), again.songs.map { it.id }.toSet())
            assertEquals(original.playlists.map { it.name to it.songs.map { s -> s.songId } }, again.playlists.map { it.name to it.songs.map { s -> s.songId } })
            assertEquals(original.plays.toSet(), again.plays.toSet())
            assertEquals(original.listens.toSet(), again.listens.toSet())
            assertEquals(original.notes.toSet(), again.notes.toSet())
            assertEquals(original.trims, again.trims)
            assertEquals(original.settings - setOf(FIRST_LAUNCH_SEEN), again.settings - setOf(FIRST_LAUNCH_SEEN))
            println("round trip ok; settings left out as unusable: $skipped; songs waiting for download: ${queue.ids.value.size}")
        } finally { db.close() }
        Unit
    }
}
