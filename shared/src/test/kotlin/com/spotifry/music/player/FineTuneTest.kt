package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class FineTuneTest {
    private fun tuner(s: FineTuneSetting) = FineTune().apply { configure(48_000); setting = s }

    /** Peak of the left channel for a sine of [hz] fed equally to both sides, after the filters settle. */
    private fun peak(s: FineTuneSetting, hz: Double, amp: Float = 0.1f): Float {
        val t = tuner(s); var p = 0f
        for (i in 0 until 48_000) {
            val x = (amp * sin(2 * PI * hz * i / 48_000)).toFloat()
            t.process(x, x)
            if (i > 24_000) p = max(p, abs(t.outL))
        }
        return p
    }

    @Test fun `neutral setting is flagged`() { assertTrue(FineTuneSetting().neutral); assertTrue(!FineTuneSetting(gainDb = 1).neutral) }

    @Test fun `balance right turns the left side down`() {
        val t = tuner(FineTuneSetting(balance = 100)); t.process(.5f, .5f)
        assertEquals(0f, t.outL, 1e-6f); assertEquals(.5f, t.outR, 1e-6f)
    }

    @Test fun `width zero is mono and 200 pushes sides apart`() {
        val m = tuner(FineTuneSetting(width = 0)); m.process(.4f, 0f)
        assertEquals(m.outL, m.outR, 1e-6f)
        val w = tuner(FineTuneSetting(width = 200)); w.process(.4f, 0f)
        assertEquals(.6f, w.outL, 1e-5f); assertEquals(-.2f, w.outR, 1e-5f)
    }

    @Test fun `gain 6 dB doubles the level`() { assertEquals(.2f, peak(FineTuneSetting(gainDb = 6), 1000.0), .005f) }

    @Test fun `high-pass removes 50 Hz and keeps 5 kHz`() {
        val s = FineTuneSetting(highPassHz = 200)
        assertTrue(peak(s, 50.0) < .1f * .15f); assertEquals(.1f, peak(s, 5000.0), .01f)
    }

    @Test fun `low-pass removes 12 kHz and keeps 200 Hz`() {
        val s = FineTuneSetting(lowPassHz = 2000)
        assertTrue(peak(s, 12000.0) < .1f * .1f); assertEquals(.1f, peak(s, 200.0), .01f)
    }

    @Test fun `night mode brings quiet and loud closer together`() {
        val quiet = peak(FineTuneSetting(night = 2), 1000.0, .02f); val loud = peak(FineTuneSetting(night = 2), 1000.0, .5f)
        assertTrue(loud / quiet < (.5f / .02f) * .5f) // the 25 to 1 gap is at least halved
        assertTrue(loud <= FineTune.CEILING + 1e-3f)
    }

    @Test fun `limiter keeps a boosted loud sound under the ceiling`() {
        assertTrue(peak(FineTuneSetting(gainDb = 15, limiter = true), 1000.0, .9f) <= FineTune.CEILING + 1e-3f)
        assertTrue(peak(FineTuneSetting(gainDb = 15), 1000.0, .9f) > 1f)
    }
}
