package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class TimeMixesTest {
    private val zone = ZoneOffset.UTC
    private fun at(hour: Int, day: Int = 1) = LocalDateTime.of(2027, 3, day, hour, 0).toInstant(zone).toEpochMilli()
    private fun song(id: String) = SongEntity(id, "Song $id", "Artist $id", 200, null, null, null, null)
    private fun row(hour: Int, id: String, seconds: Int = 120, day: Int = 1) = ListenRow(at(hour, day), id, seconds, false, song(id))
    /** [songs] different songs at [hour], the first one played [extra] more times. */
    private fun part(hour: Int, songs: Int, extra: Int = 4, prefix: String = "s$hour-") =
        (0 until songs).map { row(hour, "$prefix$it") } + (1..extra).map { row(hour, "${prefix}0", day = 1 + it) }

    @Test fun noHistoryNoMixes() = assertEquals(emptyList<TimeMix>(), timeMixes(emptyList(), zone))

    @Test fun tooFewSongsOrPlaysGiveNoMix() {
        assertTrue(timeMixes(part(8, MIX_MIN_SONGS - 1, extra = 10), zone).isEmpty())
        // enough songs, each heard once: too few plays
        assertTrue(timeMixes(part(8, MIX_MIN_SONGS, extra = 0), zone).isEmpty())
    }

    @Test fun shortListensAndRemovedSongsDontCount() {
        val short = (0 until 20).map { row(8, "x$it", seconds = 29) }
        val gone = (0 until 20).map { ListenRow(at(8), "g$it", 120, false, null) }
        assertTrue(timeMixes(short + gone, zone).isEmpty())
    }

    @Test fun mixIsTheMostPlayedSongsOfThatPart() {
        val m = timeMixes(part(8, 10) + row(9, "s8-3") + row(10, "s8-3"), zone).single()
        assertEquals("Morning", m.kind)
        assertEquals("Your mornings", m.title)
        assertEquals(listOf("s8-0", "s8-3"), m.songs.take(2).map { it.id })
        assertEquals(10, m.songs.size)
    }

    @Test fun mixIsCapped() = assertEquals(MIX_MAX_SONGS, timeMixes(part(8, MIX_MAX_SONGS + 5), zone).single().songs.size)

    @Test fun hoursFollowHourKind() {
        val m = timeMixes(part(4, 8) + part(23, 8, prefix = "n") + part(11, 8), zone)
        // 4 a.m. and 11 p.m. are both late night: one mix of 16 songs
        assertEquals(listOf("Night", "Morning"), m.map { it.kind })
        assertEquals("Your late nights", m[0].title)
        assertEquals("16 songs you play late at night", m[0].line)
    }

    @Test fun nowComesFirstThenTheStrongestOther() {
        val all = timeMixes(part(8, 8) + part(13, 8, extra = 20) + part(19, 8, extra = 10) + part(23, 8, extra = 6), zone)
        assertEquals(listOf("Afternoon", "Evening", "Night", "Morning"), all.map { it.kind })
        assertEquals(listOf("Morning", "Afternoon"), pickTimeMixes(all, 9).map { it.kind })
        assertEquals(listOf("Afternoon", "Evening"), pickTimeMixes(all, 14).map { it.kind })
    }

    @Test fun withoutAMixForNowTheStrongestShow() {
        val all = timeMixes(part(13, 8, extra = 20) + part(19, 8), zone)
        assertEquals(listOf("Afternoon", "Evening"), pickTimeMixes(all, 2).map { it.kind })
    }
}
