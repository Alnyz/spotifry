package com.spotifry.music.data.spotifried

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongYearDao
import com.spotifry.music.data.db.SongYearEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DAY = 24 * 60 * 60 * 1000L

private class FakeYears : SongYearDao {
    val rows = mutableMapOf<String, SongYearEntity>()
    override suspend fun put(e: SongYearEntity) { rows[e.songId] = e }
    override fun observeAll(): Flow<List<SongYearEntity>> = emptyFlow()
    override suspend fun all() = rows.values.toList()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YearLookupTest {
    private fun song(id: String) = SongEntity(id, "T", "A", 100, null, null, null, null)
    private fun hit(year: String) =
        """{"recordings":[{"title":"T","score":100,"first-release-date":"$year","artist-credit":[{"name":"A","artist":{"name":"A"}}],"releases":[]}]}"""

    @Test fun mostPlayedFirstAndOnlyPlayedSongs() {
        assertEquals(listOf("b", "a"), nextToLookUp(mapOf("a" to 2, "b" to 5, "c" to 0), emptyList(), 0))
    }

    @Test fun skipsSongsAlreadyFound() {
        assertEquals(listOf("b"), nextToLookUp(mapOf("a" to 2, "b" to 1), listOf(SongYearEntity("a", 1999, 0)), 100 * DAY))
    }

    @Test fun retriesNotFoundAfterThreeWeeks() {
        val known = listOf(SongYearEntity("a", null, 0))
        assertEquals(listOf("a"), nextToLookUp(mapOf("a" to 1), known, 21 * DAY + 1))
        assertEquals(emptyList<String>(), nextToLookUp(mapOf("a" to 1), known, 20 * DAY))
    }

    @Test fun aTryLaterStoresNothingAndStops() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)) }
        val dao = FakeYears()
        val mb = MusicBrainz(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t")
        assertFalse(lookUpYears(listOf(song("a"), song("b")), mb, dao, { 5 }, {}))
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun foundAndNotFoundAreStored() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(hit("1975-09-12")))
            enqueue(MockResponse().setBody("""{"recordings":[]}"""))
            enqueue(MockResponse().setBody(hit("1850")))
        }
        val dao = FakeYears()
        val mb = MusicBrainz(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t")
        assertTrue(lookUpYears(listOf(song("a"), song("b"), song("c")), mb, dao, { 7 }, {}))
        assertEquals(SongYearEntity("a", 1975, 7), dao.rows["a"])
        assertEquals(SongYearEntity("b", null, 7), dao.rows["b"])
        assertEquals(SongYearEntity("c", null, 7), dao.rows["c"])
        server.shutdown()
    }

    @Test fun pausesBetweenRequests() = runBlocking {
        val server = MockWebServer().apply { repeat(2) { enqueue(MockResponse().setBody(hit("1990"))) } }
        var pauses = 0
        val mb = MusicBrainz(OkHttpClient(), server.url("/").toString().trimEnd('/'), "t")
        lookUpYears(listOf(song("a"), song("b")), mb, FakeYears(), { 0 }, { pauses++ })
        assertEquals(2, pauses)
        server.shutdown()
    }

    @Test fun workIsUniqueAndNeedsNetworkAndBattery() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        enqueueYearLookup(ctx)
        enqueueYearLookup(ctx)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("song-years").get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos[0].constraints.requiredNetworkType)
        assertTrue(infos[0].constraints.requiresBatteryNotLow())
    }
}
