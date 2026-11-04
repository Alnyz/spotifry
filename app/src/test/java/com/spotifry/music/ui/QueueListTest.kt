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
        compose.onNodeWithTag("queue_list").performScrollToIndex(49)
        compose.onNodeWithText("Song 49").assertIsDisplayed()
    }

    @Test fun queueFollowsShuffleOrderAndMarksSavedSongs() {
        val queue = (0 until 4).map { Song("id$it", "Song $it", "Artist", 100, null) }
        val state = PlayerUiState(current = queue[2], queue = queue, currentIndex = 2, shuffle = true, order = listOf(2, 0, 3))
        compose.setContent { MusicTheme { QueueList(state, setOf("id0")) {} } }
        compose.onNodeWithText("Song 2").assertIsDisplayed()
        compose.onAllNodesWithTag("saved_mark", useUnmergedTree = true).assertCountEquals(1)
        org.junit.Assert.assertEquals(listOf(2, 0, 3), state.upNextIndices())
    }

    @Test fun onlyTheSongsComingUpCanBeMoved() {
        val queue = (0 until 3).map { Song("id$it", "Song $it", "Artist", 100, null) }
        compose.setContent { MusicTheme { QueueList(PlayerUiState(current = queue[0], queue = queue), onMove = { _, _ -> }, onRemove = {}, onPick = {}) } }
        compose.onNodeWithContentDescription("Drag to move Song 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drag to move Song 2").assertIsDisplayed()
    }
}
