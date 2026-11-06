package com.spotifry.music.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.Routes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FadeBarTest {
    @get:Rule val compose = createComposeRule()
    private val tabs = listOf(
        BarTab(Routes.LISTEN, "Home", Icons.Home), BarTab(Routes.SEARCH, "Search", Icons.Search),
        BarTab(Routes.SAVED, "Saved", Icons.Saved), BarTab(Routes.PLAYLISTS, "Playlists", Icons.List),
    )

    @After fun animationsBack() = animations(1f)
    private fun animations(scale: Float) {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    /** where the pill should rest for the tab at [index], in pixels from the row's left edge */
    private fun target(pill: PillState, index: Int): Pair<Float, Float> {
        val half = with(compose.density) { FadeBarSpec.PILL_W.toPx() / 2 }
        val centre = pill.width.toFloat() / tabs.size * (index + 0.5f)
        return centre - half to centre + half
    }
    private fun PillState.at(index: Int, tol: Float = 0.5f) = target(this, index).let { (l, r) -> abs(left.value - l) < tol && abs(right.value - r) < tol }

    @Test fun fadeIsLongEnoughAndTheTabsStaySolidEnough() {
        for (playing in listOf(true, false)) for (inset in listOf(0.dp, 16.dp, 48.dp)) {
            val stops = FadeBarSpec.stops(playing, inset)
            assertTrue("heights climb", stops.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertTrue("never more opaque going up", stops.zipWithNext().all { (a, b) -> b.second <= a.second })
            assertEquals(1f, stops.first().second)
            assertEquals(0f, stops.last().second)
            assertEquals("the fade ends where the lists' end padding does", FadeBarSpec.reserve(playing, inset), stops.last().first)
            val tabTop = inset + FadeBarSpec.TAB_ROW
            assertTrue("fade at least 32 dp", stops.last().first - (inset + FadeBarSpec.TAB_ROW + if (playing) 28.dp else 0.dp) >= 32.dp)
            assertTrue("the tab row is at least 88% solid", stops.filter { it.first <= tabTop }.all { it.second >= 0.88f })
        }
        assertEquals(164.dp, FadeBarSpec.reserve(true, 16.dp))
        assertEquals(104.dp, FadeBarSpec.reserve(false, 16.dp))
    }

    @Test fun eachScreenBelongsToItsTab() {
        fun on(route: String?) = tabs.filter { tabSelected(it.route, route) }.map { it.label }
        assertEquals(listOf("Home"), on(Routes.LISTEN))
        assertEquals(listOf("Home"), on(Routes.SETTINGS))
        assertEquals(listOf("Home"), on(Routes.EQUALIZER))
        assertEquals(listOf("Home"), on(Routes.LISTENING))
        assertEquals(listOf("Search"), on(Routes.SEARCH))
        assertEquals(listOf("Search"), on(Routes.MOOD))
        assertEquals(listOf("Search"), on(Routes.PAGE))
        assertEquals(listOf("Saved"), on(Routes.SAVED))
        assertEquals(listOf("Playlists"), on(Routes.PLAYLISTS))
        assertEquals(listOf("Playlists"), on(Routes.PLAYLIST))
    }

    @Test fun fourIconTabsNamedForScreenReaders_noLabelDrawn_selectedMarked_clickGoesToItsRoute() {
        var route by mutableStateOf<String?>(Routes.SEARCH)
        val clicked = mutableListOf<String>()
        compose.setContent { MaterialTheme { FadeBar(tabs, route, { clicked += it; route = it }, playing = false) } }
        tabs.forEach {
            compose.onNodeWithContentDescription(it.label).assert(SemanticsMatcher("is a tab") { n -> n.config.getOrNull(SemanticsProperties.Role) == Role.Tab })
            assertTrue("no visible label", compose.onAllNodes(hasText(it.label)).fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithContentDescription("Search").assertIsSelected()
        compose.onNodeWithContentDescription("Home").assertIsNotSelected()
        compose.onNodeWithContentDescription("Playlists").performClick()
        assertEquals(listOf(Routes.PLAYLISTS), clicked)
        compose.onNodeWithContentDescription("Playlists").assertIsSelected()
        compose.onNodeWithContentDescription("Search").assertIsNotSelected()
    }

    @Test fun capsuleShowsOnlyWhileASongIsLoaded() {
        var playing by mutableStateOf(false)
        compose.setContent { MaterialTheme { FadeBar(tabs, Routes.LISTEN, {}, playing) { Text("capsule") } } }
        assertTrue(compose.onAllNodes(hasText("capsule")).fetchSemanticsNodes().isEmpty())
        playing = true
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(hasText("capsule")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun solidPartSwallowsTouches_theFadeLetsThemThrough() {
        var under = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black).clickable { under++ })
                    FadeBar(tabs, Routes.LISTEN, {}, playing = false, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        val h = compose.onRoot().fetchSemanticsNode().size.height
        val d = with(compose.density) { FadeBarSpec.FADE.toPx() }
        val tabRow = with(compose.density) { FadeBarSpec.TAB_ROW.toPx() }
        // the 3 dp margin left of the first tab: nothing there to press, and the list underneath must not hear it
        compose.onRoot().performTouchInput { click(Offset(3f, h - tabRow / 2)) }
        assertEquals("margin of the solid part", 0, under)
        // inside the fade, above the tab row: the page is pressed
        compose.onRoot().performTouchInput { click(Offset(3f, h - tabRow - d / 2)) }
        assertEquals("fade zone", 1, under)
    }

    /** A real finger moves a pixel or two between down and up; a scripted `adb input tap` never does. */
    @Test fun aTapWithAFingerWobbleStillReachesTheTab() {
        val clicked = mutableListOf<String>()
        compose.setContent { MaterialTheme { FadeBar(tabs, Routes.LISTEN, { clicked += it }, playing = false) } }
        compose.onNodeWithContentDescription("Saved").performTouchInput { down(center); moveBy(Offset(2f, 1f)); moveBy(Offset(-1f, 2f)); up() }
        assertEquals(listOf(Routes.SAVED), clicked)
    }

    @Test fun aTapWithAFingerWobbleStillReachesTheCapsule() {
        var opened = 0
        compose.setContent { MaterialTheme { FadeBar(tabs, Routes.LISTEN, {}, playing = true) { androidx.compose.foundation.layout.Box(Modifier.size(200.dp, 56.dp).clickable { opened++ }) { Text("capsule") } } } }
        compose.onNodeWithText("capsule").performTouchInput { down(center); moveBy(Offset(2f, 1f)); up() }
        assertEquals(1, opened)
    }

    @Test fun pillRestsOnTheSelectedTab_andFollowsARouteChangeFromOutsideTheBar() {
        animations(1f)
        val pill = PillState()
        var route by mutableStateOf<String?>(Routes.SEARCH)
        compose.setContent { MaterialTheme { FadeBar(tabs, route, {}, false, pill = pill) } }
        compose.waitForIdle()
        assertTrue("starts on Search", pill.at(1))
        route = Routes.PLAYLIST   // the back gesture, a screen opened from elsewhere: no tap on the bar
        compose.waitForIdle()
        assertTrue("moved to Playlists", pill.at(3))
        route = Routes.LISTENING
        compose.waitForIdle()
        assertTrue("moved to Home", pill.at(0))
    }

    @Test fun pillTravelsRatherThanJumps() {
        animations(1f)
        val pill = PillState()
        var route by mutableStateOf<String?>(Routes.LISTEN)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { FadeBar(tabs, route, {}, false, pill = pill) } }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(pill.at(0))
        compose.runOnUiThread { route = Routes.PLAYLISTS; Snapshot.sendApplyNotifications() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }; compose.mainClock.advanceTimeBy(60)
        assertFalse("not there yet", pill.at(3, tol = 4f))
        assertTrue("on its way", pill.right.value > target(pill, 0).second)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("settled", pill.at(3))
    }

    @Test fun withAnimationsRemovedThePillSnapsAtOnce() {
        animations(0f)
        val pill = PillState()
        var route by mutableStateOf<String?>(Routes.LISTEN)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { FadeBar(tabs, route, {}, false, pill = pill) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread { route = Routes.SAVED; Snapshot.sendApplyNotifications() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("already on Saved", pill.at(2))
    }

    @Test fun quickTapsEndOnTheLastTab() {
        animations(1f)
        val pill = PillState()
        var route by mutableStateOf<String?>(Routes.LISTEN)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { FadeBar(tabs, route, { route = it }, false, pill = pill) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Search").performClick(); compose.mainClock.advanceTimeBy(70)
        compose.onNodeWithContentDescription("Playlists").performClick(); compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithContentDescription("Saved").performClick(); compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithContentDescription("Home").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue("on Home, not stuck between", pill.at(0))
    }
}
