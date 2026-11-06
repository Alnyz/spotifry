package com.spotifry.music.ui.saved

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spotifry.music.data.RestoreStatus
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreCardTest {
    @get:Rule val compose = createComposeRule()

    private var asked = mutableListOf<Boolean>()
    private var stopped = 0
    private var forgotten = 0

    private fun show(left: Int, status: RestoreStatus) = compose.setContent {
        MusicTheme { RestoreCard(left, status, { asked += it }, { stopped++ }, { forgotten++ }) }
    }

    @Test fun waitingAsksFirstAndDefaultsToWifiOnly() {
        show(340, RestoreStatus.Idle)
        compose.onNodeWithText("340 songs were saved on your old phone").assertIsDisplayed()
        compose.onNodeWithText("Download 340 songs").performClick()
        assertEquals(listOf(true), asked)
    }

    @Test fun wifiOnlyCanBeTurnedOff() {
        show(1, RestoreStatus.Idle)
        compose.onNodeWithText("1 song was saved on your old phone").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi only").performClick()
        compose.onNodeWithText("Download 1 song").performClick()
        assertEquals(listOf(false), asked)
    }

    @Test fun runningOffersOnlyStop() {
        show(3, RestoreStatus.Running)
        compose.onNodeWithText("3 songs left. You can keep listening.").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
        compose.onNodeWithText("Download 3 songs").assertDoesNotExist()
    }

    @Test fun failureSaysHowManyAndCanBeForgotten() {
        show(2, RestoreStatus.Failed(2))
        compose.onNodeWithText("2 songs didn't download").assertIsDisplayed()
        compose.onNodeWithText("Forget them").performClick()
        assertEquals(1, forgotten)
    }

    @Test fun cardShowsOnSavedListsOnly() {
        var filter by mutableStateOf(SavedFilter.All)
        compose.setContent {
            MusicTheme {
                SavedContent(SavedView(filter), {}, {}, { _, _ -> }, {}, restoreCard = { androidx.compose.material3.Text("the card") })
            }
        }
        compose.onNodeWithText("the card").assertIsDisplayed()
        filter = SavedFilter.Lately
        compose.onNodeWithText("the card").assertDoesNotExist()
    }
}
