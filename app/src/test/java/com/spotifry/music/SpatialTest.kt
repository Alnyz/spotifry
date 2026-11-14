package com.spotifry.music

import com.spotifry.music.player.Spatial
import com.spotifry.music.player.SpatialSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialTest {
    @Test fun monoMixesLeftAndRight() {
        val s = Spatial().apply { mono = true }
        s.process(1f, 0f)
        assertEquals(.5f, s.outL, 1e-6f); assertEquals(.5f, s.outR, 1e-6f)
        s.process(.2f, .6f)
        assertEquals(.4f, s.outL, 1e-6f); assertEquals(.4f, s.outR, 1e-6f)
    }

    @Test fun offLeavesSoundAlone() {
        val s = Spatial()
        s.process(.3f, -.7f)
        assertEquals(.3f, s.outL, 0f); assertEquals(-.7f, s.outR, 0f)
    }

    @Test fun crossfeedLetsLeftReachTheRightEar() {
        val s = Spatial().apply { crossfeed = SpatialSetting.NATURAL }
        repeat(2000) { s.process(1f, 0f) } // only the left plays, a steady low sound
        assertTrue("right ear hears some: ${s.outR}", s.outR > .1f)
        assertTrue("left is a bit lower: ${s.outL}", s.outL < 1f && s.outL > s.outR)
    }

    @Test fun crossfeedDullsHighSounds() {
        fun crossing(hz: Double): Double {
            val s = Spatial().apply { crossfeed = SpatialSetting.NATURAL }
            var peak = 0.0
            for (n in 0 until 4000) { s.process(kotlin.math.sin(2 * Math.PI * hz * n / 44100).toFloat(), 0f); if (n > 2000) peak = maxOf(peak, abs(s.outR.toDouble())) }
            return peak
        }
        assertTrue(crossing(5000.0) < crossing(200.0) / 3)
    }

    @Test fun fullCrossfeedNeverGetsLouder() {
        val s = Spatial().apply { crossfeed = SpatialSetting.STRONG }
        repeat(2000) { s.process(1f, 1f); assertTrue(s.outL <= 1.0001f && s.outR <= 1.0001f) }
    }

    @Test fun amountZeroIsOff() {
        val s = Spatial().apply { crossfeed = SpatialSetting(700, 6, 0) }
        s.process(1f, 0f)
        assertEquals(1f, s.outL, 1e-6f); assertEquals(0f, s.outR, 1e-6f)
    }
}
