package com.spotifry.music.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.ListenWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenLogTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()

    @Test fun updatingFromThreeKeepsEverythingAndStartsAnEmptyLog() = runBlocking {
        val file = File.createTempFile("from3", ".db", ctx.cacheDir).also { it.delete() }
        val v3 = JSONObject(File("schemas/com.spotifry.music.data.db.AppDatabase/3.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val tables = v3.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val t = tables.getJSONObject(i)
                old.execSQL(t.getString("createSql").replace("\${TABLE_NAME}", t.getString("tableName")))
            }
            val setup = v3.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO songs (id, title, artist, durationSec) VALUES ('a', 'Old song', 'x', 1)")
            old.execSQL("INSERT INTO plays (songId, count, lastPlayedAt) VALUES ('a', 3, 5)")
            old.version = 3
        }
        // Room checks the table against the schema when it opens, so a migration that doesn't match fails here
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .addMigrations(AppDatabase.ADD_PLAYS, AppDatabase.ADD_ADDED_AT, AppDatabase.ADD_LISTENS, AppDatabase.ADD_SONG_YEARS).allowMainThreadQueries().build()
        assertEquals("Old song", db.songs().get("a")?.title)
        assertEquals(listOf("a"), db.plays().observeMostPlayed(5).first().map { it.id })
        assertTrue(db.listens().observeAll().first().isEmpty())
        assertFalse(db.listens().observeAny().first())
        db.listens().insert(ListenEntity(1, "a", 30))
        assertEquals("Old song", db.listens().observeAll().first().single().song?.title)
        db.close()
    }

    @Test fun oldLinesKeepTheirNameAfterHistoryAndSavesAreCleared() = runBlocking {
        val db = memory()
        db.songs().upsert(Song("a", "Name", "Who", 200, "art").toEntity())
        db.songs().markSaved("a", "/f", "/t", 1)
        db.listens().insert(ListenEntity(1, "a", 90))
        db.history().record("a", 1)
        db.history().clear(); db.plays().clear(); db.songs().clearSaved("a")
        val row = db.listens().observeAll().first().single()
        assertEquals("Name", row.song?.title)
        assertEquals("Who", row.song?.artist)
        db.close()
    }

    @Test fun aLineWithNoSongRowIsStillReadWithoutASong() = runBlocking {
        val db = memory()
        db.listens().insert(ListenEntity(1, "gone", 90))
        val row = db.listens().observeAll().first().single()
        assertNull(row.song)
        assertEquals(90, row.secondsHeard)
        db.close()
    }

    @Test fun afterClearingARunningSongIsNotBroughtBack() = runBlocking {
        val db = memory()
        db.listens().insert(ListenEntity(1, "a", 90))
        db.listens().clear()
        db.listens().update(1, 100, false)
        assertTrue(db.listens().observeAll().first().isEmpty())
        db.close()
    }

    @Test fun writesLandInOrder() = runBlocking {
        val db = memory()
        val scope = CoroutineScope(Dispatchers.IO)
        val log = ListenLog(db.listens(), scope)
        log.write(ListenWrite.New(ListenEntity(1, "a", 1)))
        log.write((2..50).map { ListenWrite.Heard(1, it, false) })
        log.write(ListenWrite.Heard(1, 100, true))
        log.write(null)
        val row = withTimeout(5_000) {
            var r = db.listens().observeAll().first().firstOrNull()
            while (r == null || r.secondsHeard != 100) { kotlinx.coroutines.delay(10); r = db.listens().observeAll().first().firstOrNull() }
            r
        }
        assertNotNull(row)
        assertTrue(row!!.skipped)
        scope.cancel()
        db.close()
    }
}
