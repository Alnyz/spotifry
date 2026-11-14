package com.spotifry.music

import com.spotifry.music.player.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TransitionTest {
    private fun peak(mode: Int, filterHz: Float, toneHz: Double): Double {
        val s = Sweep().apply { configure(44_100); set(mode, filterHz) }
        var p = 0.0
        for (n in 0 until 8000) { val y = s.process(sin(2 * PI * toneHz * n / 44_100).toFloat(), 0); if (n > 4000) p = maxOf(p, abs(y.toDouble())) }
        return p
    }

    @Test fun highpassCutsBassKeepsTreble() {
        assertTrue(peak(FILTER_HIGHPASS, 300f, 50.0) < .05)
        assertTrue(peak(FILTER_HIGHPASS, 300f, 3000.0) > .9)
    }

    @Test fun lowpassCutsTrebleKeepsBass() {
        assertTrue(peak(FILTER_LOWPASS, 500f, 8000.0) < .05)
        assertTrue(peak(FILTER_LOWPASS, 500f, 100.0) > .9)
    }

    @Test fun openFilterPassesSoundUntouched() {
        val s = Sweep().apply { configure(44_100); set(FILTER_HIGHPASS, 20f) }
        assertEquals(.37f, s.process(.37f, 0), 0f)
        s.set(FILTER_OFF, 0f)
        assertEquals(.37f, s.process(.37f, 0), 0f)
    }

    @Test fun silkIsPlainEqualPower() {
        val (o, n) = transitionVoices(TransitionStyle.SILK, .5f)
        assertEquals(FILTER_OFF, o.mode); assertEquals(FILTER_OFF, n.mode)
        assertEquals(1f, o.gain * o.gain + n.gain * n.gain, 1e-5f)
    }

    @Test fun bassSwapHandsTheLowEndOver() {
        val (o0, n0) = transitionVoices(TransitionStyle.BASS_SWAP, 0f)
        val (o1, n1) = transitionVoices(TransitionStyle.BASS_SWAP, 1f)
        assertTrue(o0.hz <= 25f && n0.hz >= 290f) // old has its bass, new has none
        assertTrue(o1.hz >= 290f && n1.hz <= 25f) // the other way round
        assertEquals(FILTER_HIGHPASS, o0.mode)
    }

    @Test fun filterSweepDullsTheOldAndOpensTheNew() {
        val (o0, n0) = transitionVoices(TransitionStyle.FILTER_SWEEP, 0f)
        val (o1, n1) = transitionVoices(TransitionStyle.FILTER_SWEEP, 1f)
        assertTrue(o0.hz > 17_000f && n0.hz < 300f)
        assertTrue(o1.hz < 300f && n1.hz > 17_000f)
    }

    @Test fun autoPicksPerPair() {
        assertEquals(TransitionStyle.BASS_SWAP, pickStyle(TransitionStyle.AUTO, true, 12_000))
        assertEquals(TransitionStyle.SILK, pickStyle(TransitionStyle.AUTO, true, 3_000))
        assertEquals(TransitionStyle.FILTER_SWEEP, pickStyle(TransitionStyle.AUTO, false, 12_000))
        assertEquals(TransitionStyle.SILK, pickStyle(TransitionStyle.SILK, false, 12_000))
    }
}
