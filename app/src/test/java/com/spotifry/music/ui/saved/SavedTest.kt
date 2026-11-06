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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    private fun show(filter: SavedFilter, rows: List<SavedRow> = emptyList(), allowed: Boolean = true, onFilter: (SavedFilter) -> Unit = {}, onPlay: (List<Song>, Int) -> Unit = { _, _ -> }, onFind: () -> Unit = {}, onAllow: () -> Unit = {}) =
        compose.setContent { MusicTheme { SavedContent(SavedView(filter, rows), onFilter, onFind, onPlay, {}, phoneAllowed = allowed, onAllow = onAllow) } }

    @Test fun emptySavedShowsExactCopy() {
        show(SavedFilter.Offline)
        compose.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        compose.onNodeWithText("Open any song and tap “Save on this phone”. It will play here without internet.").assertIsDisplayed()
    }

    @Test fun everyFilterHasItsOwnEmptyWords() {
        val say = mapOf(
            SavedFilter.All to "Nothing here yet", SavedFilter.Offline to "Nothing saved yet",
            SavedFilter.Phone to "No music files found", SavedFilter.Lately to "Nothing played lately",
        )
        var now by androidx.compose.runtime.mutableStateOf(SavedFilter.All)
        compose.setContent { MusicTheme { SavedContent(SavedView(now), {}, {}, { _, _ -> }, {}) } }
        say.forEach { (f, text) -> now = f; compose.onNodeWithText(text).assertIsDisplayed() }
    }

    @Test fun allOnlyAsksForMusicAccessWhenItIsntGivenYet() {
        var given by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { MusicTheme { SavedContent(SavedView(SavedFilter.All), {}, {}, { _, _ -> }, {}, phoneAllowed = given) } }
        compose.onNodeWithText("Save a song from search, or allow music on this phone, and it shows up here.").assertIsDisplayed()
        given = true
        compose.onNodeWithText("Save a song from search, or add music files to this phone, and it shows up here.").assertIsDisplayed()
    }

    @Test fun theFourChipsAreThereAndTappingOneAsksForIt() {
        val picked = ArrayList<SavedFilter>()
        show(SavedFilter.All, onFilter = { picked += it })
        SavedFilter.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        compose.onNodeWithText("Played lately").performClick()
        compose.onNodeWithText("On this phone").performClick()
        assertEquals(listOf(SavedFilter.Lately, SavedFilter.Phone), picked)
    }

    @Test fun withoutAccessToMusicThePhoneChipShowsTheAskCard() {
        var asked = 0
        show(SavedFilter.Phone, allowed = false, onAllow = { asked++ })
        compose.onNodeWithText("Play the music on your phone").assertIsDisplayed()
        compose.onNodeWithText("Allow access to music").performClick()
        assertEquals(1, asked)
    }

    @Test fun tappingARowPlaysTheListAsFilteredFromThatRow() {
        val rows = listOf(SavedRow(Song("x", "First", "A", 100, null), true), SavedRow(Song("local:2", "Second", "B", 100, null), false))
        var played: Pair<List<String>, Int>? = null
        show(SavedFilter.Lately, rows, onPlay = { l, i -> played = l.map { it.id } to i })
        compose.onNodeWithText("Second").performClick()
        assertEquals(listOf("x", "local:2") to 1, played)
    }

    @Test fun thePickedFilterStaysInTheViewModelAcrossTabs() = runBlocking<Unit> {
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(FakeMusicSource()), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()), db.history())
        assertEquals(SavedFilter.All, vm.view.value.filter)
        vm.pick(SavedFilter.Lately)
        vm.view.await { it.filter == SavedFilter.Lately }
    }

    @Test fun playedLatelyFollowsTheHistoryNewestFirstOnlyForSavedSongs() = runBlocking<Unit> {
        val file = File(dir, "songs").apply { mkdirs() }.resolve("abc.m4a").apply { writeText("audio") }
        db.songs().upsert(testSong.toEntity()); db.songs().markSaved("abc", file.absolutePath, null, 1)
        db.songs().upsert(Song("stream", "Streamed", "X", 100, null).toEntity())
        db.history().record("abc", 1); db.history().record("stream", 2)
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(FakeMusicSource()), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()), db.history())
        vm.pick(SavedFilter.Lately)
        assertEquals(listOf("abc"), vm.view.await { it.filter == SavedFilter.Lately && it.rows.isNotEmpty() }.rows.map { it.song.id })
    }

    @Test fun findASongButtonNavigatesToListen() {
        var taps = 0
        compose.setContent { MusicTheme { SavedContent(SavedView(SavedFilter.Offline), {}, { taps++ }, { _, _ -> }, {}) } }
        compose.onNodeWithText("Find a song to save").performClick()
        assertEquals(1, taps)
    }

    @Test fun removingSavedSongUpdatesList() = runBlocking<Unit> {
        val file = File(dir, "songs").apply { mkdirs() }.resolve("abc.m4a").apply { writeText("audio") }
        db.songs().upsert(testSong.toEntity())
        db.songs().markSaved("abc", file.absolutePath, null, 1)
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(FakeMusicSource()), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()), db.history())
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
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(androidx.test.core.app.ApplicationProvider.getApplicationContext()), db.history())
        vm.save(testSong)
        // the file is still downloading (about 600 ms), but the list already shows it
        assertEquals(listOf("abc"), vm.saved.await { it.isNotEmpty() }.map { it.id })
        assertEquals(true, downloads.state("abc").value is com.spotifry.music.data.DownloadState.Downloading)
        // let the download finish and write to the database before tearDown closes it
        vm.saved.await { l -> l.firstOrNull()?.filePath != null }
        server.shutdown()
    }

    @Test fun accessGivenInAndroidsSettingsShowsOnReturn() = runBlocking<Unit> {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        val downloads = DownloadRepository(dir, OkHttpClient(), StreamResolver(FakeMusicSource()), db.songs())
        val vm = SavedViewModel(db.songs(), downloads, NoopPlayer(), com.spotifry.music.data.LocalMusic(app), db.history())
        vm.pick(SavedFilter.Phone)
        vm.view.await { it.filter == SavedFilter.Phone }
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MusicTheme { SavedScreen(vm, {}, {}, androidx.compose.ui.unit.Dp(0f)) }
            }
        }
        compose.onNodeWithText("Allow access to music").assertIsDisplayed()
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText("Allow access to music").assertDoesNotExist()
    }
}
