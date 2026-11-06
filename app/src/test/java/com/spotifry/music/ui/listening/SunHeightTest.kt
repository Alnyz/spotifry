package com.spotifry.music.ui.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunHeightTest {
    private fun hours(vararg at: Pair<Int, Long>) = List(24) { h -> at.toMap()[h] ?: 0L }

    @Test fun nothingHeardCutsTheBlankBottomButKeepsTheRing() {
        // ring radius .205 below the centre at .5, plus the glow: well under the full square
        assertEquals(.765f, sunHeightFraction(hours(), 0f), .001f)
    }

    @Test fun listeningAtMidnightLeavesTheBottomShort() {
        val f = sunHeightFraction(hours(0 to 3600), 1f)
        assertTrue("was $f", f < .8f)
    }

    @Test fun listeningAtNoonUsesTheWholeSquare() {
        assertEquals(1f, sunHeightFraction(hours(12 to 3600), 1f), .001f)
    }

    @Test fun aLongRayAnywhereInTheLowerHalfMakesRoomForIt() {
        val short = sunHeightFraction(hours(0 to 3600), 1f)
        assertTrue(sunHeightFraction(hours(0 to 3600, 15 to 3600), 1f) > short)
    }
}
