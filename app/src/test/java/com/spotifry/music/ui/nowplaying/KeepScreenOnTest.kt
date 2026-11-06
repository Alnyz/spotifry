package com.spotifry.music.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepScreenOnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theScreenStaysAwakeOnlyWhileAskedAndWhileThisIsShowing() {
        var on by mutableStateOf(false)
        var showing by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            MusicTheme {
                view = LocalView.current
                if (showing) KeepScreenOn(on)
            }
        }
        assertFalse(view.keepScreenOn)
        on = true
        compose.waitForIdle()
        assertTrue(view.keepScreenOn)
        // a paused song: no reason to keep it lit
        on = false
        compose.waitForIdle()
        assertFalse(view.keepScreenOn)
        // leaving Now playing lets the screen sleep again, whatever was asked
        on = true
        compose.waitForIdle()
        assertTrue(view.keepScreenOn)
        showing = false
        compose.waitForIdle()
        assertFalse(view.keepScreenOn)
    }
}
