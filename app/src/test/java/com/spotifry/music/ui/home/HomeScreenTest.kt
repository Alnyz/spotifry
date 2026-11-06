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

    @Test fun playingSaysSoWithBarsAndTheButtonPauses() {
        var stopped = 0; var opened = 0
        home(PlayerUiState(current = testSong, isPlaying = true, wantsPlay = true), opened = { opened++ }, paused = { stopped++ })
        compose.onNodeWithText("Playing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playing now").assertExists()
        compose.onAllNodesWithText("Last played").assertCountEquals(0)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, stopped)
        compose.onNodeWithText("Title").performClick()
        assertEquals(1, opened)
    }

    @Test fun pausedSaysSoWithNoBarsAndTheButtonPlays() {
        var toggled = 0
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = false), { toggled++ })
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onAllNodesWithText("Playing").assertCountEquals(0)
        compose.onAllNodesWithText("Last played").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Playing now").assertCountEquals(0)
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, toggled)
    }

    @Test fun bufferingSaysLoadingWithNoBarsAndTheButtonPauses() {
        var stopped = 0
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = true, buffering = true), paused = { stopped++ })
        compose.onNodeWithText("Loading").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Playing now").assertCountEquals(0)
        compose.onNodeWithContentDescription("Pause").performClick() // must stop it even before sound starts
        assertEquals(1, stopped)
    }

    @Test fun anotherAppHoldingTheSoundIsNotLoading() {
        home(PlayerUiState(current = testSong, isPlaying = false, wantsPlay = true, buffering = false))
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onAllNodesWithText("Loading").assertCountEquals(0)
    }

    @Test fun aFailedSongSaysSoAndTheButtonTriesAgain() {
        var toggled = 0
        home(PlayerUiState(current = testSong, failed = true), { toggled++ })
        compose.onNodeWithText("Couldn't play").assertIsDisplayed()
        compose.onAllNodesWithText("Paused").assertCountEquals(0)
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
        compose.onNodeWithText("Playing").assertIsDisplayed()
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
}
