package com.spotifry.music.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.theme.MusicTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lastSongInALongQueueIsReachable() {
        val queue = (0 until 50).map { Song("id$it", "Song $it", "Artist", 100, null) }
        compose.setContent { MusicTheme { QueueList(PlayerUiState(current = queue[0], queue = queue), onPick = {}) } }
        compose.onNodeWithTag("queue_list").performScrollToIndex(48)
        compose.onNodeWithText("Song 49").assertIsDisplayed()
    }

    @Test fun queueFollowsShuffleOrderAndMarksSavedSongs() {
        val queue = (0 until 4).map { Song("id$it", "Song $it", "Artist", 100, null) }
        val state = PlayerUiState(current = queue[2], queue = queue, currentIndex = 2, shuffle = true, order = listOf(2, 0, 3))
        compose.setContent { MusicTheme { QueueList(state, setOf("id0")) {} } }
        // the playing song isn't listed: Now playing shows it above the queue
        compose.onNodeWithText("Song 2").assertDoesNotExist()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.onAllNodesWithTag("saved_mark", useUnmergedTree = true).assertCountEquals(1)
        org.junit.Assert.assertEquals(listOf(2, 0, 3), state.upNextIndices())
    }

    @Test fun onlyTheSongsComingUpCanBeMoved() {
        val queue = (0 until 3).map { Song("id$it", "Song $it", "Artist", 100, null) }
        compose.setContent { MusicTheme { QueueList(PlayerUiState(current = queue[0], queue = queue), onMove = { _, _ -> }, onRemove = {}, onPick = {}) } }
        compose.onNodeWithContentDescription("Move Song 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move Song 2").assertIsDisplayed()
    }

    /** The labels of the TalkBack actions on a node, and runs the one called [run] if given. */
    private fun actions(description: String, run: String? = null): List<String> {
        val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
        val list = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        if (run != null) compose.runOnIdle { list.first { it.label == run }.action() }
        return list.map { it.label }
    }

    @Test fun talkBackCanMoveAndRemoveQueuedSongsWithoutDragging() {
        val queue = (0 until 4).map { Song("id$it", "Song $it", "Artist", 100, null) }
        val moves = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Int>()
        compose.setContent { MusicTheme { QueueList(PlayerUiState(current = queue[0], queue = queue), onMove = { a, b -> moves += a to b }, onRemove = { removed += it }, onPick = {}) } }
        // the first one up can only go down, the last only up
        assertEquals(listOf("Move down", "Remove from queue"), actions("Move Song 1"))
        assertEquals(listOf("Move up", "Remove from queue"), actions("Move Song 3"))
        actions("Move Song 1", run = "Move down")
        assertEquals(listOf(1 to 2), moves)
        actions("Move Song 3", run = "Remove from queue")
        assertEquals(listOf(3), removed)
    }
}
