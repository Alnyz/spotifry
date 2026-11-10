package com.spotifry.music.data.spotifried

import com.spotifry.music.data.db.ListenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SpotifriedStatsTest {
    private val today = LocalDate.of(2026, 12, 31)
    private val year = calendarYear(2026, today)
    private fun stats(vararg r: ListenRow, period: WrappedPeriod = year) = spotifriedStats(r.toList(), emptyMap(), period, testZone)

    @Test fun calendarYearEndsTodayUntilItEnds() {
        assertEquals(LocalDate.of(2027, 3, 11), calendarYear(2027, LocalDate.of(2027, 3, 11)).to)
        assertEquals(LocalDate.of(2026, 12, 31), calendarYear(2026, LocalDate.of(2027, 3, 11)).to)
        assertEquals(LocalDate.of(2026, 1, 1), calendarYear(2026, today).from)
    }

    @Test fun periodChoicesListYearsThenLast12MonthsThenAll() {
        val c = periodChoices(LocalDate.of(2025, 6, 1), LocalDate.of(2027, 3, 11))
        assertEquals(listOf("2027", "2026", "2025", "Last 12 months", "All time"), c.map { it.label })
        assertEquals(LocalDate.of(2025, 6, 1), c.last().from)
        assertEquals(LocalDate.of(2027, 3, 11), c.last().to)
    }

    @Test fun shortHistoryStartsAtFirstListen() {
        val s = stats(row(at(2026, 9, 1), "a", 60))
        assertEquals(LocalDate.of(2026, 9, 1), s.start)
        assertEquals(122, s.daySeconds.size)
    }

    @Test fun onlyListensInsideThePeriodCount() {
        val s = stats(row(at(2025, 12, 31), "a", 60), row(at(2026, 5, 5), "b", 60), row(at(2027, 1, 1), "c", 60))
        assertEquals(1, s.plays)
        assertEquals(60L, s.seconds)
    }

    @Test fun aListenUnder30SecondsIsNotAPlay() {
        val s = stats(row(at(2026, 5, 5), "a", 29))
        assertEquals(0, s.plays)
        assertEquals(0, s.songs)
        assertEquals(29L, s.seconds)
    }

    @Test fun topSongIsTheOneWithMostPlays() {
        val s = stats(row(at(2026, 5, 1), "a", 300), row(at(2026, 5, 2), "b", 40), row(at(2026, 5, 3), "b", 40), row(at(2026, 5, 4), "b", 40))
        assertEquals("b", s.topSong?.song?.id)
        assertEquals(3, s.topSong?.plays)
        assertEquals(2, s.songs)
        assertEquals(LocalDate.of(2026, 5, 1), s.longestDay)
        assertEquals(420L, s.hours[12])
    }

    @Test fun listenDatedAfterTodayIsLeftOut() {
        val p = calendarYear(2027, LocalDate.of(2027, 3, 11))
        val s = stats(row(at(2027, 3, 11), "a", 60), row(at(2027, 3, 12), "b", 60), period = p)
        assertEquals(1, s.plays)
        assertEquals(60L, s.seconds)
    }

    @Test fun aRowWithNoSongStillCountsInTheTotals() {
        val s = stats(ListenRow(at(2026, 5, 1), "gone", 300, false, null), row(at(2026, 5, 2), "a", 60))
        assertEquals(360L, s.seconds)
        assertEquals("a", s.topSong?.song?.id)
    }

    @Test fun emptyPeriodGivesZerosAndNoTopSong() {
        val s = stats()
        assertEquals(0, s.plays); assertEquals(0L, s.seconds); assertEquals(0, s.songs)
        assertNull(s.topSong); assertNull(s.longestDay)
        assertEquals(24, s.hours.size)
        assertEquals(365, s.daySeconds.size)
    }

    @Test fun obsessionIsTheSongPlayedMostInOneDay() {
        val rows = (0 until 23).map { row(at(2026, 3, 14, it % 24), "a", 60) } + row(at(2026, 3, 15), "b", 60)
        val o = stats(*rows.toTypedArray()).obsession!!
        assertEquals("a", o.song.id); assertEquals(LocalDate.of(2026, 3, 14), o.date)
        assertEquals(23, o.plays); assertEquals(23, o.startHours.size)
    }

    @Test fun obsessionTieGoesToTheEarlierDay() {
        val o = stats(row(at(2026, 5, 2), "b", 60), row(at(2026, 5, 1), "a", 60)).obsession!!
        assertEquals(LocalDate.of(2026, 5, 1), o.date)
    }

    @Test fun monthsHaveTheTopSongOfEachMonth() {
        val s = stats(row(at(2026, 1, 1), "a", 60), row(at(2026, 1, 2), "a", 60), row(at(2026, 1, 3), "b", 60),
            row(at(2026, 3, 1), "c", 60))
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 3)), s.months.map { it.month })
        assertEquals(listOf("a", "c"), s.months.map { it.song.id })
        assertEquals(2, s.months[0].plays)
    }

    @Test fun changeMonthIsWhereTheTopFiveChangedMost() {
        fun month(m: Int, ids: List<String>) = ids.map { row(at(2026, m, 1), it, 60) }
        val s = stats(*(month(1, listOf("a", "b", "c", "d", "e")) + month(2, listOf("a", "b", "c", "d", "x")) +
            month(3, listOf("p", "q", "r", "s", "t"))).toTypedArray())
        assertEquals(YearMonth.of(2026, 3), s.changeMonth)
    }

    @Test fun changeMonthNullWithOneMonth() {
        assertNull(stats(row(at(2026, 1, 1), "a", 60)).changeMonth)
    }

    @Test fun streakStaysInsideThePeriod() {
        val p = WrappedPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "2026")
        val s = stats(row(at(2025, 12, 29), "a", 60), row(at(2025, 12, 30), "a", 60), row(at(2025, 12, 31), "a", 60),
            row(at(2026, 1, 1), "a", 60), row(at(2026, 1, 2), "a", 60), row(at(2026, 1, 5), "a", 60), period = p)
        assertEquals(2, s.longestStreak)
    }

    @Test fun newSongsOnlyCountsFirstPlaysInThePeriod() {
        val s = stats(row(at(2025, 6, 1), "old", 60), row(at(2026, 2, 1), "old", 60), row(at(2026, 2, 2), "new", 60),
            row(at(2026, 2, 3), "new", 60))
        assertEquals(1, s.newSongs)
        assertEquals(1, s.replayedSongs)
    }

    @Test fun firstListenEverLooksAtEveryRowNotJustThePeriod() {
        assertEquals(LocalDate.of(2025, 6, 1), stats(row(at(2025, 6, 1), "old", 60), row(at(2026, 2, 1), "x", 60)).firstListenEver)
        assertNull(stats().firstListenEver)
    }

    @Test fun shareFiguresAreZeroNotNaNWhenThereAreNoPlays() {
        val s = stats(row(at(2026, 2, 1), "a", 10))
        assertEquals(0f, s.earlyShare, 0f); assertEquals(0f, s.nightShare, 0f)
        assertEquals(0f, stats().earlyShare, 0f)
    }

    @Test fun topArtistAndTimeOfDayShares() {
        val s = stats(row(at(2026, 2, 1, 8), "a", 300, "X - Topic"), row(at(2026, 2, 1, 2), "b", 100, "Y"),
            row(at(2026, 2, 1, 15), "c", 100, "Y"), row(at(2026, 2, 1, 20), "d", 100, "Y"))
        assertEquals("X", s.topArtist!!.name); assertEquals(0.5f, s.topArtist!!.share, 0.001f)
        assertEquals(0.5f, s.earlyShare, 0.001f); assertEquals(0.25f, s.nightShare, 0.001f)
    }

    private fun songRows(n: Int) = (1..n).map { row(at(2026, 3, 1 + it % 28), "s$it", 60) }
    private fun aged(n: Int, years: Map<String, Int>, checked: Set<String> = years.keys) =
        spotifriedStats(songRows(n), years, year, testZone, checked).age

    @Test fun medianOfOddAndEvenCounts() {
        assertEquals(2000, typicalYear(listOf(1990, 2000, 2010)))
        assertEquals(2000, typicalYear(listOf(2010, 1990, 2005, 2000)))
    }

    @Test fun ageIsEndYearMinusTypicalPlus14() = assertEquals(42, listeningAge(1998, 2026))

    @Test fun noAgeUnder20SongsWithAYear() {
        val a = aged(25, (1..19).associate { "s$it" to 1998 })!!
        assertEquals(19, a.songsWithYear)
        assertNull(a.typicalYear); assertNull(a.age)
    }

    @Test fun ageUsesTheMedianOfSongsWithAYear() {
        val a = aged(20, (1..20).associate { "s$it" to 1998 })!!
        assertEquals(1998, a.typicalYear); assertEquals(42, a.age)
    }

    @Test fun songsWithoutAYearAreCountedInTotalButNotInYears() {
        val a = aged(25, (1..5).associate { "s$it" to 2000 })!!
        assertEquals(25, a.songsTotal); assertEquals(5, a.songsWithYear); assertEquals(5, a.years.size)
    }

    @Test fun uncheckedCountsSongsWithoutAnyLookupRow() {
        val years = (1..5).associate { "s$it" to 2000 }
        val a = aged(25, years, years.keys + (6..15).map { "s$it" })!!
        assertEquals(10, a.unchecked); assertEquals(5, a.songsWithYear)
    }

    @Test fun oldestIsTheEarliestYear() {
        val a = aged(25, mapOf("s1" to 1985, "s2" to 1971, "s3" to 2001))!!
        assertEquals(1971, a.oldestYear); assertEquals("s2", a.oldestSong?.id)
    }
}
