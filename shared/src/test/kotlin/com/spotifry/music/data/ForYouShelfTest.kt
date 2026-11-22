package com.spotifry.music.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.AppDatabase_Impl
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset
import kotlin.random.Random

class ForYouShelfTest {
    private lateinit var db: AppDatabase
    private val prefs = MemoryPrefs()
    private val settings = Settings(prefs)
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private var t = 1_800_000_000_000L
    private val notSuggested = mutableSetOf<String>()

    private val relatedMap = mutableMapOf<String, List<Song>>()
    private val searchMap = mutableMapOf<String, List<Song>>()
    private val calls = mutableListOf<String>()
    private var failing = false
    private val source = object : MusicSource {
        override suspend fun search(query: String) = searchMap[query].orEmpty()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("not in tests")
        override suspend fun related(song: Song): List<Song> {
            calls += song.id
            if (failing) throw IOException("offline")
            return relatedMap[song.id].orEmpty()
        }
    }

    private fun shelf() = ForYouShelf(db, source, prefs, settings, { notSuggested.toSet() }, CoroutineScope(Job()), { t }, ZoneOffset.UTC, Random(5))

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }

    @After fun close() = db.close()

    private fun song(id: String, artist: String = "Artist $id") = Song(id, "Song $id", artist, 200, null)

    private suspend fun listen(id: String, at: Long, seconds: Int = 200, skipped: Boolean = false) {
        db.songs().upsert(song(id).toEntity())
        db.listens().insert(ListenEntity(at, id, seconds, skipped))
    }

    /** [n] songs h0.. heard in full two days ago, each with 10 similar songs nobody heard. */
    private suspend fun history(n: Int = 12) = (0 until n).forEach { i ->
        listen("h$i", t - 2 * day + i)
        relatedMap["h$i"] = (0 until 10).map { song("n$i-$it") }
    }

    private fun stored() = prefs.getString(Settings.FOR_YOU, null)

    @Test fun buildsAndStoresAList() = runBlocking {
        history()
        val a = shelf()
        assertTrue(a.rebuildIfDue())
        val ids = a.shown.value!!.map { it.id }
        assertEquals(24, ids.size)
        assertEquals(18, ids.count { it.startsWith("n") })
        assertNotNull(stored())
        val b = shelf(); b.loadStored()
        assertEquals(ids, b.shown.value!!.map { it.id })
    }

    @Test fun notDueWithinAMinuteOrWithoutChange() = runBlocking {
        history()
        val s = shelf()
        assertTrue(s.rebuildIfDue())
        assertFalse(s.rebuildIfDue())
        t += 10 * minute
        assertFalse(s.rebuildIfDue())
        t += 21 * minute
        assertTrue(s.rebuildIfDue())
    }

    @Test fun twoSkipsOrFourPlaysMakeItDue() = runBlocking {
        history()
        val s = shelf()
        assertTrue(s.rebuildIfDue())
        t += 2 * minute
        listen("x1", t - minute, 5, true)
        assertFalse(s.rebuildIfDue())
        listen("x2", t - minute + 1, 5, true)
        assertTrue(s.rebuildIfDue())
        t += 2 * minute
        (1..3).forEach { listen("p$it", t - minute + it) }
        assertFalse(s.rebuildIfDue())
        listen("p4", t - minute + 4)
        assertTrue(s.rebuildIfDue())
    }

    @Test fun usesCachedRelatedUnder7Days() = runBlocking {
        history()
        val s = shelf()
        s.rebuildIfDue()
        assertEquals(8, calls.size)
        t += 31 * minute
        assertTrue(s.rebuildIfDue())
        // a seed asked for in the last 7 days is never asked for again
        assertEquals(calls.size, calls.toSet().size)
        t += 8 * day
        assertTrue(s.rebuildIfDue())
        assertTrue(calls.size > calls.toSet().size)
        failing = true
        t += 8 * day
        assertTrue(s.rebuildIfDue())
        assertEquals(18, s.shown.value!!.count { it.id.startsWith("n") })
    }

    @Test fun offlineBuildDoesNotReplaceAGoodList() = runBlocking {
        history()
        val s = shelf()
        s.rebuildIfDue()
        val before = s.shown.value!!.map { it.id }
        val kept = stored()
        (0 until 12).forEach { db.related().replace("h$it", emptyList()) }
        failing = true
        t += 31 * minute
        s.rebuildIfDue()
        assertEquals(before, s.shown.value!!.map { it.id })
        assertEquals(kept, stored())
    }

    @Test fun clearingHistoryHidesTheShelf() = runBlocking {
        history()
        val s = shelf()
        s.rebuildIfDue()
        db.listens().clear()
        s.onListensChanged(0)
        assertEquals(emptyList<Song>(), s.shown.value)
        assertNull(stored())
    }

    @Test fun dontSuggestDropsTheSongNowAndLater() = runBlocking {
        history()
        val s = shelf()
        s.rebuildIfDue()
        val id = s.shown.value!!.first { it.id.startsWith("n") }.id
        notSuggested += id
        s.dontSuggest(id)
        assertFalse(s.shown.value!!.any { it.id == id })
        assertFalse(stored()!!.contains(id))
        repeat(5) {
            t += 31 * minute
            s.rebuildIfDue()
            assertFalse(s.shown.value!!.any { it.id == id })
        }
    }

    @Test fun littleHistoryUsesSavedAndPickedArtists() = runBlocking {
        (0 until 3).forEach { listen("h$it", t - 2 * day + it) }
        (0 until 5).forEach { i ->
            db.songs().upsert(song("s$i").toEntity()); db.songs().markSaved("s$i", "/f/s$i", null, 1)
            relatedMap["s$i"] = (0 until 10).map { song("n$i-$it") }
        }
        settings.setArtists(listOf(PickedArtist("Zed", "https://music.youtube.com/channel/z", null)))
        searchMap["Zed"] = listOf(song("z1", "Zed"))
        relatedMap["z1"] = (0 until 10).map { song("nz-$it") }
        val s = shelf()
        s.rebuildIfDue()
        assertTrue(calls.any { it.startsWith("s") })
        assertTrue(s.shown.value!!.isNotEmpty())
        // a seed's similar songs are looked up once, so every seed ever drawn shows up in calls
        repeat(10) { if ("z1" in calls) return@runBlocking; t += 31 * minute; s.rebuildIfDue() }
        error("the picked artist's song was never a seed")
    }

    @Test fun tooLittleOfAnythingHidesTheShelf() = runBlocking {
        (0 until 2).forEach { listen("h$it", t - 2 * day + it) }
        val s = shelf()
        s.rebuildIfDue()
        assertEquals(emptyList<Song>(), s.shown.value)
    }

    @Test fun localSeedWithoutMatchGivesNoCandidates() = runBlocking {
        history()
        relatedMap["h0"] = emptyList()
        val s = shelf()
        s.rebuildIfDue()
        assertEquals(24, s.shown.value!!.size)
    }

    // ---- what is shown while Home is open

    private val listA = listOf(song("a"))
    private val listB = listOf(song("b"))

    @Test fun touchedRowWaitsUntilHomeIsLeft() {
        val v = ShelfView()
        v.offer(listA)
        v.homeVisible(true); v.touched()
        v.offer(listB)
        assertEquals(listA, v.shown.value)
        v.homeVisible(false)
        assertEquals(listB, v.shown.value)
    }

    @Test fun untouchedRowSwapsWhileVisible() {
        val v = ShelfView()
        v.offer(listA)
        v.homeVisible(true)
        v.offer(listB)
        assertEquals(listB, v.shown.value)
    }

    @Test fun leavingHomeForgetsTheTouch() {
        val v = ShelfView()
        v.homeVisible(true); v.touched(); v.homeVisible(false); v.homeVisible(true)
        v.offer(listB)
        assertEquals(listB, v.shown.value)
    }
}
