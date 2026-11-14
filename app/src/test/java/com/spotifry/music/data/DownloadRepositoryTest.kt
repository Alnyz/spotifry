package com.spotifry.music.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val source = FakeMusicSource()
    private lateinit var repo: DownloadRepository
    private val audio = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        dir = createTempDir("dlrepo")
        source.streamUrl = server.url("/a").toString()
        repo = DownloadRepository(dir, OkHttpClient(), StreamResolver(source) { 1_000L }, db.songs(), retryPauseMs = 0)
    }
    @After fun tearDown() { server.shutdown(); db.close(); dir.deleteRecursively() }

    private fun body() = MockResponse().setBody(Buffer().write(audio))

    @Test fun savesCompleteFile() = runBlocking {
        server.enqueue(body())
        repo.save(testSong)
        assertEquals(DownloadState.Saved, repo.state(testSong.id).value)
        val row = db.songs().get(testSong.id)!!
        assertNotNull(row.filePath)
        assertArrayEquals(audio, File(row.filePath!!).readBytes())
    }

    @Test fun interruptedDownloadFails() = runBlocking {
        repeat(4) { server.enqueue(body().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) } // the first try and three retries
        repo.save(testSong)
        assertTrue(repo.state(testSong.id).value is DownloadState.Failed)
        assertNull(db.songs().get(testSong.id)?.filePath)
        assertEquals(emptyList<String>(), File(dir, "songs").listFiles().orEmpty().map { it.name })
    }

    @Test fun removeDeletesFile() = runBlocking {
        server.enqueue(body())
        repo.save(testSong)
        val path = db.songs().get(testSong.id)!!.filePath!!
        repo.remove(testSong.id)
        assertFalse(File(path).exists())
        assertNull(db.songs().get(testSong.id)!!.filePath)
        assertEquals(DownloadState.Idle, repo.state(testSong.id).value)
    }

    @Test fun leftoverPartFilesCleanedOnStart() {
        val songs = File(dir, "songs").apply { mkdirs() }
        File(songs, "old.part").writeText("half")
        File(songs, "keep.m4a").writeText("whole")
        DownloadRepository(dir, OkHttpClient(), StreamResolver(source) { 1_000L }, db.songs())
        assertEquals(listOf("keep.m4a"), songs.listFiles()!!.map { it.name })
    }

    @Test fun progressNeverGoesBackwardsAndEndsSaved() = runBlocking {
        server.enqueue(body())
        val seen = mutableListOf<DownloadState>()
        val job = launch(Dispatchers.Unconfined) { repo.state(testSong.id).collect { seen += it } }
        repo.save(testSong)
        job.cancel()
        val progress = seen.filterIsInstance<DownloadState.Downloading>().map { it.progress }
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.all { it in 0f..1f })
        assertEquals(DownloadState.Saved, seen.last())
    }

    private fun slowBody() = MockResponse().setBody(Buffer().write(audio)).throttleBody(100_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test fun downloadSurvivesTheCallerGoingAway() = runBlocking<Unit> {
        server.enqueue(slowBody())
        val caller = launch(Dispatchers.Default) { repo.save(testSong) }
        kotlinx.coroutines.delay(150)
        caller.cancel() // the screen that started the save is gone
        withContext(Dispatchers.Default) { withTimeout(5_000) { repo.state(testSong.id).first { it == DownloadState.Saved } } }
        assertNotNull(db.songs().get(testSong.id)!!.filePath)
    }

    @Test fun secondSaveWhileDownloadingDoesNothing() = runBlocking<Unit> {
        server.enqueue(slowBody())
        val a = launch(Dispatchers.Default) { repo.save(testSong) }
        kotlinx.coroutines.delay(100)
        repo.save(testSong) // double tap
        a.join()
        assertEquals(1, server.requestCount)
        assertEquals(DownloadState.Saved, repo.state(testSong.id).value)
    }

    @Test fun asksForTheFileWithARangeSoYouTubeDoesNotThrottleIt() = runBlocking<Unit> {
        server.enqueue(body())
        repo.save(testSong)
        // measured: a plain GET crawls at ~33 KB/s, the same URL with range=0- arrives at ~12 MB/s
        assertTrue(server.takeRequest().path!!.contains("range=0-"))
    }

    @Test fun pendingListShowsTheSongTheMomentSaveStarts() = runBlocking<Unit> {
        server.enqueue(slowBody())
        val a = launch(Dispatchers.Default) { repo.save(testSong) }
        kotlinx.coroutines.delay(100)
        assertEquals(listOf(testSong.id), repo.pending.value.map { it.id })
        a.join()
        assertEquals(emptyList<String>(), repo.pending.value.map { it.id })
    }

    @Test fun removingWhileDownloadingDoesNotBringTheSongBack() = runBlocking<Unit> {
        server.enqueue(slowBody())
        val a = launch(Dispatchers.Default) { repo.save(testSong) }
        kotlinx.coroutines.delay(100)
        repo.remove(testSong.id)
        a.join()
        assertNull(db.songs().get(testSong.id)?.filePath)
        assertEquals(emptyList<String>(), File(dir, "songs").listFiles().orEmpty().map { it.name })
    }

    @Test fun failedDownloadAnnouncesTheSong() = runBlocking<Unit> {
        repeat(4) { server.enqueue(body().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) }
        val seen = kotlinx.coroutines.CompletableDeferred<String>()
        val job = launch(Dispatchers.Unconfined) { repo.failures.collect { seen.complete(it.id) } }
        repo.save(testSong)
        assertEquals(testSong.id, withTimeout(5_000) { seen.await() })
        job.cancel()
    }

    /** First request gets [cut] bytes of a longer promise and then the line drops; later ones go to [then]. */
    private fun dropsAfter(cut: Int, then: (RecordedRequest) -> MockResponse) {
        var first = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (first) { first = false; MockResponse().setBody(Buffer().write(audio, 0, cut)).setHeader("Content-Length", audio.size).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) } else then(request)
        }
    }

    @Test fun aDroppedConnectionContinuesWhereItStopped() = runBlocking<Unit> {
        dropsAfter(100_000) { r ->
            assertEquals("bytes=100000-", r.getHeader("Range"))
            MockResponse().setResponseCode(206).setBody(Buffer().write(audio, 100_000, audio.size - 100_000))
        }
        repo.save(testSong)
        assertEquals(DownloadState.Saved, repo.state(testSong.id).value)
        assertArrayEquals(audio, File(db.songs().get(testSong.id)!!.filePath!!).readBytes())
    }

    @Test fun aServerThatIgnoresTheRangeSendsItAllAgain() = runBlocking<Unit> {
        dropsAfter(100_000) { body() }
        repo.save(testSong)
        assertEquals(DownloadState.Saved, repo.state(testSong.id).value)
        assertArrayEquals(audio, File(db.songs().get(testSong.id)!!.filePath!!).readBytes())
    }
}
