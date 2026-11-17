package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqShapeTest {
    private val flat = List(EQ_BANDS) { 0 }

    @Test fun theDefaultIsTheOldSpacingAndWidth() {
        assertEquals(EQ_CENTERS_HZ.toList(), EqShape.DEFAULT.hz)
        // the old width in octaves comes back from its Q, so a default curve sounds as it did
        assertEquals(EQ_WIDTH_OCTAVES, octavesOf(EqShape.DEFAULT.q[0]), 1e-9)
        assertEquals(1.0, EqShape.DEFAULT.q[0], .01)
        val before = EqDesign(listOf(300, 0, 600, 0, -300, 0, 0, 900, 0, 0))
        val now = EqDesign(listOf(300, 0, 600, 0, -300, 0, 0, 900, 0, 0), shape = EqShape.DEFAULT)
        for (hz in listOf(40.0, 300.0, 4000.0, 15_000.0)) assertEquals(before.responseDb(hz), now.responseDb(hz), 1e-9)
    }

    @Test fun savedAndReadBackItIsTheSame() {
        val moved = EqShape.DEFAULT.with(6, hz = 3150.0, q = 4.0)
        assertEquals(moved, EqShape.decode(moved.encode()))
        assertTrue(moved.moved(6))
        assertFalse(moved.moved(5))
    }

    @Test fun anythingUnreadableIsTheDefault() {
        // settings from before bands could move have no shape at all
        assertEquals(EqShape.DEFAULT, EqShape.decode(null))
        assertEquals(EqShape.DEFAULT, EqShape.decode("1000:1"))
        assertEquals(EqShape.DEFAULT, EqShape.decode(EqShape.DEFAULT.encode().replaceFirst("31.25", "5")))
        assertEquals(EqShape.DEFAULT, EqShape.decode("nonsense"))
    }

    @Test fun aMovedBandBoostsWhereItWasMovedTo() {
        val levels = flat.toMutableList().also { it[6] = -600 }
        val d = EqDesign(levels, shape = EqShape.DEFAULT.with(6, hz = 3150.0, q = 4.0))
        assertEquals(-6.0, d.responseDb(3150.0), .3)
        // narrow: an octave away it has almost let go
        assertTrue(abs(d.responseDb(1575.0)) < 1.0)
    }

    @Test fun twoBandsOnOneSpotWithDifferentLevelsStayInRange() {
        val levels = flat.toMutableList().also { it[4] = 1200; it[5] = -1200 }
        val d = EqDesign(levels, shape = EqShape.DEFAULT.with(4, hz = 1000.0))
        assertTrue(d.gainsDb.joinToString(), d.gainsDb.all { abs(it) <= 12.0 })
    }

    @Test fun slidersReadAsPeopleDo() {
        assertEquals(20.0, eqHzAt(0f), 0.0)
        assertEquals(20_000.0, eqHzAt(1f), 0.0)
        assertEquals(3150.0, eqHzAt(eqHzPos(3170.0)), 0.0)
        assertEquals(125.0, eqHzAt(eqHzPos(125.0)), 0.0)
        assertEquals(1.0, eqQAt(eqQPos(1.0)), 1e-9)
        assertEquals("Q 1.0 · medium", eqQText(1.0))
        assertEquals("Q 0.50 · wide", eqQText(.5))
        assertEquals("Q 4.0 · narrow", eqQText(4.0))
    }
}
