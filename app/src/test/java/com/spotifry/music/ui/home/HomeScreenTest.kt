package com.spotifry.music.ui.home

import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.player.PlayerUiState
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import org.junit.Assert.assertTrue
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.testSong
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingRowsShowWhileSearching() {
        compose.setContent { MusicTheme { SearchResults(SearchState.Loading, emptySet(), { _, _ -> }, {}, {}) } }
        compose.onAllNodesWithTag("loading_row").assertCountEquals(3)
    }

    @Test fun errorRowShowsExactCopyAndRetryButton() {
        var retried = 0
        compose.setContent { MusicTheme { ErrorRow("Sample", onRetry = { retried++ }) } }
        compose.onNodeWithText("Couldn't load Sample. Check your connection, then try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test fun savedSongShowsDownloadMark() {
        compose.setContent { MusicTheme { SongRow(testSong, saved = true, onClick = {}, onMore = {}) } }
        compose.onNodeWithContentDescription("Saved on this phone").assertIsDisplayed()
    }

    @Test fun unsavedSongHasNoDownloadMark() {
        compose.setContent { MusicTheme { SongRow(testSong, saved = false, onClick = {}, onMore = {}) } }
        compose.onAllNodesWithTag("saved_mark").assertCountEquals(0)
    }

    @Test fun listenShowsLastPlayedRecentAndAMixThatQueuesOneSong() {
        val recent = listOf(testSong.toEntity(), Song("def", "Second", "Other", 100, null).toEntity())
        var played: List<Song>? = null
        compose.setContent { MusicTheme { HomeContent(recent, emptySet(), { l, _, _ -> played = l }, {}) } }
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onAllNodesWithText("Shuffle").assertCountEquals(0) // nothing saved, so no shuffle shortcut
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performScrollToNode(hasText("Second"))
        compose.onNodeWithText("Second").assertIsDisplayed()
        list.performScrollToNode(hasText("Mix from Title"))
        compose.onNodeWithText("Mix from Title").performClick()
        assertEquals(listOf("abc"), played?.map { it.id })
    }

    private val first = testSong.toEntity()
    private val second = Song("def", "Second", "Other", 100, null)
    private val history = listOf(first, second.toEntity())
    private fun home(player: PlayerUiState, toggled: () -> Unit = {}, opened: () -> Unit = {}, played: (List<Song>) -> Unit = {}, paused: () -> Unit = {}) = compose.setContent {
        MusicTheme { HomeContent(history, emptySet(), { l, _, _ -> played(l) }, {}, player = player, onToggle = toggled, onPause = paused, onOpenPlayer = opened) }
    }

    @Test fun playingKeepsThePlainPillAndTheButtonPauses() {
        var stopped = 0; var opened = 0
        home(PlayerUiState(current = testSong, isPlaying = true, wantsPlay = true), opened = { opened++ }, paused = { stopped++ })
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onAllNodesWithText("Playing").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Playing now").assertCountEquals(0)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, stopped)
        compose.onNodeWithText("Title").performClick()
        assertEquals(1, opened)
    }

    @Test fun pausedKeepsThePlainPillAndTheButtonPlays() {
        var toggled = 0
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = false), { toggled++ })
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onAllNodesWithText("Paused").assertCountEquals(0)
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, toggled)
    }

    @Test fun bufferingKeepsThePlainPillAndTheButtonPauses() {
        var stopped = 0
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = true, buffering = true), paused = { stopped++ })
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onAllNodesWithText("Loading").assertCountEquals(0)
        compose.onNodeWithContentDescription("Pause").performClick() // must stop it even before sound starts
        assertEquals(1, stopped)
    }

    @Test fun anotherAppHoldingTheSoundShowsPlayNotPause() {
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = true, buffering = false))
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Pause").assertCountEquals(0)
    }

    @Test fun aFailedSongSaysSoUnderTheTitleAndTheButtonTriesAgain() {
        var toggled = 0
        home(PlayerUiState(current = testSong, failed = true), { toggled++ })
        compose.onNodeWithText("Couldn't play").assertIsDisplayed()
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, toggled)
    }

    @Test fun aFinishedSongIsLastPlayedAgainAndPlaysAgain() {
        var played: List<Song>? = null
        home(PlayerUiState(current = testSong, ended = true), played = { played = it })
        compose.onNodeWithText("Last played").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play again").performClick()
        assertEquals(listOf("abc", "def"), played?.map { it.id })
    }

    @Test fun anotherSongInThePlayerTakesTheHeroAndTheLastOneMovesToTheRow() {
        home(PlayerUiState(current = second, isPlaying = true, wantsPlay = true))
        compose.onAllNodesWithText("Second").assertCountEquals(1)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Recently played"))
        compose.onAllNodesWithText("Title").assertCountEquals(1)
    }

    @Test fun theNewestPlaylistWithSongsIsTheHeroWhenNothingWasPlayedAndNotInTheRow() {
        var open: Long? = null; var play: PlaylistWithCounts? = null
        val lists = listOf(PlaylistWithCounts(1, "Older", 3, 0, 1), PlaylistWithCounts(2, "Road trip", 48, 0, 2), PlaylistWithCounts(3, "Empty new", 0, 0, 3))
        compose.setContent {
            MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, playlists = lists, onOpenPlaylist = { open = it }, onPlayPlaylist = { play = it }) }
        }
        compose.onNodeWithText("Your playlist").assertIsDisplayed()
        compose.onNodeWithText("48 songs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(2L, play?.id)
        compose.onNodeWithText("Road trip").performClick()
        assertEquals(2L, open)
        compose.onNodeWithText("Saved").assertIsDisplayed() // there is a playlist now, so the shortcuts return
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Your playlists"))
        compose.onAllNodesWithText("Road trip").assertCountEquals(1)
        compose.onAllNodesWithText("Older").assertCountEquals(1)
    }

    @Test fun moodsLeaveOnceThereIsEnoughHistory() {
        val five = (1..5).map { Song("s$it", "Song $it", "A", 100, null).toEntity() }
        compose.setContent { MusicTheme { HomeContent(five, emptySet(), { _, _, _ -> }, {}) } }
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performScrollToNode(hasText("Mix from Song 1"))
        compose.onAllNodesWithText("Moods and genres").assertCountEquals(0)
        compose.onAllNodesWithText("Chill").assertCountEquals(0)
    }

    @Test fun moodsStayWhileThereIsLittleHistory() {
        val four = (1..4).map { Song("s$it", "Song $it", "A", 100, null).toEntity() }
        compose.setContent { MusicTheme { HomeContent(four, emptySet(), { _, _, _ -> }, {}) } }
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performScrollToNode(hasText("Moods and genres"))
        list.performScrollToNode(hasText("Chill"))
    }

    private val list get() = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
    private fun loadingNodes() = compose.onAllNodesWithContentDescription("Loading").fetchSemanticsNodes().size
    private fun many(n: Int) = (1..n).map { Song("id$it", "Song $it", "Artist $it", 100, null).toEntity() }
    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun shelfShapesHoldTheirPlacesWhileReadsArePendingAndTheRealShelvesReplaceThemInTheSameOrder() {
        // a window tall enough that every shelf is composed, so their positions can be compared
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h4000dp")
        var loading by mutableStateOf(true)
        val lists = listOf(PlaylistWithCounts(1, "Mine", 3, 0, 1))
        compose.setContent { MusicTheme { HomeContent(if (loading) emptyList() else many(8), emptySet(), { _, _, _ -> }, {}, loading = loading, mostPlayed = if (loading) emptyList() else many(6), playlists = if (loading) emptyList() else lists) } }
        compose.mainClock.advanceTimeBy(300)
        val heads = listOf("Recently played", "Your playlists", "Most played", "Keep listening")
        assertTrue(loadingNodes() > 0)
        compose.onAllNodesWithText("Moods and genres").assertCountEquals(0)
        val shapeTops = heads.map { top(it) }
        assertEquals(shapeTops.sorted(), shapeTops)
        assertEquals(heads.size, shapeTops.distinct().size)
        loading = false
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(0, loadingNodes())
        val realTops = heads.map { top(it) }
        assertEquals(realTops.sorted(), realTops)
        assertEquals(heads.size, realTops.distinct().size)
    }

    @Test fun readsThatCameBackEmptyLeaveNoShelvesAndNoShapes() {
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, loading = false) } }
        compose.mainClock.advanceTimeBy(300)
        for (h in listOf("Recently played", "Your playlists", "Most played", "Keep listening")) compose.onAllNodesWithText(h).assertCountEquals(0)
        assertEquals(0, loadingNodes())
    }

    @Test fun mostPlayedShowsShapesUntilReadAndLeavesNoGapWhenTooShort() {
        var read by mutableStateOf(false)
        compose.setContent { MusicTheme { HomeContent(many(8), emptySet(), { _, _, _ -> }, {}, mostPlayed = emptyList(), mostRead = read) } }
        compose.mainClock.advanceTimeBy(300)
        list.performScrollToNode(hasText("Most played"))
        compose.onNodeWithText("Most played").assertExists()
        read = true
        compose.waitForIdle()
        compose.onAllNodesWithText("Most played").assertCountEquals(0)
    }

    @Test fun aTimeMixPlaysItsSongsFromTheTop() {
        val songs = many(8)
        val mix = com.spotifry.music.data.TimeMix("Night", songs, 12)
        var played: Triple<List<Song>, Int, String>? = null
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { l, i, n -> played = Triple(l, i, n) }, {}, timeMixes = listOf(mix)) } }
        list.performScrollToNode(hasText("Your late nights"))
        compose.onNodeWithText("8 songs you play late at night").assertExists()
        compose.onNodeWithText("Your late nights").performClick()
        assertEquals(Triple(songs.map { it.toSong() }, 0, "Your late nights"), played)
    }
}
