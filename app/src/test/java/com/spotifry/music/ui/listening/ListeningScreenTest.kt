package com.spotifry.music.ui.listening

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.ListeningStats
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.listeningStats
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class ListeningScreenTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val now = at(2027, 3, 11, 21, 44)
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(zone).toEpochMilli()
    private fun row(w: Long, id: String, s: Int, artist: String = "Artist $id", skipped: Boolean = false) = ListenRow(w, id, s, skipped, SongEntity(id, "Song $id", artist, 200, null, null, null, null))
    private var artist: String? = null
    private var played: List<String>? = null

    private fun show(vararg r: ListenRow) {
        val stats: ListeningStats = listeningStats(r.toList(), now, zone)!!
        rule.setContent {
            MusicTheme {
                ListeningContent(stats, emptySet(), false, {}, { l, _, _ -> played = l.map { it.id } }, {}, { artist = it }, 0.dp)
            }
        }
    }
    private fun scrollTo(text: String) { rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)); rule.onNodeWithText(text).assertIsDisplayed() }
    private fun missing(text: String) = rule.onNodeWithText(text).assertDoesNotExist()

    @Test fun firstDayIsASparkWithNoTabsNoDaysAndOneFirstSong() {
        show(row(now - 240_000, "a", 240))
        rule.onNodeWithText("First spark.").assertIsDisplayed()
        rule.onNodeWithText("4 minutes in. This grows as you listen.").assertIsDisplayed()
        missing("So far")
        missing("Every day you've played")
        scrollTo("Your top songs show up once you've heard three.")
        scrollTo("It started here")
        scrollTo("Kept only on this phone.")
        missing("You keep coming back")
    }

    @Test fun aFewDaysInShowTheSongsAndWaitForTheArtists() {
        val r = listOf("a", "b", "c").mapIndexed { i, id -> row(at(2027, 3, 9, 21, i), id, 300, artist = "Same") }.toTypedArray()
        show(*r)
        rule.onNodeWithText("Evening listener.").assertIsDisplayed()
        missing("So far")
        scrollTo("You keep coming back")
        scrollTo("Your top artists show up once you've heard three.")
        scrollTo("Every day you've played")
        missing("The ones you play most")
    }

    @Test fun anEmptyWeekOpensOnTheMonthAndTappingItSaysSo() {
        show(row(at(2027, 1, 2, 23), "a", 600), row(at(2027, 3, 2, 23), "b", 600))
        rule.onNodeWithText("Night listener.").assertIsDisplayed()
        rule.onNodeWithText("This week").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Still warm.").assertIsDisplayed()
        rule.onNodeWithText("Nothing yet this week. Play something.").assertIsDisplayed()
        missing("ads. Not one.")
        scrollTo("Every day you've played")
    }

    @Test fun aPeriodChangesTheTitleAndTheLists() {
        show(
            row(at(2027, 1, 2, 1), "old1", 9000), row(at(2027, 1, 2, 1, 5), "old2", 8000), row(at(2027, 1, 2, 1, 9), "old3", 7000),
            row(at(2027, 3, 9, 21), "new1", 3000), row(at(2027, 3, 9, 21, 5), "new2", 2000), row(at(2027, 3, 9, 21, 9), "new3", 1000),
        )
        rule.onNodeWithText("Evening listener.").assertIsDisplayed()
        scrollTo("Song new1")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("All time"))
        rule.onNodeWithText("All time").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Night listener."))
        rule.onNodeWithText("Night listener.").assertIsDisplayed()
        scrollTo("Song old1")
    }

    @Test fun theTallySaysSkipsSongsAndNoAds() {
        show(row(now - 3000, "a", 60), row(now - 2000, "b", 10, skipped = true), row(now - 1000, "c", 10, skipped = true))
        scrollTo("different song")
        scrollTo("skips. Nobody stopped you.")
        scrollTo("ads. Not one.")
    }

    @Test fun tappingAnArtistSearchesForTheName() {
        val r = (1..3).map { row(now - it * 100_000L, "s$it", 400 - it, artist = "Band $it") }.toTypedArray()
        show(*r)
        // the first artist's name is also on the no. 1 card, so the second is the one only the list has
        scrollTo("Band 2")
        rule.onNodeWithText("Band 2").performClick()
        assertEquals("Band 2", artist)
    }

    @Test fun theNumberOnePlaysAllTheTopSongs() {
        val r = (1..3).map { row(now - it * 100_000L, "s$it", 400 - it) }.toTypedArray()
        show(*r)
        scrollTo("Your no. 1")
        rule.onNodeWithText("Your no. 1").performClick()
        assertEquals(listOf("s1", "s2", "s3"), played)
    }

    @Test fun tappingTheSunShowsThatHourAndTappingTheMiddleGoesBack() {
        show(row(at(2027, 3, 10, 1), "a", 600), row(at(2027, 3, 10, 13), "b", 60))
        val sun = rule.onNodeWithContentDescription("Your listening by hour of the day", substring = true)
        sun.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .5f, height * .98f)) } // the bottom: noon, 12 p.m.
        rule.waitForIdle()
        rule.onNodeWithText("12 p.m.").assertExists()
        sun.performTouchInput { click(center) }
        rule.waitForIdle()
        missing("12 p.m.")
    }

    @Test fun anHourIsFoundFromWhereTheTapFell() {
        assertEquals(0, hourAt(50f, 0f, 100f))
        assertEquals(6, hourAt(100f, 50f, 100f))
        assertEquals(12, hourAt(50f, 100f, 100f))
        assertEquals(18, hourAt(0f, 50f, 100f))
        assertEquals(0, hourAt(52f, 0f, 100f))
        assertNull(hourAt(50f, 50f, 100f))
    }
}
