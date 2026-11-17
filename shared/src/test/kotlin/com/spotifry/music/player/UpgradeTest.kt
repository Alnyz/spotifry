package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeTest {
    @Test fun noSoundForEightSecondsAtTheStartFallsBack() {
        val w = StallWatch(); w.newSong(0)
        assertFalse(w.tooLong(7_999)); assertTrue(w.tooLong(8_000))
        // sound came: no more waiting
        w.playing(); assertFalse(w.tooLong(60_000))
    }

    @Test fun aStallOfFourSecondsFallsBack() {
        val w = StallWatch(); w.newSong(0); w.playing()
        assertFalse(w.stalled(10_000))
        assertFalse(w.tooLong(13_999)); assertTrue(w.tooLong(14_000))
    }

    @Test fun twoStallsWithinThirtySecondsFallBackButNotFurtherApart() {
        val w = StallWatch(); w.newSong(0); w.playing()
        assertFalse(w.stalled(1_000)); w.playing()
        assertTrue(w.stalled(20_000)); w.playing()
        val v = StallWatch(); v.newSong(0); v.playing()
        assertFalse(v.stalled(1_000)); v.playing()
        assertFalse(v.stalled(40_000))
    }

    @Test fun aNewSongStartsCountingAfresh() {
        val w = StallWatch(); w.newSong(0); w.playing(); w.stalled(1_000); w.playing()
        w.newSong(5_000); w.playing()
        assertFalse(w.stalled(6_000))
    }

    @Test fun aDownloadCutShortIsNotAWholeSong() {
        val out = java.io.File.createTempFile("whole", ".part").apply { deleteOnExit() }
        fun reader(bytes: Int): (ByteArray) -> Int { var left = bytes; return { b -> if (left <= 0) -1 else minOf(left, b.size).also { left -= it } } }
        assertTrue(fetchWhole(100_000, reader(100_000), out) { true })
        assertEquals(100_000L, out.length())
        assertFalse(fetchWhole(100_000, reader(60_000), out) { true })
        assertFalse(fetchWhole(-1, reader(0), out) { true })
    }
}
