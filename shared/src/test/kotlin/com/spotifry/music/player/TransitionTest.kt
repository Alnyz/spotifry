package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTest {
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
