package com.spotifry.music.ui.spotifried

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.spotifry.music.data.TopSong
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.spotifried.WrappedPeriod
import com.spotifry.music.data.spotifried.stats
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class CardsTest {
    @get:Rule val rule = createComposeRule()
    private fun show(card: @Composable () -> Unit) = rule.setContent { MusicTheme { card() } }
    private val song = SongEntity("s", "Paper Lanterns", "Hollow Birds - Topic", 200, null, null, null, null)

    @Test fun introSaysTheYear() {
        show { IntroCard(stats(plays = 40, seconds = 9000, hours = List(24) { it * 60L }), still = true) }
        rule.onNodeWithText("2026,", substring = true).assertIsDisplayed()
        rule.onNodeWithText("played", substring = true).assertIsDisplayed()
    }

    @Test fun topSongShowsTitleArtistAndPlays() {
        show { TopSongCard(stats(plays = 40).copy(topSong = TopSong(song, 4600, 23)), still = true) }
        rule.onNodeWithText("Paper Lanterns").assertIsDisplayed()
        rule.onNodeWithText("Hollow Birds").assertIsDisplayed()
        rule.onNodeWithText("23").assertIsDisplayed()
    }

    @Test fun topSongWithoutASongDrawsNothing() {
        show { TopSongCard(stats(plays = 40), still = true) }
        rule.onNodeWithText("You couldn't", substring = true).assertDoesNotExist()
    }

    @Test fun hoursShowsTotalHoursAndLongestDay() {
        val days = List(365) { if (it == 61) 9 * 3600L else 3600L }
        val s = stats(plays = 400, seconds = 612 * 3600L, daySeconds = days).copy(longestDay = LocalDate.of(2026, 3, 3))
        show { HoursCard(s, still = true) }
        rule.onNodeWithText("612 hours", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Longest day: 9 hours, 3 March").assertIsDisplayed()
    }

    @Test fun introLeadFollowsThePeriod() {
        val d = LocalDate.of(2026, 1, 1)
        assertEquals("2026,", introLead(WrappedPeriod(d, d, "2026")))
        assertEquals("Your last 12 months,", introLead(WrappedPeriod(d, d, "Last 12 months")))
        assertEquals("Everything,", introLead(WrappedPeriod(d, d, "All time")))
    }

    @Test fun barsAreOnePerDayUpToTheCapAndKeepTheLongestDayTallest() {
        assertEquals(listOf(0f, .5f, 1f), barHeights(listOf(0, 100, 400)))
        val year = List(365) { if (it == 200) 400L else 100L }
        val bars = barHeights(year, cap = 120)
        assertEquals(120, bars.size)
        assertEquals(1f, bars.max())
        assertEquals(200 * 120 / 365, bars.indexOf(1f))
        assertEquals(120 * 200 / 365, barOf(200, 365, 120))
    }
}
