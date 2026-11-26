package com.spotifry.music.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.spotifry.music.data.testSong
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingTest {
    @get:Rule val compose = createComposeRule()
    private val playing = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong))

    private fun show(save: SaveUi, onSave: () -> Unit = {}) = compose.setContent {
        MusicTheme { NowPlayingContent(playing, save, {}, {}, {}, {}, {}, {}, {}, onSave, {}) }
    }

    private val queue = (0 until 3).map { com.spotifry.music.data.source.Song("id$it", "Song $it", "Artist", 100, null) }

    /** Now playing with its mode kept here, as the app shell does. */
    private fun showWithModes(state: PlayerUiState = PlayerUiState(current = queue[0], queue = queue, from = "chill"), lyrics: LyricsUi = LyricsUi.None) = compose.setContent {
        var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(NowPlayingMode.Cover) }
        MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = lyrics, mode = mode, onMode = { mode = it }) }
    }

    @Test fun unsavedSongOffersSave() { show(SaveUi.Unsaved); compose.onNodeWithContentDescription("Save on this phone").assertIsDisplayed() }
    @Test fun savedSongShowsSavedLabel() { show(SaveUi.Saved); compose.onNodeWithContentDescription("Saved on this phone").assertIsDisplayed() }
    @Test fun savingShowsAsSavedImmediately() { show(SaveUi.Saving(0.4f)); compose.onNodeWithContentDescription("Saved on this phone").assertIsDisplayed() }
    @Test fun failedSaveOffersRetry() {
        var taps = 0
        show(SaveUi.Failed) { taps++ }
        compose.onNodeWithContentDescription("Save failed. Retry").performClick()
        assertEquals(1, taps)
    }
    // a phone-sized screen: Robolectric's default is too short to show a queue row under the controls
    @Config(qualifiers = "w411dp-h891dp")
    @Test fun theQueueOpensInPlaceWithoutANowPlayingRow() {
        showWithModes()
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.onNodeWithText("Next from chill").assertIsDisplayed()
        compose.onNodeWithText("Song 1").assertIsDisplayed()
        // the playing song is only in the header row, not in the list
        compose.onAllNodesWithText("Song 0").assertCountEquals(1)
        compose.onNodeWithContentDescription("Next song").assertIsDisplayed()
    }

    @Test fun lyricsModeKeepsOnlyTheBarAndTheSmallCoverGoesBack() {
        showWithModes(lyrics = LyricsUi.Ready(com.spotifry.music.data.Lyrics.Timed(listOf(com.spotifry.music.data.LyricLine(0, "la la")))))
        compose.onNodeWithContentDescription("Show all lyrics. la la").performClick()
        compose.onNodeWithContentDescription("Song position").assertIsDisplayed()
        compose.onNodeWithText("Lyrics from", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Queue").assertDoesNotExist()
        compose.onNodeWithText("Song 0").performClick()
        compose.onNodeWithContentDescription("Next song").assertIsDisplayed()
    }

    @Test fun theGrabberAndAPullDownOnTheHeaderGoBackToTheCover() {
        showWithModes()
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.onNodeWithContentDescription("Back to the cover").performClick()
        compose.onNodeWithText("Next from chill").assertDoesNotExist()
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.onNodeWithContentDescription("Back to the cover").performTouchInput { swipeDown(startY = top, endY = top + 600f) }
        compose.onNodeWithText("Next from chill").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next song").assertIsDisplayed()
    }

    @Test fun theOutputButtonNamesWhereTheSoundPlays() {
        showWithModes()
        compose.onNodeWithContentDescription("Audio output: Phone speaker").assertIsDisplayed()
    }
    @Test fun showsTitleAndArtist() { show(SaveUi.Unsaved); compose.onNodeWithText("Title").assertIsDisplayed(); compose.onNodeWithText("Artist").assertIsDisplayed() }
}

class SaveUiTest {
    private val saved = setOf("abc")
    @org.junit.Test fun savedWinsOverEverything() =
        assertEquals(SaveUi.Saved, saveUi("abc", saved, com.spotifry.music.data.DownloadState.Idle))
    @org.junit.Test fun downloadingShowsProgress() =
        assertEquals(SaveUi.Saving(0.5f), saveUi("x", saved, com.spotifry.music.data.DownloadState.Downloading(0.5f)))
    @org.junit.Test fun failedShowsFailed() =
        assertEquals(SaveUi.Failed, saveUi("x", saved, com.spotifry.music.data.DownloadState.Failed("net")))
    @org.junit.Test fun otherwiseUnsaved() =
        assertEquals(SaveUi.Unsaved, saveUi("x", saved, com.spotifry.music.data.DownloadState.Idle))
}
