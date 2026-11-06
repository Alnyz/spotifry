package com.spotifry.music.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
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

    @Test fun inAVeryShortWindowTheButtonsStayAndEverythingElseScrolls() {
        val songs = List(30) { Song("id$it", "Song $it", "Ann", 100, null) }
        compose.setContent {
            MusicTheme { Box(Modifier.size(640.dp, 180.dp)) { ImportSharedPage(ShareText.Shared("Mine", songs, 0), { _, _, _ -> }, {}, {}) } }
        }
        compose.onNodeWithText("Play now").assertIsDisplayed()
        compose.onNodeWithText("Add to my playlists").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText("Song 29"))
        compose.onNodeWithText("Song 29").assertIsDisplayed()
    }
}
