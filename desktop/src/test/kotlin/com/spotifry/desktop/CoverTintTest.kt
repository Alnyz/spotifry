package com.spotifry.desktop

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTintTest {
    private fun hue(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue); val d = max - min
        val h = when (max) { c.red -> ((c.green - c.blue) / d) % 6; c.green -> (c.blue - c.red) / d + 2; else -> (c.red - c.green) / d + 4 }
        return (h * 60 + 360) % 360
    }

    @Test fun orangePixelsGiveOrange() {
        val c = dominantColor(IntArray(100) { 0xFFE2531F.toInt() })
        assertNotNull(c); assertEquals(16f, hue(c!!), 15f)
    }

    @Test fun greyOrBlackGivesNull() {
        assertNull(dominantColor(IntArray(100) { 0xFF111111.toInt() }))
        assertNull(dominantColor(IntArray(100) { 0xFF808080.toInt() }))
    }

    @Test fun mixedPicksTheBiggerGroup() {
        val px = IntArray(100) { if (it < 70) 0xFF0F5F78.toInt() else 0xFFE2531F.toInt() }
        assertTrue(hue(dominantColor(px)!!) in 170f..210f)
    }

    @Test fun emptyGivesNull() = assertNull(dominantColor(IntArray(0)))
}
