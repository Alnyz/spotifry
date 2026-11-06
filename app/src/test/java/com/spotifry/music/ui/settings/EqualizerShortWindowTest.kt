package com.spotifry.music.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.AutoEqSource
import com.spotifry.music.data.Settings
import com.spotifry.music.player.AUTOEQ_PREFIX
import com.spotifry.music.player.EQ_BANDS
import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.SpectrumTap
import com.spotifry.music.ui.theme.MusicTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqualizerShortWindowTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = Settings(context.getSharedPreferences("eq-short", 0))
    private val nowhere = AutoEqSource(OkHttpClient(), File(context.cacheDir, "eq-short"), base = "http://localhost:9/")

    /** The graph draws every frame, so the clock is moved by hand: it never goes idle by itself. */
    private fun open(width: Int, height: Int) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicTheme { Box(Modifier.size(width.dp, height.dp)) { EqualizerScreen(settings, SpectrumTap(), nowhere, false, {}, 0.dp) } } }
        settle()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(1500)

    @Test fun inAShortWindowOpeningThePageLeavesTheHeaderWhereItIs() {
        val (name, levels) = EQ_PRESETS.last()
        settings.setEq(name, levels)
        open(360, 232)
        assertEquals(4f, compose.onNodeWithContentDescription("Back").getBoundsInRoot().top.value, .5f)
    }

    @Test fun thePickedChipFarAlongTheRowIsInViewOnOpening() {
        val (name, levels) = EQ_PRESETS.last()
        settings.setEq(name, levels)
        open(320, 800)
        compose.onNodeWithText(name).assertIsDisplayed()
    }

    @Test fun customIsInViewOnOpeningWhenNoPresetIsPicked() {
        settings.setEq(null, List(EQ_BANDS) { 300 })
        open(320, 800)
        compose.onNodeWithText("Custom").assertIsDisplayed()
    }

    @Test fun withTheEqualizerOffACorrectionIsRemovedNotTurnedOff() {
        settings.setEqOn(false)
        settings.setEq(AUTOEQ_PREFIX + "HD 600", List(EQ_BANDS) { 100 })
        open(360, 900)
        compose.onNodeWithText("Headphone").performClick()
        settle()
        compose.onNodeWithText("Remove correction").assertExists()
        compose.onNodeWithText("Turn off correction").assertDoesNotExist()
        settings.setEqOn(true)
        settle()
        compose.onNodeWithText("Turn off correction").assertExists()
    }
}
