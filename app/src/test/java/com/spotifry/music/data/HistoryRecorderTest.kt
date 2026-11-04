package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
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
class HistoryRecorderTest {
    private lateinit var db: AppDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun anySongThatStartsPlayingLandsInRecentlyPlayed() = runBlocking<Unit> {
        val rec = HistoryRecorder(db.history(), db.songs()) { 42L }
        rec.onPlayed(testSong) // as if reached by auto-advance, with no row in songs yet
        val recent = db.history().observeRecent().first()
        assertEquals(listOf(testSong.id), recent.map { it.id })
        assertEquals("Title", recent.single().title)
    }
}
