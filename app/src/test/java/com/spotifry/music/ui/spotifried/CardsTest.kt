package com.spotifry.music.ui.spotifried

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.ui.theme.contrast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.spotifry.music.data.TopSong
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.spotifried.WrappedPeriod
import com.spotifry.music.data.spotifried.stats
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun storyIsDarkOnTheLightTheme() {
        var bg = Color.Unspecified; var ink = Color.Unspecified; var accent = Color.Unspecified
        rule.setContent {
            MusicTheme(ThemeMode.LIGHT) {
                StoryTheme { MaterialTheme.colorScheme.let { bg = it.background; ink = LocalContentColor.current; accent = it.primary } }
            }
        }
        rule.waitForIdle()
        assertTrue(bg.luminance() < .01f)
        assertTrue(contrast(ink, bg) >= 4.5f)
        assertTrue(contrast(ink.copy(alpha = .64f).compositeOver(bg), bg) >= 4.5f)
        assertTrue(contrast(accent, bg) >= 4.5f)
    }

    @Test fun bigCountsHaveThousandsSeparators() {
        val s = stats(plays = 4000, seconds = 1240 * 3600L, daySeconds = List(365) { 3600L }).copy(topSong = TopSong(song, 999_000, 1240))
        show { HoursCard(s, still = true) }
        rule.onNodeWithText("1,240 hours.", substring = true).assertIsDisplayed()
    }

    @Test fun playCountHasThousandsSeparators() {
        show { TopSongCard(stats(plays = 4000).copy(topSong = TopSong(song, 999_000, 1240)), still = true) }
        rule.onNodeWithText("1,240").assertIsDisplayed()
    }

    @Test fun hoursLeadIsShortForEveryPeriod() {
        val d = LocalDate.of(2026, 1, 1)
        assertEquals("You gave 2026 ", hoursLead(WrappedPeriod(d, d, "2026")))
        assertEquals("Twelve months, ", hoursLead(WrappedPeriod(d, d, "Last 12 months")))
        assertEquals("All told, ", hoursLead(WrappedPeriod(d, d, "All time")))
    }

    @Test fun shortHistoryKeepsBarsThinAndLeft() {
        assertEquals(1 to 6.dp, barLayout(1, 308.dp))
        assertEquals(7 to 6.dp, barLayout(7, 308.dp))
        val (n, slot) = barLayout(366, 308.dp)
        assertEquals(123, n)
        assertEquals(308f / 123, slot.value, .001f)
    }

    @Test fun dateLabelsAreDistinct() {
        val mar = LocalDate.of(2026, 3, 1)
        assertEquals(listOf("1 Mar"), dateLabels(mar, 1, full = false))
        assertEquals(listOf("1 Mar – 7 Mar"), dateLabels(mar, 7, full = false))
        assertEquals(listOf("Jan", "Jul", "Dec"), dateLabels(LocalDate.of(2024, 1, 1), 366, full = true))
    }

    @Test fun oneDayOfHistoryShowsOneDate() {
        show { HoursCard(stats(plays = 12, seconds = 7200, daySeconds = listOf(7200L)).copy(start = LocalDate.of(2026, 3, 1), longestDay = LocalDate.of(2026, 3, 1)), still = true) }
        rule.onNodeWithText("1 Mar").assertIsDisplayed()
    }

    @Test fun topSongCoverKeepsItsSizeOnAPhone() {
        val long = song.copy(title = "A very long song title that will surely need two whole lines")
        // a card's share of a 360 x 780 dp phone: minus status bar 24, back 52, periods 48, progress 27 and the bottom bar with music playing 164
        rule.setContent { MusicTheme { Box(Modifier.size(360.dp, 460.dp)) { TopSongCard(stats(plays = 40).copy(topSong = TopSong(long, 4600, 23)), still = true) } } }
        rule.onNodeWithTag("top_cover").assertHeightIsAtLeast(200.dp)
    }
}
