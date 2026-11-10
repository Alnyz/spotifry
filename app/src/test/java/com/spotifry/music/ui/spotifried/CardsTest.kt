package com.spotifry.music.ui.spotifried

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import com.spotifry.music.data.spotifried.MonthTop
import com.spotifry.music.data.spotifried.ObsessionDay
import com.spotifry.music.data.spotifried.ageStats

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
        val s = stats(plays = 4000, seconds = 1240 * 3600L, daySeconds = List(365) { 3600L })
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

    @Test fun systemBarIconsAreLightOnTheStoryAndRestoredAfter() {
        var view: android.view.View? = null
        var open by mutableStateOf(true)
        rule.setContent {
            MusicTheme(ThemeMode.LIGHT) {
                view = LocalView.current
                if (open) StoryTheme {}
            }
        }
        rule.waitForIdle()
        val bars = WindowCompat.getInsetsController((view!!.context as Activity).window, view!!)
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)
        open = false
        rule.waitForIdle()
        assertTrue(bars.isAppearanceLightStatusBars)
        assertTrue(bars.isAppearanceLightNavigationBars)
    }

    private fun ageOf(years: List<Int>, typical: Int?) =
        ageStats(typical, years.minOrNull(), years.size, years.size).copy(years = years.sorted())

    @Test fun ageCardShowsAgeAndTypicalYear() {
        val years = List(30) { 1975 + it } + List(10) { 1998 }
        show { AgeCard(stats(plays = 400, age = ageOf(years, 1998)), 40, 0, still = true) }
        rule.onNodeWithText("You listen like someone who was 14 in 1998.").assertIsDisplayed()
        rule.onNodeWithText("42").assertIsDisplayed()
        rule.onNodeWithText("1975, your oldest").assertIsDisplayed()
        rule.onNodeWithText("Most of your songs are from the 90s.", substring = true).assertIsDisplayed()
    }

    @Test fun ageCardShowsProgressWhileYearsAreMissing() {
        show { AgeCard(stats(plays = 400, age = ageOf(listOf(1990, 1991, 1999, 2004, 2020), null).copy(songsTotal = 25, unchecked = 20)), 5, 20, still = true) }
        rule.onNodeWithText("Checking song years: 5 of 25").assertIsDisplayed()
        rule.onNodeWithText("You listen like", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Your listening age", substring = true).assertDoesNotExist()
    }

    @Test fun ageCardReplaysWhenTheAgeArrives() {
        val years = List(25) { 1990 + it % 15 }
        var a by mutableStateOf(ageOf(years.take(5), null).copy(songsTotal = 25, unchecked = 20))
        show { AgeCard(stats(plays = 400, age = a), 25 - a.unchecked, a.unchecked) }
        rule.waitForIdle()
        rule.onNodeWithText("Checking song years", substring = true).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        a = ageOf(years, 1997)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("You listen like someone who was 14 in 1997.").assertIsDisplayed()
        rule.onNodeWithText("0").assertIsDisplayed() // counting up from the start, not already at the end
        rule.mainClock.advanceTimeBy(6000)
        rule.onNodeWithText("43").assertIsDisplayed()
    }

    @Test fun oneYearWithHundredsOfSongsStaysInItsArea() {
        val room = 120.dp
        assertTrue(stackStep(399, room) * 399 <= room)
        assertEquals(10.dp, stackStep(3, room))
        assertEquals(10.dp, stackStep(0, room))
        rule.setContent { MusicTheme { Box(Modifier.size(360.dp, 460.dp)) { AgeCard(stats(plays = 400, age = ageOf(List(400) { 2023 } + List(20) { 1999 }, 2023)), 420, 0, still = true) } } }
        rule.onNodeWithText("You listen like someone who was 14 in 2023.").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("Song years from MusicBrainz").getBoundsInRoot().bottom <= 460.dp)
    }

    @Test fun ageCardCreditsMusicBrainz() {
        show { AgeCard(stats(plays = 400, age = ageOf(List(20) { 2000 + it % 7 }, 2003)), 20, 0, still = true) }
        rule.onNodeWithText("Song years from MusicBrainz").assertIsDisplayed()
    }

    @Test fun ageCardWithExactlyTwentySongs() {
        val years = List(20) { 2010 + it % 5 }
        // the share of a phone with music playing, as in topSongCoverKeepsItsSizeOnAPhone
        rule.setContent { MusicTheme { Box(Modifier.size(360.dp, 460.dp)) { AgeCard(stats(plays = 400, age = ageOf(years, 2012)), 20, 0, still = true) } } }
        rule.onNodeWithText("You listen like someone who was 14 in 2012.").assertIsDisplayed()
        rule.onNodeWithText("28").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("Song years from MusicBrainz").getBoundsInRoot().bottom <= 460.dp)
    }

    @Test fun dotsStackPerYear() {
        assertEquals(listOf(1975 to 0, 1998 to 0, 1998 to 1, 1998 to 2), yearStacks(listOf(1998, 1975, 1998, 1998)))
        assertEquals("90s", decadeName(1990)); assertEquals("2000s", decadeName(2000)); assertEquals("1890s", decadeName(1890))
        assertEquals(1990, topDecade(listOf(1975, 1991, 1995, 2001)))
    }

    @Test fun dayCardShowsDateSongAndCount() {
        val day = ObsessionDay(song, LocalDate.of(2026, 3, 14), 8, List(8) { 20f + it * .1f })
        show { DayCard(stats(plays = 400, obsession = day), still = true) }
        rule.onNodeWithText("On 14 March, something happened.").assertIsDisplayed()
        rule.onNodeWithText("You played \u201cPaper Lanterns\u201d 8 times in one day. We won't ask.").assertIsDisplayed()
    }

    @Test fun dayCardSingularAndLongTitle() {
        val long = song.copy(title = "A very long song title that goes on and on and on, then some more words, and then even more")
        show { DayCard(stats(plays = 400, obsession = ObsessionDay(long, LocalDate.of(2026, 3, 14), 1, listOf(9f))), still = true) }
        rule.onNodeWithText("1 time in one day", substring = true).assertIsDisplayed()
        rule.onNodeWithText("\u2026\u201d", substring = true).assertIsDisplayed()
    }

    @Test fun dayTextAndShortTitles() {
        assertEquals("14 March", dayText(LocalDate.of(2026, 3, 14), withYear = false))
        assertEquals("14 March 2025", dayText(LocalDate.of(2025, 3, 14), withYear = true))
        assertEquals("Paper Lanterns", shortTitle("Paper Lanterns"))
        val cut = shortTitle("word ".repeat(30).trim())
        assertTrue(cut.length <= 61 && cut.endsWith("\u2026") && !cut.contains(" \u2026"))
        assertEquals("A " + "x".repeat(58) + "\u2026", shortTitle("A " + "x".repeat(80)))
    }

    private fun monthsOf(n: Int) = List(n) { MonthTop(YearMonth.of(2026, it + 1), song.copy(title = "Song number ${it + 1} with a title long enough to need cutting short"), 30 - it) }

    @Test fun monthsCardHighlightsTheChangeMonth() {
        val s = stats(plays = 400, months = monthsOf(3)).copy(changeMonth = YearMonth.of(2026, 2))
        show { MonthsCard(s, still = true) }
        rule.onNodeWithText("Your year,\nin order.").assertIsDisplayed()
        rule.onNodeWithTag("change_month").assert(hasAnyDescendant(hasText("Feb")))
        rule.onNodeWithText("Jan").assertIsDisplayed()
        rule.onNodeWithText("Mar").assertIsDisplayed()
    }

    @Test fun monthsCardFitsTwelveRows() {
        val s = stats(plays = 400, months = monthsOf(12)).copy(changeMonth = YearMonth.of(2026, 6))
        // 460 dp is the card's share of a 360 x 780 dp phone with music playing; the full 780 is roomier
        rule.setContent { MusicTheme { Box(Modifier.size(360.dp, 460.dp)) { MonthsCard(s, still = true) } } }
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
            assertTrue(it, rule.onNodeWithText(it).getBoundsInRoot().bottom <= 460.dp)
        }
    }

    @Test fun moreThanTwelveMonthsKeepTheChangeMonth() {
        val m = List(20) { MonthTop(YearMonth.of(2025, 1).plusMonths(it.toLong()), song, 5) }
        assertEquals(m.takeLast(12), shownMonths(m, null))
        assertEquals(m.subList(3, 15), shownMonths(m, m[3].month))
        assertEquals(m.take(3), shownMonths(m.take(3), m[1].month))
    }
}
