package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun song(id: String) = SongEntity(id, "Title $id", "Artist", 200, null, null, null, null)
    private suspend fun add(vararg ids: String) = ids.forEach { db.songs().upsert(song(it)) }

    @Test fun historyKeepsNewest20() = runBlocking {
        add(*(1..25).map { "s$it" }.toTypedArray())
        (1..25).forEach { db.history().record("s$it", it.toLong()) }
        val recent = db.history().observeRecent().first()
        assertEquals(20, recent.size)
        assertEquals("s25", recent.first().id)
        assertEquals("s6", recent.last().id)
    }

    @Test fun savedSortedNewestFirst() = runBlocking {
        add("a", "b", "c")
        db.songs().markSaved("a", "/f/a", null, 100)
        db.songs().markSaved("c", "/f/c", null, 300)
        db.songs().markSaved("b", "/f/b", null, 200)
        assertEquals(listOf("c", "b", "a"), db.songs().observeSaved().first().map { it.id })
    }

    @Test fun playlistCountsShowSavedSubset() = runBlocking {
        add("a", "b", "c")
        db.songs().markSaved("a", "/f/a", null, 1)
        db.songs().markSaved("b", "/f/b", null, 2)
        val id = db.playlists().create("Mix")
        listOf("a", "b", "c").forEach { db.playlists().addSong(id, it) }
        val p = db.playlists().observePlaylists().first().single()
        assertEquals(3, p.songCount)
        assertEquals(2, p.savedCount)
        assertEquals(1, p.number)
    }

    @Test fun songInTwoPlaylists() = runBlocking {
        add("a")
        val p1 = db.playlists().create("One")
        val p2 = db.playlists().create("Two")
        db.playlists().addSong(p1, "a")
        db.playlists().addSong(p2, "a")
        assertEquals(listOf("a"), db.playlists().observeSongs(p1).first().map { it.id })
        assertEquals(listOf("a"), db.playlists().observeSongs(p2).first().map { it.id })
        assertEquals(listOf(1, 2), db.playlists().observePlaylists().first().map { it.number })
    }

    @Test fun deletePlaylistKeepsSongs() = runBlocking {
        add("a")
        val p = db.playlists().create("Gone")
        db.playlists().addSong(p, "a")
        db.playlists().delete(p)
        assertEquals(0, db.playlists().observePlaylists().first().size)
        assertNotNull(db.songs().get("a"))
    }

    @Test fun upsertKeepsSavedInfo() = runBlocking {
        add("a")
        db.songs().markSaved("a", "/f/a", "/t/a", 5)
        db.songs().upsert(song("a").copy(title = "New title"))
        val s = db.songs().get("a")!!
        assertEquals("New title", s.title)
        assertEquals("/f/a", s.filePath)
        db.songs().clearSaved("a")
        assertNull(db.songs().get("a")!!.filePath)
    }
}
