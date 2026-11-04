package com.spotifry.music.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistOrderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun song(id: String, title: String, artist: String) = SongEntity(id, title, artist, 200, null, null, null, null)

    @Test fun sortsByNameAndArtist() {
        val list = listOf(song("1", "beta", "Zed"), song("2", "Alpha", "amy"), song("3", "gamma", "Amy"))
        assertEquals(listOf("2", "1", "3"), PlaylistOrder.TITLE.arrange(list).map { it.id })
        assertEquals(listOf("2", "3", "1"), PlaylistOrder.ARTIST.arrange(list).map { it.id })
        assertEquals(listOf("1", "2", "3"), PlaylistOrder.CUSTOM.arrange(list).map { it.id })
    }

    @Test fun searchMatchesEveryWordInNameOrArtist() {
        val s = song("1", "Love in Low Light", "Kite Harbor")
        assertTrue(s.matches("love"))
        assertTrue(s.matches("LOW kite"))
        assertFalse(s.matches("love ocean"))
    }

    @Test fun handMadeOrderAndNewestFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val p = db.playlists()
        val id = p.create("chill")
        listOf("a", "b", "c").forEach { db.songs().upsert(Song(it, it, "x", 1, null).toEntity()); p.addSong(id, it); Thread.sleep(2) }
        p.setOrder(id, listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), p.observeSongs(id).first().map { it.id })
        // newest first follows when songs were added, not the hand-made order
        assertEquals(listOf("c", "b", "a"), p.observeSongsNewestFirst(id).first().map { it.id })
        p.addSong(id, "a") // already there: nothing changes
        assertEquals(3, p.observeSongs(id).first().size)
        db.close()
    }

    /** A phone on version 0.1.0 keeps its playlists, and "Recently added" follows the old order. */
    @Test fun upgradeFromVersion2KeepsPlaylists() = runBlocking {
        val file = File(ctx.cacheDir, "upgrade-test.db").apply { delete() }
        val v2 = JSONObject(File("schemas/com.spotifry.music.data.db.AppDatabase/2.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val tables = v2.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val t = tables.getJSONObject(i)
                old.execSQL(t.getString("createSql").replace("\${TABLE_NAME}", t.getString("tableName")))
            }
            val setup = v2.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'chill', 0)")
            listOf("a", "b", "c").forEachIndexed { pos, s ->
                old.execSQL("INSERT INTO songs (id, title, artist, durationSec) VALUES ('$s', '$s', 'x', 1)")
                old.execSQL("INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, '$s', $pos)")
            }
            old.version = 2
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .addMigrations(AppDatabase.ADD_PLAYS, AppDatabase.ADD_ADDED_AT).allowMainThreadQueries().build()
        assertEquals(listOf("a", "b", "c"), db.playlists().observeSongs(1).first().map { it.id })
        assertEquals(listOf("c", "b", "a"), db.playlists().observeSongsNewestFirst(1).first().map { it.id })
        db.close()
    }
}
