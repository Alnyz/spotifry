package com.spotifry.music.ui.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.FakeMusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.testSong
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakePlayer : SongPlayer {
    val played = mutableListOf<Pair<List<Song>, Int>>()
    override fun playList(songs: List<Song>, startIndex: Int, from: String?) { played += songs to startIndex }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelTest {
    private lateinit var db: AppDatabase
    private val source = FakeMusicSource().apply { searchResult = listOf(testSong) }
    private val player = FakePlayer()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    private fun vm() = HomeViewModel(source, db.history(), db.songs(), player)

    @Test fun blankQueryDoesNotSearch() = runTest {
        val vm = vm()
        listOf("", "   ", "!!!").forEach { vm.onQueryChanged(it); advanceTimeBy(1_000); advanceUntilIdle() }
        assertEquals(0, source.searchCalls)
        assertEquals(SearchState.Idle, vm.search.value)
    }

    @Test fun searchShowsLoadingThenResults() = runTest {
        val vm = vm()
        val seen = mutableListOf<SearchState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.search.collect { seen += it } }
        vm.onQueryChanged("yellow")
        advanceTimeBy(1_000); advanceUntilIdle()
        assertTrue(seen.contains(SearchState.Loading))
        assertEquals(SearchState.Results(listOf(testSong), query = "yellow"), vm.search.value)
    }

    @Test fun failureShowsErrorAndRetryRecovers() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.search.collect { } }
        source.failSearch = true
        vm.onQueryChanged("yellow")
        advanceTimeBy(1_000); advanceUntilIdle()
        assertEquals(SearchState.Error("yellow"), vm.search.value)
        source.failSearch = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(SearchState.Results(listOf(testSong), query = "yellow"), vm.search.value)
    }

    @Test fun pickedTypeSearchesThatTypeAndStaysForTheNextSearch() = runTest {
        val album = PageLink(SearchType.ALBUMS, "https://music.youtube.com/playlist?list=OLAK", "Kid A", "Radiohead", null)
        source.pagesResult = listOf(album)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.search.collect { } }
        vm.type.value = SearchType.ALBUMS
        vm.onQueryChanged("kid a")
        advanceTimeBy(1_000); advanceUntilIdle()
        assertEquals(SearchState.Results(emptyList(), listOf(album), SearchType.ALBUMS, "kid a"), vm.search.value)
        assertEquals(0, source.searchCalls)
        vm.onQueryChanged("ok computer")
        advanceTimeBy(1_000); advanceUntilIdle()
        assertEquals(SearchType.ALBUMS, (vm.search.value as SearchState.Results).type)
        vm.submit()
        assertEquals(listOf("ok computer"), vm.recentSearches.list.value)
    }

    @Test fun queryDebounced() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.search.collect { } }
        "yel".forEach { vm.onQueryChanged(vm.currentQuery + it); advanceTimeBy(100) }
        advanceTimeBy(1_000); advanceUntilIdle()
        assertEquals(1, source.searchCalls)
    }

    @Test fun playStartsPlaybackOfTheTappedList() = runTest {
        val vm = vm()
        vm.play(listOf(testSong), 0, "Recently played")
        assertEquals(listOf(listOf(testSong) to 0), player.played)
    }

    @Test fun theHistoryIsNullUntilReadThenAListEvenIfEmpty() = runTest {
        val vm = vm()
        assertEquals(null, vm.recentOrNull.value)
        var waited = 0
        while (vm.recentOrNull.value == null && waited++ < 200) { advanceUntilIdle(); withContext(Dispatchers.Default) { kotlinx.coroutines.delay(10) } }
        assertEquals(emptyList<Any>(), vm.recentOrNull.value)
    }
}
