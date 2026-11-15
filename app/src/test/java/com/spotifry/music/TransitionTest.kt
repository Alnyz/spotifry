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
}
