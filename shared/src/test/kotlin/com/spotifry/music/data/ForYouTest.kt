package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.random.Random

class ForYouTest {
    private val zone = ZoneOffset.UTC
    /** 20 March, noon. */
    private val now = LocalDateTime.of(2027, 3, 20, 12, 0).toInstant(zone).toEpochMilli()
    private val hourMs = 3_600_000L
    private val dayMs = 24 * hourMs
    private fun song(id: String, artist: String = "Artist $id", duration: Long = 200) = SongEntity(id, "Song $id", artist, duration, null, null, null, null)
    private fun row(ago: Long, id: String, seconds: Int = 200, skipped: Boolean = false, artist: String = "Artist $id", duration: Long = 200) =
        ListenRow(now - ago, id, seconds, skipped, song(id, artist, duration))
    private fun score(vararg rows: ListenRow, saved: Set<String> = emptySet(), lists: Set<String> = emptySet()) =
        tasteScores(rows.toList(), saved, lists, now, zone)
    private val eps = 1e-6

    // six hours ago: outside the ±2 h window around noon
    private val sixH = 6 * hourMs
    private val fadeSix = exp(-0.25 / 14)

    @Test fun fullListenTodayScoresAboutOne() = assertEquals(fadeSix, score(row(sixH, "a"))["a"]!!, eps)

    @Test fun halfListenScoresHalf() = assertEquals(fadeSix / 2, score(row(sixH, "a", seconds = 100))["a"]!!, eps)

    @Test fun fadesWithAge() = assertEquals(exp(-(14.25) / 14), score(row(14 * dayMs + sixH, "a"))["a"]!!, eps)

    @Test fun olderThan90DaysAndSkippedDontCount() {
        val s = score(row(91 * dayMs, "old"), row(sixH, "skip", seconds = 10, skipped = true))
        assertNull(s["old"]); assertNull(s["skip"])
    }

    @Test fun sameHourCountsOneAndAHalf() = assertEquals(1.5 * exp(-(1.0 / 24) / 14), score(row(hourMs, "a"))["a"]!!, eps)

    @Test fun hourWindowWrapsAroundMidnight() {
        val midnight = LocalDateTime.of(2027, 3, 20, 0, 30).toInstant(zone).toEpochMilli()
        val s = tasteScores(listOf(ListenRow(midnight - hourMs, "a", 200, false, song("a"))), emptySet(), emptySet(), midnight, zone)
        assertEquals(1.5 * exp(-(1.0 / 24) / 14), s["a"]!!, eps)
    }

    @Test fun unknownLengthUses180Seconds() = assertEquals(fadeSix / 2, score(row(sixH, "a", seconds = 90, duration = 0))["a"]!!, eps)

    @Test fun savedAndPlaylistBonusOnlyWithAListen() {
        val s = score(row(sixH, "a"), saved = setOf("a", "never"), lists = setOf("a", "never"))
        assertEquals(fadeSix + 1.5 + 1.0, s["a"]!!, eps)
        assertNull(s["never"])
    }

    @Test fun skipInLast14DaysBlocksTheSong() {
        val b = blocked(listOf(row(13 * dayMs, "recent", 5, true), row(15 * dayMs, "old", 5, true)), emptySet(), now)
        assertTrue("recent" in b.songs); assertFalse("old" in b.songs)
    }

    @Test fun threeSkipsBlockTheArtist() {
        val rows = (1..3).map { row(it * dayMs, "s$it", 5, true, artist = "Artist X - Topic") }
        val b = blocked(rows, emptySet(), now)
        assertTrue("artist x" in b.artists)
        assertTrue(b.blocks(Song("other", "T", "artist x", 100, null)))
        assertFalse(blocked(rows.take(2), emptySet(), now).blocks(Song("other", "T", "artist x", 100, null)))
    }

    @Test fun notSuggestedIsAlwaysBlocked() = assertTrue(blocked(emptyList(), setOf("n"), now).blocks(Song("n", "T", "A", 100, null)))

    @Test fun weightedOrderFavoursHeavierButNotAlways() {
        val r = Random(7)
        val firstA = (1..1000).count { weightedOrder(listOf("a", "b"), { if (it == "a") 10.0 else 1.0 }, r).first() == "a" }
        assertTrue("a first $firstA times", firstA in 850..960)
    }

    @Test fun weightedOrderPutsZeroWeightsLast() {
        val r = Random(1)
        repeat(50) { assertEquals("z", weightedOrder(listOf("z", "a", "b"), { if (it == "z") 0.0 else 1.0 }, r).last()) }
    }
}
