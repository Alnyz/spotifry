package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LevelerTest {
    private val rate = 44_100

    /** Plays [seconds] of a stereo tone at [levelDb] average level through [l]; returns the level of the last second out. */
    private fun play(l: Leveler, levelDb: Double, seconds: Int): Double {
        val amp = sqrt(2.0) * 10.0.pow(levelDb / 20)
        var sum = 0.0; var n = 0
        for (i in 0 until rate * seconds) {
            val x = (amp * sin(2 * PI * 440 * i / rate)).toFloat()
            repeat(2) {
                val y = l.process(x)
                if (i >= rate * (seconds - 1)) { sum += y * y; n++ }
            }
        }
        return 10 * log10(sum / n)
    }

    private fun leveler() = Leveler().apply { configure(rate, 2); startSong(null) }

    @Test fun quietSongIsTurnedUp() = assertEquals(-16.0, play(leveler(), -20.0, 30), 1.0)

    @Test fun loudSongIsTurnedDown() = assertEquals(-16.0, play(leveler(), -8.0, 40), 1.0)

    @Test fun offLeavesTheSongAlone() = assertEquals(-20.0, play(leveler().apply { enabled = false }, -20.0, 5), .3)

    @Test fun rememberedSongStartsAtTheRightLevel() {
        val l = Leveler().apply { configure(rate, 2); startSong(-8f) }
        assertEquals(-16.0, play(l, -8.0, 2), 1.0)
    }

    @Test fun measuresAfterTenSeconds() {
        val l = leveler()
        play(l, -12.0, 5)
        assertEquals(null, l.measuredDb())
        play(l, -12.0, 6)
        assertEquals(-12f, l.measuredDb()!!, .5f)
    }

    @Test fun peaksNeverClip() {
        for (x in listOf(-4f, -1.2f, -.95f, .5f, .95f, 1.2f, 4f)) assertTrue(kotlin.math.abs(Leveler.soften(x)) <= 1f)
        assertEquals(.5f, Leveler.soften(.5f))
    }
}
