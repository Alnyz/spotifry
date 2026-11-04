package com.spotifry.music.ui.nowplaying

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        MusicTheme { NowPlayingContent(playing, save, {}, {}, {}, {}, {}, {}, {}, onSave, {}, {}) }
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
    @Test fun upNextIsReachable() { show(SaveUi.Unsaved); compose.onNodeWithText("Open queue").performScrollTo().assertIsDisplayed() }
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
