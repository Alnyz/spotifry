package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ListeningStatsTest {
    private val zone = ZoneOffset.UTC
    /** A Thursday. */
    private val now = at(2027, 3, 11, 21, 44)
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(zone).toEpochMilli()
    private fun song(id: String, artist: String = "Artist $id") = SongEntity(id, "Song $id", artist, 200, null, null, null, null)
    private fun row(when_: Long, id: String, seconds: Int, skipped: Boolean = false, artist: String = "Artist $id") = ListenRow(when_, id, seconds, skipped, song(id, artist))
    private fun stats(vararg r: ListenRow) = listeningStats(r.toList(), now, zone)!!
    private fun ListeningStats.period(id: PeriodId) = periods.first { it.id == id }

    @Test fun anEmptyLogHasNoStats() = assertNull(listeningStats(emptyList(), now, zone))

    @Test fun periodsAreFromMondayFromTheFirstAndFromTheStart() {
        val s = stats(
            row(at(2026, 11, 6), "a", 600),
            row(at(2027, 3, 1), "b", 600), // this month, last week
            row(at(2027, 3, 7, 23, 59), "c", 600), // Sunday night, still last week
            row(at(2027, 3, 8, 0, 0), "d", 600), // Monday
        )
        assertEquals(600L, s.period(PeriodId.WEEK).seconds)
        assertEquals(1800L, s.period(PeriodId.MONTH).seconds)
        assertEquals(2400L, s.period(PeriodId.ALL).seconds)
    }

    @Test fun minutesRoundAndAFewSecondsAreStillAMinute() {
        assertEquals(0, minutesOf(0))
        assertEquals(1, minutesOf(5))
        assertEquals(6, minutesOf(359))
    }

    @Test fun songsAreDifferentSongsHeardThirtySeconds() {
        val p = stats(row(at(2026, 1, 1), "z", 1), row(now - 3000, "a", 30), row(now - 2000, "a", 30), row(now - 1000, "b", 29)).period(PeriodId.WEEK)
        assertEquals(1, p.songs)
    }

    @Test fun skipsAreTheLinesMarkedSkipped() {
        val p = stats(row(at(2026, 1, 1), "z", 1), row(now - 2000, "a", 10, skipped = true), row(now - 1000, "b", 10)).period(PeriodId.WEEK)
        assertEquals(1, p.skips)
    }

    @Test fun playsCountOnlyListensOfThirtySeconds() {
        val t = stats(row(now - 3000, "a", 200), row(now - 2000, "a", 30), row(now - 1000, "a", 29)).period(PeriodId.ALL).topSongs.single()
        assertEquals(2, t.plays)
        assertEquals(259L, t.seconds)
    }

    @Test fun topListsGoByMinutesAndStopAtFive() {
        val rows = (1..7).map { row(now - it * 1000L, "s$it", it * 100) }.toTypedArray()
        val p = stats(*rows).period(PeriodId.ALL)
        assertEquals(listOf("s7", "s6", "s5", "s4", "s3"), p.topSongs.map { it.song.id })
        assertEquals(5, p.artists.size)
        assertEquals("Artist s7", p.artists[0].name)
    }

    @Test fun anArtistIsNamedAsOnTheSongRowWithoutTheTopicEnding() {
        val p = stats(row(now - 2000, "a", 60, artist = "Nadira - Topic"), row(now - 1000, "b", 60, artist = "Nadira"), row(now - 500, "c", 60, artist = "")).period(PeriodId.ALL)
        assertEquals(listOf(TopArtist("Nadira", 120)), p.artists)
    }

    @Test fun aLineWhoseSongIsMissingStillCountsInTheTotalsButNotInTheLists() {
        val p = stats(ListenRow(now - 2000, "gone", 300, false, null), row(now - 1000, "a", 60)).period(PeriodId.ALL)
        assertEquals(360L, p.seconds)
        assertEquals(listOf("a"), p.topSongs.map { it.song.id })
    }

    @Test fun hoursGoByWhenEachListenStartedInThePhonesTime() {
        val p = stats(row(at(2027, 3, 10, 1, 30), "a", 600), row(at(2027, 3, 10, 21), "b", 60)).period(PeriodId.ALL)
        assertEquals(600L, p.hours[1])
        assertEquals(60L, p.hours[21])
        assertEquals(1, p.peakHour)
        assertEquals("a", p.hourSongs[1]?.id)
        assertNull(p.hourSongs[2])
        // the same moments on a phone seven hours east
        val east = listeningStats(listOf(row(at(2027, 3, 10, 1, 30), "a", 600)), now, ZoneOffset.ofHours(7))!!
        assertEquals(600L, east.periods.last().hours[8])
    }

    @Test fun firstDayIsOneQuietPeriodWithNoDays() {
        val s = stats(row(now - 240_000, "a", 240))
        assertTrue(s.firstDay)
        assertEquals(listOf("So far"), s.periods.map { it.label })
        assertEquals(1, s.days.size)
        assertTrue(s.firstAt.startsWith("Today, "))
        assertEquals("a", s.first?.id)
    }

    @Test fun aFewDaysInThePeriodsStayMerged() {
        val s = stats(row(at(2027, 3, 8), "a", 600), row(at(2027, 3, 10), "b", 600))
        assertFalse(s.firstDay)
        assertEquals(listOf("So far"), s.periods.map { it.label })
        assertEquals(listOf(600L, 0L, 600L, 0L), s.days)
        assertEquals(1, s.longestRun)
        assertEquals("8 Mar, 12.00", s.firstAt)
    }

    @Test fun thisWeekSplitsOffFromTheSecondMondayAndThisMonthFromTheNextFirst() {
        // began this month, before this week
        assertEquals(listOf("This week", "All time"), stats(row(at(2027, 3, 2), "a", 60)).periods.map { it.label })
        // began last month, with some of this month before this week
        assertEquals(listOf("This week", "This month", "All time"), stats(row(at(2027, 2, 2), "a", 60), row(at(2027, 3, 2), "b", 60)).periods.map { it.label })
    }

    @Test fun aWeekWithNothingYetOpensOnTheNextPeriodWithListening() {
        val s = stats(row(at(2027, 1, 2), "a", 60), row(at(2027, 3, 2), "b", 60))
        assertEquals(0L, s.period(PeriodId.WEEK).seconds)
        assertEquals(PeriodId.MONTH, s.opensOn)
        assertEquals(PeriodId.ALL, stats(row(at(2027, 1, 2), "a", 60)).opensOn)
    }

    @Test fun theLongestRunIsDaysInARow() {
        val s = stats(row(at(2027, 3, 1), "a", 60), row(at(2027, 3, 2), "a", 60), row(at(2027, 3, 3), "a", 60), row(at(2027, 3, 9), "a", 60), row(at(2027, 3, 10), "a", 60))
        assertEquals(3, s.longestRun)
        assertEquals(5, s.daysPlayed)
        assertEquals(11, s.days.size)
    }

    @Test fun linesDatedAheadOfTodayDontBreakThePage() {
        for (ahead in listOf(1L, 2L, 400L)) {
            val s = stats(row(now + ahead * 86_400_000, "a", 60))
            assertEquals(1, s.days.size)
            assertEquals(60L, s.days[0])
        }
    }

    @Test fun aMonthHoldingTheSameAsTheWeekIsNotASecondTab() {
        // March 2027 begins on a Monday; on the 3rd the week and the month are the same days
        val third = at(2027, 3, 3, 12)
        val s = listeningStats(listOf(row(at(2027, 2, 2), "a", 60), row(at(2027, 3, 2), "b", 60)), third, zone)!!
        assertEquals(listOf("This week", "All time"), s.periods.map { it.label })
        // nothing heard in either yet: one empty tab, not two
        val empty = listeningStats(listOf(row(at(2027, 2, 2), "a", 60)), third, zone)!!
        assertEquals(listOf("This week", "All time"), empty.periods.map { it.label })
    }

    @Test fun aChangeOfClocksKeepsEveryDayOnce() {
        val york = java.time.ZoneId.of("America/New_York")
        // the clocks went forward on 14 March 2027
        val noon = { d: Int -> LocalDateTime.of(2027, 3, d, 12, 0).atZone(york).toInstant().toEpochMilli() }
        val s = listeningStats(listOf(row(noon(13), "a", 60), row(noon(14), "a", 60), row(noon(15), "a", 60)), noon(15), york)!!
        assertEquals(listOf(60L, 60L, 60L), s.days)
        assertEquals(3, s.longestRun)
    }

    @Test fun weekStartsOnMonday() {
        assertEquals(LocalDate.of(2027, 3, 8), weekStart(LocalDate.of(2027, 3, 14)))
        assertEquals(LocalDate.of(2027, 3, 8), weekStart(LocalDate.of(2027, 3, 8)))
    }

    @Test fun wordsForTimeAndHours() {
        assertEquals("4 min", longDuration(4))
        assertEquals("6 h 12 min", longDuration(372))
        assertEquals("12 days, 16 hours", longDuration(18240))
        assertEquals("1 day, 1 hour", longDuration(1500))
        assertEquals("12 a.m.", hourName(0))
        assertEquals("1 a.m.", hourName(1))
        assertEquals("12 p.m.", hourName(12))
        assertEquals("9 p.m.", hourName(21))
        assertEquals("Morning", hourKind(5)); assertEquals("Morning", hourKind(10))
        assertEquals("Afternoon", hourKind(11)); assertEquals("Afternoon", hourKind(16))
        assertEquals("Evening", hourKind(17)); assertEquals("Evening", hourKind(21))
        assertEquals("Night", hourKind(22)); assertEquals("Night", hourKind(4))
    }

    @Test fun theSunIsFullAtTwoHoursAndSmallBefore() {
        assertEquals(0f, sunScale(0))
        assertEquals(1f, sunScale(120))
        assertEquals(1f, sunScale(18240))
        assertTrue(sunScale(4) < .4f)
        assertTrue(sunScale(4) < sunScale(86))
    }
}
