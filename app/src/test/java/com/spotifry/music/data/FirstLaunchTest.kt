package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private suspend fun fresh() = isFreshInstall(db.history(), db.songs(), db.playlists())

    @Test fun emptyPhoneIsAFreshInstall() = runBlocking { assertTrue(fresh()) }

    @Test fun anUpdatedPhoneWithHistoryIsNot() = runBlocking {
        db.songs().upsert(testSong.toEntity())
        db.history().record(testSong.id, 1L)
        assertFalse(fresh())
    }

    @Test fun anUpdatedPhoneWithOnlyAPlaylistIsNot() = runBlocking {
        db.playlists().create("chill")
        assertFalse(fresh())
    }

    @Test fun anUpdatedPhoneWithOnlySavedSongsIsNot() = runBlocking {
        db.songs().upsert(testSong.toEntity())
        db.songs().markSaved(testSong.id, "/f", "/t", 1L)
        assertFalse(fresh())
    }

    @Test fun seenFlagIsOffUntilSetAndSurvivesARestart() {
        val prefs = context.getSharedPreferences("seen", 0)
        assertFalse(Settings(prefs).firstLaunchSeen.value)
        Settings(prefs).markFirstLaunchSeen()
        assertTrue(Settings(prefs).firstLaunchSeen.value)
    }

    @Test fun pickedArtistsSurviveARestartInOrderWithOrWithoutAPage() {
        val prefs = context.getSharedPreferences("artists", 0)
        val list = listOf(PickedArtist("Tulus", "https://music.youtube.com/channel/UC1", "https://img/t"), PickedArtist("Raisa", "", null))
        Settings(prefs).setArtists(list)
        assertEquals(list, Settings(prefs).artists.value)
        Settings(prefs).setArtists(emptyList())
        assertEquals(emptyList<PickedArtist>(), Settings(prefs).artists.value)
    }

    @Test fun theStarterListHasIndonesianArtistsAndNoDuplicates() {
        assertTrue(listOf("Tulus", "Raisa", "Hindia", "Nadin Amizah").all { it in STARTER_ARTISTS })
        assertEquals(STARTER_ARTISTS.size, STARTER_ARTISTS.map { it.lowercase() }.toSet().size)
    }
}
