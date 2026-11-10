package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.SongYearEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongYearDbTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    @Test fun putThenReadBack() = runBlocking {
        db.songYears().put(SongYearEntity("a", 1999, 5))
        db.songYears().put(SongYearEntity("b", null, 6))
        assertEquals(listOf(SongYearEntity("a", 1999, 5), SongYearEntity("b", null, 6)), db.songYears().observeAll().first().sortedBy { it.songId })
    }

    @Test fun playCountsOnlyListsSongsThatHaveARow() = runBlocking {
        db.songs().upsert(com.spotifry.music.data.db.SongEntity("a", "T", "A", 100, null, null, null, null))
        db.listens().insert(ListenEntity(1L, "a", 100))
        db.listens().insert(ListenEntity(2L, "gone", 100))
        assertEquals(listOf("a"), db.listens().playCounts().map { it.songId })
    }

    @Test fun putReplacesAnEarlierRow() = runBlocking {
        db.songYears().put(SongYearEntity("a", null, 5))
        db.songYears().put(SongYearEntity("a", 2001, 9))
        assertEquals(listOf(SongYearEntity("a", 2001, 9)), db.songYears().all())
    }

    @Test fun migrationKeepsListensAndAddsTheTable() = runBlocking {
        db.listens().insert(ListenEntity(1L, "s", 100))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE song_years")
        AppDatabase.ADD_SONG_YEARS.migrate(sql)
        db.songYears().put(SongYearEntity("a", 2001, 9))
        assertEquals(1, db.songYears().all().size)
        assertEquals("s", db.listens().observeAll().first().single().songId)
    }
}
