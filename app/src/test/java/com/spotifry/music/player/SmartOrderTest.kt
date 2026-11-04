package com.spotifry.music.player

import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartOrderTest {
    private fun song(id: String, artist: String) = Song(id, id, artist, 200, null)

    // the made-up playlist from the design: one artist three times, one twice, three on their own
    private val mix = listOf(
        song("harbour", "The Hollow Pines"), song("glass", "Juno Reyes"), song("kites", "Juno Reyes"), song("sun", "Juno Reyes"),
        song("static", "Northbound"), song("salt", "Ada Kova"), song("tide", "The Hollow Pines"), song("neon", "Fennick"),
    )

    @Test fun keepsEverySongOnce() {
        repeat(50) { seed -> assertEquals(mix.sortedBy { it.id }, spreadShuffle(mix, emptySet(), Random(seed)).sortedBy { it.id }) }
    }

    @Test fun noArtistBackToBack() {
        repeat(200) { seed ->
            val out = spreadShuffle(mix, emptySet(), Random(seed))
            assertTrue("seed $seed: ${out.map { it.id }}", out.zipWithNext().none { (a, b) -> a.artist == b.artist })
        }
    }

    @Test fun heardTodayLandsInSecondHalf() {
        repeat(200) { seed ->
            val out = spreadShuffle(mix, setOf("neon", "tide"), Random(seed))
            assertTrue("seed $seed: ${out.map { it.id }}", out.take(4).none { it.id == "neon" || it.id == "tide" })
            assertTrue(out.zipWithNext().none { (a, b) -> a.artist == b.artist })
        }
    }

    @Test fun heardMostOfItTodaySkipsTheRule() {
        val recent = mix.take(5).map { it.id }.toSet()
        val firsts = (0 until 50).map { spreadShuffle(mix, recent, Random(it)).first().id }
        assertTrue(firsts.any { it in recent })
    }

    @Test fun sameSeedSameOrder() {
        assertEquals(spreadShuffle(mix, setOf("neon"), Random(7)), spreadShuffle(mix, setOf("neon"), Random(7)))
    }

    @Test fun edgeCases() {
        assertEquals(emptyList<Song>(), spreadShuffle(emptyList(), emptySet(), Random(1)))
        assertEquals(listOf(mix[0]), spreadShuffle(listOf(mix[0]), setOf("harbour"), Random(1)))
        val one = (1..6).map { song("s$it", "Solo") }
        assertEquals(one.toSet(), spreadShuffle(one, emptySet(), Random(1)).toSet())
    }

    @Test fun oneExtraAfterEveryThree() {
        assertEquals(listOf(1, 2, 3, 0, 4, 5, 6, 0, 7), interleave(listOf(1, 2, 3, 4, 5, 6, 7), listOf(0, 0, 0)))
        assertEquals(listOf(1, 2), interleave(listOf(1, 2), listOf(0)))
    }

    @Test fun queueDragSetsWhatPlaysNext() {
        // played 4 and 0, playing 2, then 3 1 5; the user drags 5 to the front
        val order = listOf(4, 0, 2, 3, 1, 5)
        assertEquals(listOf(4, 0, 2, 5, 3, 1), withUpcoming(order, 2, listOf(5, 3, 1)))
        // a suggestion (6) added meanwhile stays after the songs the user arranged
        assertEquals(listOf(4, 0, 2, 5, 3, 1, 6), withUpcoming(order + 6, 2, listOf(5, 3, 1)))
        // Undo put the song back among the played ones (shuffle inserts at random): it comes up again where it was
        assertEquals(listOf(4, 0, 2, 3, 1, 5), withUpcoming(listOf(4, 3, 0, 2, 1, 5), 2, listOf(3, 1, 5)))
        assertEquals(null, withUpcoming(order, 2, listOf(2, 3)))
        assertEquals(null, withUpcoming(order, 2, listOf(3, 9)))
        assertEquals(null, withUpcoming(order, 2, listOf(3, 3)))
    }
}
