package com.spotifry.music.ui.saved

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.FakeMusicSource
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.testSong
import com.spotifry.music.player.SongPlayer
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class NoopPlayer : SongPlayer { override fun playList(songs: List<Song>, startIndex: Int, from: String?) {} }

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var dir: File

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        dir = createTempDir("savedvm")
    }
    @After fun tearDown() { Dispatchers.resetMain(); db.close(); dir.deleteRecursively() }

    private suspend fun <T> StateFlow<T>.await(p: (T) -> Boolean): T = withContext(Dispatchers.Default) { withTimeout(5_000) { first(p) } }

    @Test fun emptySavedShowsExactCopy() {
        compose.setContent { MusicTheme { SavedContent(emptyList(), {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        compose.onNodeWithText("Open any song and tap “Save on this phone”. It will play here without internet.").assertIsDisplayed()
    }

    @Test fun findASongButtonNavigatesToListen() {
        var taps = 0
        compose.setContent { MusicTheme { SavedContent(emptyList(), { taps++ }, { _, _ -> }, {}) } }
        compose.onNodeWithText("Find a song to save").performClick()
        assertEquals(1, taps)
    }

    @Test fun removingSavedSongUpdatesList() = runBlocking<Unit> {
        val file = File(dir, "songs").apply { mkdirs() }.resolve("abc.m4a").apply { writeText("audio") }
        db.songs().upsert(testSong.toEntity())
        db.songs().markSaved("abc", file.absolutePath, null, 1)
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(FakeMusicSource()), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertEquals(listOf("abc"), vm.saved.await { it.isNotEmpty() }.map { it.id })
        vm.remove("abc")
        vm.saved.await { it.isEmpty() }
        assertEquals(false, file.exists())
    }

    @Test fun savedListShowsTheSongTheMomentSaveIsPressed() = runBlocking<Unit> {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        val source = FakeMusicSource().apply { streamUrl = server.url("/a").toString() }
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(ByteArray(300_000))).throttleBody(50_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(source), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        vm.save(testSong)
        // the file is still downloading (about 600 ms), but the list already shows it
        assertEquals(listOf("abc"), vm.saved.await { it.isNotEmpty() }.map { it.id })
        assertEquals(true, downloads.state("abc").value is com.spotifry.music.data.DownloadState.Downloading)
        // let the download finish and write to the database before tearDown closes it
        vm.saved.await { l -> l.firstOrNull()?.filePath != null }
        server.shutdown()
    }
}
