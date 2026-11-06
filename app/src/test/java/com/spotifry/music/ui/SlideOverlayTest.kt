package com.spotifry.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlideOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onceClosedItTakesNoTouchesButIsStillDrawnWhileItSlidesAway() {
        var open by mutableStateOf(true)
        var behind = 0
        var overlay = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { behind++ }) { Text("behind") }
                SlideOverlay(open) { Box(Modifier.fillMaxSize().clickable { overlay++ }) { Text("overlay") } }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("overlay").performClick()
        assertEquals("open: the overlay takes the tap", 1, overlay)
        assertEquals(0, behind)

        compose.runOnUiThread { open = false; Snapshot.sendApplyNotifications() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }   // a few frames into the 200 ms slide-out
        assertTrue("still drawn while it slides away", compose.onAllNodesWithText("overlay").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("behind").performClick()
        assertEquals("closing: the tap goes to what is underneath", 1, behind)
        assertEquals(1, overlay)

        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("gone once it has slid out", compose.onAllNodesWithText("overlay").fetchSemanticsNodes().isEmpty())
    }
}
