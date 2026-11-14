package com.spotifry.music.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.AppDatabase_Impl
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A backup made on one device, restored on another and made again, the way a phone and the desktop pass it around. */
class BackupRoundTripTest {
    private val open = mutableListOf<AppDatabase>()
    private val queues = mutableListOf<RestoreQueue>()
    @After fun close() = open.forEach { it.close() }

    private fun device(): Triple<AppDatabase, MemoryPrefs, Backups> {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>(factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also(open::add)
        val app = MemoryPrefs(); val trims = MemoryPrefs()
        val queue = RestoreQueue(MemoryPrefs(), db.songs(), save = {}, metered = { false }).also(queues::add)
        return Triple(db, app, Backups(db.backup(), app, trims, queue, "test", now = { 1L }))
    }

    @Test fun whatOneDeviceSavesAnotherRestoresAndSavesAgainIdentically() = runBlocking {
        val (db1, app1, backups1) = device()
        db1.songs().upsert(Song("a", "Atap Rumah", "Rumah Timur", 190, null).toEntity())
        db1.songs().upsert(Song("b", "Lampu Kota", "Nara Senja", 197, null).toEntity())
        db1.songs().markSaved("a", "/x/a.m4a", null, 50L)
        val id = db1.playlists().create("Road trip"); db1.playlists().addSong(id, "b"); db1.playlists().addSong(id, "a")
        db1.history().record("a", 10L); db1.plays().count("a", 10L)
        app1.edit().putString(Settings.NAME, "Test").putBoolean("data_saver", true).putInt(Settings.FADE, 6).putString("desktop_music_folder", "/home/x/Music").apply()

        val bytes = ByteArrayOutputStream().also { writeBackup(backups1.build(), it) }.toByteArray()
        val (db2, app2, backups2) = device()
        backups2.restore(readBackup(ByteArrayInputStream(bytes)))

        // same library; the only difference is that a song saved on the first device has no file here yet
        fun BackupFile.plain() = copy(settings = emptyMap(), songs = songs.map { it.copy(savedAt = null) }).encode()
        assertEquals(backups1.build().plain(), backups2.build().plain())
        assertEquals(listOf("a"), queues.last().ids.value)
        assertEquals(listOf("b", "a"), db2.playlists().observeSongs(1).first().map { it.id })
        // settings the other device had come along, and the desktop-only ones never left it
        assertEquals(6, app2.getInt(Settings.FADE, 0)); assertTrue(app2.getBoolean("data_saver", false))
        assertFalse(app2.contains("desktop_music_folder"))
        // the saved song has no file here yet: it waits to be downloaded
        assertEquals(null, db2.songs().get("a")!!.filePath)
    }

    @Test fun settingsThisDeviceDoesNotUseSurviveItsOwnBackup() = runBlocking {
        val (_, app, backups) = device()
        app.edit().putBoolean("keep_screen_on", true).putString("some_future_key", "x").apply()
        val b = backups.build()
        assertEquals(true, b.settings["keep_screen_on"]); assertEquals("x", b.settings["some_future_key"])
    }
}
