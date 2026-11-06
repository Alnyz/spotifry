package com.spotifry.music.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.FakeMusicSource
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoodsTest {
    @get:Rule val compose = createComposeRule()

    private val mood = MOODS.first()
    private val link = PageLink(SearchType.PLAYLISTS, "u1", "Slow one", "Ann", null)

    private class Source : MusicSource by FakeMusicSource() {
        var calls = 0
        val answers = ArrayDeque<CompletableDeferred<List<PageLink>>>()
        var queries = mutableListOf<Pair<String, SearchType>>()
        override suspend fun searchPages(query: String, type: SearchType): List<PageLink> {
            calls++
            queries += query to type
            return answers.removeFirst().await()
        }
        val songs = mutableMapOf<String, Int>()
        val broken = mutableSetOf<String>()
        override suspend fun playableSongs(link: PageLink): Int { if (link.url in broken) error("offline"); return songs[link.url] ?: 10 }
        fun next() = CompletableDeferred<List<PageLink>>().also { answers.addLast(it) }
    }

    @Test fun theTilesHaveNoDuplicatesOrBlanks() {
        assertTrue(MOODS.size in 12..16)
        assertEquals(MOODS.size, MOODS.map { it.name.lowercase() }.toSet().size)
        assertEquals(MOODS.size, MOODS.map { it.query.lowercase() }.toSet().size)
        assertEquals(MOODS.size, MOODS.map { it.line }.toSet().size)
        MOODS.forEach {
            assertTrue(it.name, it.name.isNotBlank() && it.line.isNotBlank() && it.query.isNotBlank())
        }
    }

    private fun search(text: String, state: SearchState = SearchState.Idle, history: List<String> = emptyList(), onMood: (Mood) -> Unit = {}) = compose.setContent {
        MusicTheme { Box(Modifier.size(320.dp, 800.dp)) { SearchContent(text, {}, state, emptySet(), history, { _, _ -> }, {}, {}, {}, onMood = onMood) } }
    }

    @Test fun theGridShowsOnlyWithAnEmptyBox() {
        var text by mutableStateOf("")
        compose.setContent { MusicTheme { Box(Modifier.size(320.dp, 800.dp)) { SearchContent(text, {}, SearchState.Idle, emptySet(), emptyList(), { _, _ -> }, {}, {}, {}) } } }
        compose.onNodeWithText("Moods and genres").assertIsDisplayed()
        // the search hasn't started yet, the tiles leave anyway
        text = "a"
        compose.onNodeWithText("Moods and genres").assertDoesNotExist()
        text = ""
        compose.onNodeWithText("Moods and genres").assertIsDisplayed()
        // a space is typed text, even though nothing gets searched
        text = " "
        compose.onNodeWithText("Moods and genres").assertDoesNotExist()
    }

    @Test fun clearingTheBoxBringsTheGridBackWithoutTheOldResults() {
        // the box is empty, the search hasn't caught up
        search("", SearchState.Results(emptyList(), query = "old"))
        compose.onNodeWithText("Moods and genres").assertIsDisplayed()
        compose.onNodeWithText("No songs found. Try another spelling.").assertDoesNotExist()
    }

    @Test fun theGridSitsUnderRecentSearchesAndATileOpens() {
        val opened = mutableListOf<Mood>()
        search("", history = listOf("coldplay"), onMood = { opened += it })
        val recent = compose.onNodeWithText("coldplay").getBoundsInRoot().top
        val title = compose.onNodeWithText("Moods and genres").getBoundsInRoot().top
        assertTrue(recent < title)
        val last = MOODS.last()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(last.name))
        compose.onNodeWithText(last.name).performClick()
        assertEquals(listOf(last), opened)
    }

    @Test fun twoTilesARowAndEachIsAtLeastABigTapTarget() {
        search("")
        val a = compose.onNodeWithText(MOODS[0].name).getBoundsInRoot()
        val b = compose.onNodeWithText(MOODS[1].name).getBoundsInRoot()
        assertEquals(a.top, b.top)
        assertTrue(a.left < b.left)
        // the tile, not just its words, is what's clickable
        compose.onNode(androidx.compose.ui.test.hasClickAction() and hasText(MOODS[0].name)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    private fun page(vm: MoodViewModel) = compose.setContent { MusicTheme { MoodScreen(vm, {}, PageActions(), 0.dp) } }

    @Test fun aTileSearchesForItsPlaylistsAndShowsThemWithItsLine() {
        val s = Source()
        val answer = s.next()
        val vm = MoodViewModel(s, mood, mutableMapOf())
        page(vm)
        compose.onNodeWithText(mood.line).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodesWithTag("loading_row").assertCountEquals(6)
        answer.complete(listOf(link, link.copy(name = "Same link again"), link.copy(url = "u2", name = "Other")))
        compose.onNodeWithText("Slow one").assertIsDisplayed()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithText("Same link again").assertDoesNotExist()
        compose.onAllNodesWithTag("loading_row").assertCountEquals(0)
        assertEquals(listOf(mood.query to SearchType.PLAYLISTS), s.queries)
    }

    @Test fun aFailedLoadOffersRetryAndRetryLoads() {
        val s = Source()
        val first = s.next()
        val second = s.next()
        page(MoodViewModel(s, mood, mutableMapOf()))
        first.completeExceptionally(IllegalStateException("offline"))
        compose.onNodeWithText("Couldn't load ${mood.name}. Check your connection, then try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        second.complete(listOf(link))
        compose.onNodeWithText("Slow one").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun nothingFoundGetsPlainWordsAndIsNotKept() {
        val s = Source()
        val cache = mutableMapOf<String, List<PageLink>>()
        s.next().complete(emptyList())
        page(MoodViewModel(s, mood, cache))
        compose.onNodeWithText("No playlists found for ${mood.name} right now. Try again later.").assertIsDisplayed()
        assertFalse(mood.name in cache)
    }

    @Test fun onlyListsThatHaveSongsToPlayGetARow() = runBlocking {
        val s = Source().apply { songs += mapOf("empty" to 0, "tiny" to 4, "enough" to 5); broken += "bad" }
        s.next().complete(listOf("empty", "tiny", "enough", "bad", "fine").map { link.copy(url = it, name = it) } + link.copy(url = "k", name = "Lagu KARAOKE nada pria") + link.copy(url = "same", name = " ENOUGH "))
        assertEquals(listOf("enough", "fine"), moodLists(s, mood).lists.map { it.name })
    }

    @Test fun whenNoListCanBeCheckedItIsAFailureNotAnEmptyPage() {
        val s = Source().apply { broken += "u1" }
        s.next().complete(listOf(link))
        page(MoodViewModel(s, mood, mutableMapOf()))
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun whenEveryListIsEmptyItSaysSo() {
        val s = Source().apply { songs += "u1" to 0 }
        s.next().complete(listOf(link))
        page(MoodViewModel(s, mood, mutableMapOf()))
        compose.onNodeWithText("No playlists found for ${mood.name} right now. Try again later.").assertIsDisplayed()
    }

    @Test fun openingATileAgainUsesWhatItAlreadyLoaded() {
        val s = Source()
        val cache = mutableMapOf<String, List<PageLink>>()
        s.next().complete(listOf(link))
        val first = MoodViewModel(s, mood, cache)
        compose.waitUntil { first.lists.value != null }
        val again = MoodViewModel(s, mood, cache)
        page(again)
        compose.onNodeWithText("Slow one").assertIsDisplayed()
        assertEquals(1, s.calls)
    }

    @Test fun aFailureIsNotKept() {
        val s = Source()
        val cache = mutableMapOf<String, List<PageLink>>()
        s.next().completeExceptionally(IllegalStateException("offline"))
        val vm = MoodViewModel(s, mood, cache)
        compose.waitUntil { vm.lists.value != null }
        assertTrue(cache.isEmpty())
    }

    private fun many(n: Int) = List(n) { link.copy(url = "u$it", name = "List $it") }

    /** Tiles' checks wait at a gate, so what's running at once can be counted. */
    private class Gated(val found: List<PageLink>) : MusicSource by FakeMusicSource() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        override suspend fun searchPages(query: String, type: SearchType) = found
        override suspend fun playableSongs(link: PageLink): Int {
            calls.incrementAndGet()
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try { gate.await() } finally { running.decrementAndGet() }
            return 10
        }
    }

    @Test fun noMoreThanFourChecksRunAtOnceAcrossTiles() = runBlocking {
        val s = Gated(many(20))
        withContext(Dispatchers.Default) {
            val tiles = MOODS.take(3).map { m -> async { moodLists(s, m) } }
            withTimeout(5000) { while (s.running.get() < 4) delay(5) }
            delay(200)
            assertEquals(4, s.peak.get())
            s.gate.complete(Unit)
            tiles.awaitAll().forEach { assertEquals(10, it.lists.size) }
        }
        assertEquals(4, s.peak.get())
    }

    @Test fun itStopsCheckingOnceEnoughListsPass() = runBlocking {
        val s = Source()
        var checked = 0
        val counting = object : MusicSource by s { override suspend fun playableSongs(link: PageLink): Int { checked++; return s.playableSongs(link) } }
        s.next().complete(many(20))
        val found = moodLists(counting, mood)
        assertEquals(many(20).take(10), found.lists)
        assertTrue(found.keep)
        assertEquals("three groups of four", 12, checked)
    }

    @Test fun onMeteredDataWithDataSaverAFewListsAreEnough() {
        val s = Source()
        s.next().complete(many(20))
        val vm = MoodViewModel(s, mood, mutableMapOf(), low = { true })
        compose.waitUntil { vm.lists.value != null }
        assertEquals(5, vm.lists.value!!.getOrThrow().size)
    }

    @Test fun leavingThePageStopsTheChecksAndGivesBackTheirPlaces() = runBlocking {
        val s = Gated(many(20))
        val tile = launch(Dispatchers.Default) { moodLists(s, mood) }
        withTimeout(5000) { while (s.running.get() < 4) delay(5) }
        tile.cancelAndJoin()
        assertEquals("no further group started", 4, s.calls.get())
        val free = Gated(many(4)).apply { gate.complete(Unit) }
        assertEquals(4, withTimeout(5000) { moodLists(free, mood) }.lists.size)
    }

    private fun failing(good: Map<String, Int>) = Source().apply {
        many(20).forEach { if (it.url !in good) broken += it.url }
        songs += good
        next().complete(many(20))
    }

    @Test fun aPartFailureShowsWhatPassedButIsNotKept() {
        val cache = mutableMapOf<String, List<PageLink>>()
        val vm = MoodViewModel(failing(mapOf("u3" to 10)), mood, cache)
        compose.waitUntil { vm.lists.value != null }
        assertEquals(listOf("u3"), vm.lists.value!!.getOrThrow().map { it.url })
        assertTrue(cache.isEmpty())
    }

    @Test fun nothingPassedAndSomethingFailedIsAFailureNotNothingFound() {
        val cache = mutableMapOf<String, List<PageLink>>()
        page(MoodViewModel(failing(mapOf("u3" to 0)), mood, cache))
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("No playlists found for ${mood.name} right now. Try again later.").assertDoesNotExist()
        assertTrue(cache.isEmpty())
    }

    @Test fun someFailuresDontMatterOnceEnoughPassed() = runBlocking {
        val s = Source().apply { broken += "u0"; next().complete(many(20)) }
        val found = moodLists(s, mood)
        assertEquals(10, found.lists.size)
        assertTrue(found.keep)
    }

    @Test fun fewerListsThanEnoughAreKeptWhenNothingFailed() = runBlocking {
        val s = Source().apply { next().complete(many(3)) }
        assertTrue(moodLists(s, mood).keep)
    }
}
