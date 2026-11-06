package com.spotifry.music.ui.share

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spotifry.music.data.ShareText
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportSharedPageTest {
    @get:Rule val compose = createComposeRule()

    private val songs = List(30) { Song("id$it", "Song $it", "Ann", 100, null) }

    private fun open(height: Int) = compose.setContent {
        MusicTheme { Box(Modifier.size(640.dp, height.dp)) { ImportSharedPage(ShareText.Shared("Mine", songs, 0), { _, _, _ -> }, {}, {}) } }
    }

    @Test fun inAShortWindowTheButtonsStayAndEverythingElseScrolls() {
        open(240)
        compose.onNodeWithText("Play now").assertIsDisplayed()
        compose.onNodeWithText("Add to my playlists").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Song 29"))
        compose.onNodeWithText("Song 29").assertIsDisplayed()
    }

    @Test fun inASmallSplitScreenWithNoKeyboardTheButtonsStay() {
        open(180)
        compose.onNodeWithText("Add to my playlists").assertIsDisplayed()
    }

    @Test fun whenTheKeyboardLeavesRoomOnlyForTheNameTheButtonsStepAside() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            MusicTheme { Box(Modifier.size(640.dp, 108.dp)) { ImportSharedPage(ShareText.Shared("Mine", songs, 0), { _, _, _ -> }, {}, {}) } }
        }
        compose.onNodeWithText("Play now").assertExists()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 1)).setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.onNodeWithText("Play now").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
