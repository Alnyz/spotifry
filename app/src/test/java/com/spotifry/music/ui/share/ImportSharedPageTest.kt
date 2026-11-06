package com.spotifry.music.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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

    @Test fun whenOnlyTheNameCanFitTheButtonsStepAside() {
        // what's left in landscape with the keyboard up: the page's header and a little more
        open(108)
        compose.onNodeWithText("Play now").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
