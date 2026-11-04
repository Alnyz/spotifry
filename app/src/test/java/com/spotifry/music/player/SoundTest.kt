package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoundTest {
    @Test fun fadeOutOverTheLastSeconds() {
        assertEquals(1f, fadeGain(100_000, 200_000, 5_000, fadeIn = false))
        assertEquals(.5f, fadeGain(197_500, 200_000, 5_000, fadeIn = false), .001f)
        assertEquals(0f, fadeGain(200_000, 200_000, 5_000, fadeIn = false))
    }

    @Test fun fadeInOnlyWhenTheSongRanOn() {
        assertEquals(.2f, fadeGain(1_000, 200_000, 5_000, fadeIn = true), .001f)
        assertEquals(1f, fadeGain(1_000, 200_000, 5_000, fadeIn = false))
    }

    @Test fun offOrUnknownLengthLeavesTheVolume() {
        assertEquals(1f, fadeGain(199_000, 200_000, 0, fadeIn = true))
        assertEquals(1f, fadeGain(199_000, -1, 5_000, fadeIn = false))
    }

    @Test fun toneShowsInItsOwnBand() {
        val s = Spectrum().apply { configure(44_100) }
        for (i in 0 until 44_100 / 30) s.feed((.5 * sin(2 * PI * 1_000 * i / 44_100)).toFloat())
        val loudest = s.bands.indices.maxBy { s.bands[it] }
        assert(1_000f in Spectrum.bandEdgeHz(loudest)..Spectrum.bandEdgeHz(loudest + 1)) { "1 kHz landed in band $loudest" }
        // amplitude .5 is -6 dB
        assertEquals(-6f, s.bands[loudest], 1.5f)
        assert(s.bands[0] < -40f) { "bass band should be quiet, was ${s.bands[0]}" }
    }

    @Test fun eqCurveBetweenBars() {
        val centers = listOf(60, 230, 910, 3_600, 14_000)
        val levels = listOf(600, 300, 0, 100, 300)
        assertEquals(6f, eqDbAt(30f, centers, levels))
        assertEquals(3f, eqDbAt(20_000f, centers, levels))
        assertEquals(0f, eqDbAt(910f, centers, levels), .001f)
        assertEquals(4.5f, eqDbAt(kotlin.math.sqrt(60f * 230f), centers, levels), .01f)
        assertEquals(0f, eqDbAt(500f, centers, emptyList()))
    }
}
