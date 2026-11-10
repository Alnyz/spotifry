package com.spotifry.music.data.spotifried

import com.spotifry.music.data.db.ListenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

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
}
