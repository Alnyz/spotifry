package com.spotifry.music.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotifry.music.data.testSong
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongMenuSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSwitchRowFlipsAndStaysOpenWhileOtherRowsClose() {
        var on by mutableStateOf(false)
        var plain = 0
        var closed = 0
        compose.setContent {
            MusicTheme {
                SongMenuSheet(testSong, listOf(
                    MenuAction("Keep screen on", switch = on) { on = !on },
                    MenuAction("Share") { plain++ },
                )) { closed++ }
            }
        }
        compose.onNodeWithText("Keep screen on").performClick()
        compose.waitForIdle()
        assertEquals(true, on)
        assertEquals(0, closed)
        compose.onNodeWithText("Keep screen on").assertIsDisplayed()
        compose.onNodeWithText("Share").performClick()
        assertEquals(1 to 1, plain to closed)
    }

    @Test fun theSwitchShowsItsState() {
        compose.setContent { MusicTheme { SongMenuSheet(testSong, listOf(MenuAction("Keep screen on", switch = true) {})) {} } }
        compose.onNode(isToggleable()).assertIsOn()
    }
}
