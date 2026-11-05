package com.spotifry.music.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EqGraphTest {
    private val plot = EqPlot(0f, 20f, 1000f, 480f)

    @Test fun aLevelAndItsPlaceOnTheGraphMatch() {
        for (mb in listOf(-1200, -650, 0, 50, 1200)) assertEquals(mb, plot.mbAt(plot.y(mb.toFloat())))
    }

    @Test fun draggingPastTheEdgesHoldsAtTheRange() {
        assertEquals(1200, plot.mbAt(-300f))
        assertEquals(-1200, plot.mbAt(900f))
    }

    @Test fun levelsMoveInHalfDecibels() {
        assertEquals(0, plot.mbAt(251f))
        assertEquals(50, plot.mbAt(240f))
    }

    @Test fun frequenciesSpreadOnALogScale() {
        assertEquals(0f, plot.x(20.0), .01f)
        assertEquals(1000f, plot.x(20_000.0), .01f)
        // 632 Hz is halfway between 20 Hz and 20 kHz to the ear
        assertEquals(500f, plot.x(632.5), 1f)
    }

    @Test fun aColumnHoldsItsBand() {
        assertEquals(0, bandAt(0f, 100f))
        assertEquals(3, bandAt(35f, 100f))
        assertEquals(9, bandAt(100f, 100f))
        assertEquals(0, bandAt(-5f, 100f))
    }

    @Test fun aSweepInOneColumnSetsOnlyThatBand() {
        assertEquals(listOf(4 to 300), sweep(4, 0, 4, 300))
    }

    @Test fun aQuickSweepFillsTheColumnsItJumped() {
        assertEquals(listOf(2 to 200, 3 to 400, 4 to 600), sweep(1, 0, 4, 600))
        assertEquals(listOf(5 to -300, 4 to -600), sweep(6, 0, 4, -600))
    }

    @Test fun decibelText() {
        assertEquals("+3", dbText(300))
        assertEquals("−1.5", dbText(-150))
        assertEquals("0", dbText(0))
    }
}
