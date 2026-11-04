package com.spotifry.music.ui.home

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
}
