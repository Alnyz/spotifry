package com.spotifry.music.ui.spotifried

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.centerLeft
import androidx.compose.ui.test.centerRight
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.spotifried.CardId
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class SpotifriedScreenTest {
    @get:Rule val rule = createComposeRule()
    private val today = LocalDate.of(2026, 11, 10)
    private fun ms(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(at: Long, id: String) = ListenRow(at, id, 200, false, SongEntity(id, "Song $id", "Artist $id", 200, null, null, null, null))

    @Before fun forgetProviderFolders() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (cache.get(null) as MutableMap<*, *>).clear()
    }

    private var show by mutableStateOf(true)
    private var index = 0
    private fun shell(vararg cards: CardId) {
        index = 0
        rule.setContent {
            MusicTheme {
                var i by remember { mutableIntStateOf(0) }
                StoryShell(cards.toList(), i, { i = it; index = it }) { Text(it.name) }
            }
        }
    }

    private fun screen(rows: List<ListenRow>) {
        val vm = SpotifriedViewModel(flowOf(rows), flowOf(emptyList()), { today })
        rule.setContent { MusicTheme { SpotifriedScreen(vm, {}, 0.dp) } }
    }

    @Test fun barHasOneSegmentPerShownCard() {
        shell(CardId.INTRO, CardId.TOP_SONG, CardId.SUN)
        rule.onAllNodesWithTag("segment").assertCountEquals(3)
    }

    @Test fun tapRightGoesToTheNextCardAndLeftBack() {
        shell(CardId.INTRO, CardId.TOP_SONG, CardId.SUN)
        rule.onNodeWithText("INTRO").assertIsDisplayed()
        rule.onNodeWithTag("tap").performTouchInput { click(centerRight) }
        rule.onNodeWithText("TOP_SONG").assertIsDisplayed()
        rule.onNodeWithTag("tap").performTouchInput { click(centerRight) }
        rule.onNodeWithTag("tap").performTouchInput { click(centerRight) }
        assertEquals(2, index)
        rule.onNodeWithTag("tap").performTouchInput { click(centerLeft) }
        rule.onNodeWithText("TOP_SONG").assertIsDisplayed()
    }

    @Test fun tapLeftOnTheFirstCardStays() {
        shell(CardId.INTRO, CardId.TOP_SONG)
        rule.onNodeWithTag("tap").performTouchInput { click(centerLeft) }
        rule.onNodeWithText("INTRO").assertIsDisplayed()
    }

    @Test fun noCardsShowsNotEnoughListeningYet() {
        screen((1..3).map { row(ms(2026, 5, it), "a$it") })
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Not enough listening yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("7 more plays to go").assertIsDisplayed()
    }

    @Test fun nothingIsDecidedBeforeTheDataArrives() {
        val rows = MutableSharedFlow<List<ListenRow>>()
        val vm = SpotifriedViewModel(rows, flowOf(emptyList()), { today })
        rule.setContent { MusicTheme { SpotifriedScreen(vm, {}, 0.dp) } }
        rule.waitForIdle()
        rule.onNodeWithText("Not enough listening yet").assertDoesNotExist()
        rule.runOnIdle { runBlocking { rows.emit(emptyList()) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Not enough listening yet").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun pickingAnotherPeriodChangesTheCards() {
        screen((1..12).map { row(ms(2025, 5, it), "a$it") })
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Not enough listening yet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2025").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("10 more plays to go").assertIsDisplayed()
        rule.onNodeWithText("2025").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2025,", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("segment").assertCountEquals(5)
    }

    // 20 different songs over two months: intro, top song, hours, age, sun and type; no day, no months
    private val sixCards get() = (1..20).map { row(ms(2025, 5 + it / 11, it % 28 + 1), "a$it") }
    private val typeCard = "Share your story"

    private fun shareScreen(capture: suspend (Activity, CardId, SpotifriedState) -> Bitmap) {
        val vm = SpotifriedViewModel(flowOf(sixCards), flowOf(emptyList()), { today })
        rule.setContent { MusicTheme { SpotifriedScreen(vm, {}, 0.dp, capture) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2025").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("2025").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("segment").fetchSemanticsNodes().size == 6 }
        repeat(5) { rule.onNodeWithTag("tap").performTouchInput { click(centerRight) } }
        rule.mainClock.advanceTimeBy(6_000)
    }

    private fun sent() = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    @Test fun shareOnlySavesShownCards() {
        val ids = mutableListOf<CardId>()
        shareScreen { _, id, _ -> ids += id; Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        rule.onNodeWithText(typeCard).performClick()
        rule.waitUntil(5_000) { ids.size == 6 }
        assertEquals(listOf(CardId.INTRO, CardId.TOP_SONG, CardId.HOURS, CardId.AGE, CardId.SUN, CardId.TYPE), ids)
        rule.waitUntil(5_000) { sent() != null }
    }

    @Test fun shareShowsWorkingStateAndIgnoresSecondTap() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        shareScreen { _, _, _ -> calls++; gate.await(); Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        rule.onNodeWithText(typeCard).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Making pictures…").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Making pictures…").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Making pictures…").performClick()
        assertEquals(1, calls)
        gate.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(typeCard).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aFailedCaptureShowsAPlainMessageAndSharesNothing() {
        shareScreen { _, _, _ -> error("stalled") }
        rule.onNodeWithText(typeCard).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Couldn't make the pictures. Try again.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(typeCard).assertIsEnabled()
        assertNull(sent())
    }

    @Test fun leavingTheScreenCancelsTheShare() {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        var started = false
        show = true
        val vm = SpotifriedViewModel(flowOf(sixCards), flowOf(emptyList()), { today })
        rule.setContent { MusicTheme { if (show) SpotifriedScreen(vm, {}, 0.dp) { _, _, _ -> try { started = true; gate.await(); Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) } catch (e: CancellationException) { cancelled = true; throw e } } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2025").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("2025").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("segment").fetchSemanticsNodes().size == 6 }
        repeat(5) { rule.onNodeWithTag("tap").performTouchInput { click(centerRight) } }
        rule.mainClock.advanceTimeBy(6_000)
        rule.onNodeWithText(typeCard).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Making pictures…").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { started }
        rule.runOnIdle { show = false }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue(cancelled)
        assertNull(sent())
    }
}
