package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

class CoverTest {
    private fun picture(w: Int, h: Int, bar: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color.BLACK; fillRect(0, 0, w, h); color = Color(200, 90, 40); fillRect(0, bar, w, h - 2 * bar) }
    }

    @Test fun barsAboveAndBelowAreCut() {
        val t = trimBars(picture(160, 90, 12))
        assertEquals(160, t.width); assertEquals(66, t.height)
    }

    @Test fun aPictureWithoutBarsStays() {
        val p = picture(160, 90, 0)
        assertEquals(p, trimBars(p))
    }

    @Test fun anAllBlackPictureStays() {
        val p = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
        assertEquals(p, trimBars(p))
    }
}
