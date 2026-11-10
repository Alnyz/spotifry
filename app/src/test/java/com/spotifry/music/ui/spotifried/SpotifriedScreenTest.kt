package com.spotifry.music.ui.spotifried

import androidx.compose.material3.Text
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
}
