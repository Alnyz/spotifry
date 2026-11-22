package com.spotifry.music.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlwaysDarkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textInsideIsLightEvenInTheLightTheme() {
        var text = Color.Unspecified
        compose.setContent {
            // the light theme's dark text colour, as a page in light mode passes it down
            CompositionLocalProvider(LocalContentColor provides Color(0xFF15181C)) {
                AlwaysDark(Color(0xFF0C0D10), systemBars = false) { text = LocalContentColor.current }
            }
        }
        compose.waitForIdle()
        assertTrue("text colour $text", text.luminance() > .5f)
    }
}
