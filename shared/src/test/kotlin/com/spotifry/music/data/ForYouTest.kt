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

    // ---- seeds, candidates, the list

    private fun s(id: String, artist: String = "Artist $id") = Song(id, "Song $id", artist, 200, null)
    private fun w(id: String, weight: Double, artist: String = "Artist $id") = Weighted(s(id, artist), weight)

    @Test fun seedsAreOnePerArtistAndAtMostEight() {
        // 20 songs over 10 artists, song i weighs 100 - i
        val scored = (0 until 20).map { w("s$it", 100.0 - it, artist = "A${it % 10}") }
        val top14 = scored.take(10).map { it.song.id }.toSet() // the best per artist are s0..s9
        repeat(20) { seed ->
            val seeds = pickSeeds(scored, Random(seed))
            assertEquals(8, seeds.size)
            assertEquals(8, seeds.map { it.artist }.toSet().size)
            assertTrue(seeds.all { it.id in top14 })
        }
    }

    @Test fun seedsComeFromTheTop14Only() {
        val scored = (0 until 30).map { w("s$it", 100.0 - it) }
        repeat(20) { seed -> assertTrue(pickSeeds(scored, Random(seed)).all { it.id.drop(1).toInt() < 14 }) }
    }

    private val none = Blocked(emptySet(), emptySet())

    @Test fun candidateSharedBySeedsRanksHigher() {
        val related = mapOf("x" to listOf(s("c1"), s("c2")), "y" to listOf(s("c1"), s("q")), "z" to listOf(s("c1"), s("q2")))
        val c = newCandidates(related, emptySet(), emptySet(), none, emptyMap()).associate { it.song.id to it.weight }
        assertTrue(c["c1"]!! > c["c2"]!!)
    }

    @Test fun candidateWeightFormula() {
        val list = (0 until 10).map { s("c$it", artist = if (it == 0) "Fav" else "Other $it") }
        val c = newCandidates(mapOf("x" to list, "y" to list), emptySet(), emptySet(), none, mapOf("fav" to 4.0, "other 1" to 2.0))
            .associate { it.song.id to it.weight }
        assertEquals(2 + 0.5 + 0.3, c["c0"]!!, eps)
        assertEquals(2 + 0.25 + 0.3 * 0.9, c["c1"]!!, eps)
    }

    @Test fun heardKnownBlockedAndSeedsAreNotNew() {
        val related = mapOf("x" to listOf(s("h"), s("k"), s("b"), s("y"), s("ok")), "y" to listOf(s("x")))
        val c = newCandidates(related, setOf("h"), setOf("k"), Blocked(setOf("b"), emptySet()), emptyMap()).map { it.song.id }
        assertEquals(listOf("ok"), c)
    }

    @Test fun knownLeavesOutLast24hAndBlocked() {
        val entities = listOf("p", "recent", "blocked", "savedNever", "listNever").map { song(it) }
        val k = knownSongs(mapOf("p" to 2.0, "recent" to 3.0, "blocked" to 1.0), entities, setOf("savedNever"), setOf("listNever"),
            playedSince = setOf("recent"), blocked = Blocked(setOf("blocked"), emptySet())).associate { it.song.id to it.weight }
        assertEquals(mapOf("p" to 2.0, "savedNever" to 1.5, "listNever" to 1.0), k)
    }

    @Test fun listIs18NewAnd6Known() {
        val l = buildList((0 until 40).map { w("n$it", 1.0) }, (0 until 20).map { w("k$it", 1.0) }, Random(3))
        assertEquals(24, l.songs.size); assertEquals(18, l.newCount)
        assertEquals(18, l.songs.count { it.id.startsWith("n") })
    }

    @Test fun fewNewFillsWithKnownAndBack() {
        val a = buildList((0 until 5).map { w("n$it", 1.0) }, (0 until 30).map { w("k$it", 1.0) }, Random(3))
        assertEquals(24, a.songs.size); assertEquals(5, a.newCount)
        val b = buildList((0 until 30).map { w("n$it", 1.0) }, (0 until 2).map { w("k$it", 1.0) }, Random(3))
        assertEquals(24, b.songs.size); assertEquals(22, b.newCount)
    }

    @Test fun atMostTwoPerArtistNeverBackToBack() {
        val fresh = (0 until 60).map { w("n$it", if (it < 40) 5.0 else 1.0, artist = if (it < 40) "Big" else "A$it") }
        repeat(10) { seed ->
            val l = buildList(fresh, emptyList(), Random(seed)).songs
            assertTrue(l.count { it.artist == "Big" } <= 2)
            assertFalse(l.zipWithNext().any { (a, b) -> a.artist == b.artist })
        }
    }

    @Test fun underEightSongsGivesEmpty() {
        val l = buildList((0 until 4).map { w("n$it", 1.0) }, (0 until 3).map { w("k$it", 1.0) }, Random(3))
        assertTrue(l.songs.isEmpty())
    }

    @Test fun artistTasteSumsByArtistKey() {
        val t = artistTaste(mapOf("a" to 1.0, "b" to 2.0, "c" to 4.0)) { mapOf("a" to "X - Topic", "b" to "x", "c" to null)[it] }
        assertEquals(mapOf("x" to 3.0), t)
    }
}
