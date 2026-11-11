package com.spotifry.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.home.HomeContent
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SplashHostTest {
    @get:Rule val compose = createComposeRule()

    private fun many(n: Int) = (1..n).map { Song("id$it", "Song $it", "Artist $it", 100, null).toEntity() }

    @Test fun aTouchWhileItPlaysEndsItAndStillReachesTheApp() {
        var taps = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SplashHost(SplashStart(), { SplashPage.HOME }) { Box(Modifier.fillMaxSize().clickable { taps++ }) { Text("app") } }
        }
        compose.mainClock.advanceTimeBy(200)
        assertTrue("playing", SplashTargets.active)
        compose.onNodeWithText("app").performClick()
        compose.mainClock.advanceTimeBy(50)
        assertEquals("the app got the tap", 1, taps)
        assertFalse("and the splash is over", SplashTargets.active)
    }

    @Test fun itEndsByItselfAndThenTakesNoTouches() {
        var taps = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SplashHost(SplashStart(), { SplashPage.HOME }) { Box(Modifier.fillMaxSize().clickable { taps++ }) { Text("app") } }
        }
        compose.mainClock.advanceTimeBy(SplashTime.CAP_MS + 100)
        assertFalse(SplashTargets.active)
        repeat(2) { compose.onNodeWithText("app").performClick() }
        assertEquals(2, taps)
    }

    @Test fun withoutAColdStartNothingIsInTheWay() {
        var taps = 0
        compose.setContent { SplashHost(null, { SplashPage.HOME }) { Box(Modifier.fillMaxSize().clickable { taps++ }) { Text("app") } } }
        compose.onNodeWithText("app").performClick()
        assertEquals(1, taps)
        assertFalse(SplashTargets.active)
    }

    @Test fun homeReportsWhereTheBarsLandAndStillWorksUnderTheSplash() {
        var played: List<Song>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusicTheme { SplashHost(SplashStart(), { SplashPage.HOME }) { HomeContent(many(4), setOf("id1"), { l, _, _ -> played = l }, {}, loading = true) } }
        }
        compose.mainClock.advanceTimeBy(100)
        val known = SplashTargets.rects.keys
        assertTrue(known.toString(), known.containsAll(listOf("greeting", "hero", "shortcut0", "shortcut2", "card0")))
        // the splash adds nothing for screen readers: the page's own loading shapes are all there is
        compose.mainClock.advanceTimeBy(SplashTime.CAP_MS)
        compose.onAllNodesWithContentDescription("Loading").onFirst().assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Play again").onFirst().performClick()
        assertEquals(4, played?.size)
    }

    /** Draws every phase of the flight on both pages, so the drawing itself runs, and checks the welcome page's places are heard. */
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun bothPagesDrawThroughTheWholeFlight() {
        var welcome by androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            MusicTheme {
                androidx.compose.runtime.key(welcome) {
                    SplashHost(SplashStart(), { if (welcome) SplashPage.WELCOME else SplashPage.HOME }) {
                        if (welcome) com.spotifry.music.ui.firstlaunch.WelcomeStep(emptyList(), {}, {})
                        else HomeContent(many(4), setOf("id1"), { _, _, _ -> }, {}, loading = true)
                    }
                }
            }
        }
        for (page in listOf(false, true)) {
            if (page) { welcome = true; compose.mainClock.advanceTimeByFrame() }
            var t = 0L
            while (t < SplashTime.END + 100) {
                compose.mainClock.advanceTimeBy(100); t += 100
                val v = view!!
                val shot = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                compose.runOnUiThread { v.draw(android.graphics.Canvas(shot)) }
                // the logo is drawn where the system splash had it: a white bar crosses the centre
                if (t == 100L) assertEquals(android.graphics.Color.WHITE, shot.getPixel(v.width / 2, v.height / 2))
            }
        }
        assertTrue(SplashTargets.rects.keys.toString(), SplashTargets.rects.keys.containsAll(listOf("welcome", "fresh", "record")))
    }

    @Test fun theShortcutShapeIsWhereTheRealRowComes() {
        var loading by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MusicTheme { SplashHost(SplashStart(), { null }) { HomeContent(many(4), setOf("id1"), { _, _, _ -> }, {}, loading = loading) } } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        val shape = SplashTargets.rects.getValue("shortcut0")
        loading = false
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val real = SplashTargets.rects.getValue("shortcut0")
        assertEquals(shape.top, real.top, 1f)
        assertEquals(shape.height, real.height, 1f)
        assertEquals(shape.width, real.width, 1f)
    }
}
