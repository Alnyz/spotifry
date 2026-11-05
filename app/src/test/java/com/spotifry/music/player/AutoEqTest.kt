package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class AutoEqTest {
    private val grid = DoubleArray(300) { 20 * 1000.0.pow(it / 299.0) }
    /** A headphone whose target is flat and whose raw sound is [shape] dB off it. */
    private fun curve(shape: (Double) -> Double) = AutoEqCurve(grid, DoubleArray(grid.size) { shape(grid[it]) }, DoubleArray(grid.size))
    private fun bump(at: Double, db: Double, octaves: Double) = { hz: Double -> db * exp(-((ln(hz / at) / ln(2.0)) / octaves).pow(2)) }

    @Test fun flatHeadphoneChangesNothing() {
        val fit = fitAutoEq(curve { 0.0 })
        assertEquals(List(EQ_BANDS) { 0 }, fit.levelsMb)
        assertEquals(0.0, fit.errorAfterDb, 1e-9)
    }

    @Test fun bassHeavyHeadphoneGetsTheBassCut() {
        val fit = fitAutoEq(curve(bump(60.0, 8.0, 1.0)))
        assertTrue("bass bands cut: ${fit.levelsMb}", fit.levelsMb[1] <= -600 && fit.levelsMb[0] < 0 && fit.levelsMb[2] < 0 && fit.levelsMb.drop(4).all { it == 0 })
        assertTrue("error ${fit.errorBeforeDb} -> ${fit.errorAfterDb}", fit.errorAfterDb < fit.errorBeforeDb * .5)
        assertTrue(fit.levelsMb.all { it % 50 == 0 && it in -EQ_MAX_MB..EQ_MAX_MB })
    }

    @Test fun broadTrebleDipGetsBoostedAndTurnedDown() {
        val fit = fitAutoEq(curve { -bump(4000.0, 6.0, 1.2)(it) })
        assertTrue(fit.errorAfterDb < fit.errorBeforeDb * .5)
        // the boost costs overall level, and the figure matches what the filters really do
        assertTrue(fit.peakBoostDb > 2)
        assertEquals(-20 * kotlin.math.log10(EqDesign(fit.levelsMb).preamp), fit.peakBoostDb, 1e-9)
    }

    @Test fun narrowDipIsNotClaimedFixed() {
        val fit = fitAutoEq(curve { -bump(5000.0, 10.0, .08)(it) })
        // one band an octave wide can't follow a dip this narrow: the error stays high, and says so
        assertTrue("error ${fit.errorAfterDb} vs ${fit.errorBeforeDb}", fit.errorAfterDb > fit.errorBeforeDb * .6)
    }

    @Test fun hugeErrorsAreHeldAtTheEdge() {
        val fit = fitAutoEq(curve { if (it < 200) 30.0 else 0.0 })
        assertTrue(fit.levelsMb.all { it in -EQ_MAX_MB..EQ_MAX_MB })
        assertEquals(-EQ_MAX_MB, fit.levelsMb[0])
        assertTrue("held at the edge, so 30 dB is not fixed: ${fit.errorAfterDb}", fit.errorAfterDb > 1.0)
    }

    @Test fun tooFewPointsIsRejected() {
        val c = AutoEqCurve(doubleArrayOf(100.0, 200.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0))
        assertTrue(runCatching { fitAutoEq(c) }.exceptionOrNull() is IllegalArgumentException)
    }

    private val index = """
        # Index
        - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
        - [Sennheiser HD 600](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20600) by crinacle on GRAS 43AG-7
        - [Sony WH-1000XM5](./Rtings/Bruel%20%26%20Kjaer%205128%20over-ear/Sony%20WH-1000XM5) by Rtings on Bruel & Kjaer 5128
        - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
        - not a result line
    """.trimIndent()

    @Test fun indexReadsNameSourceRigAndPath() {
        val all = parseAutoEqIndex(index)
        assertEquals(4, all.size)
        assertEquals(AutoEqEntry("Sennheiser HD 600", "oratory1990", null, "oratory1990/over-ear/Sennheiser HD 600"), all[0])
        assertEquals("crinacle · GRAS 43AG-7", all[1].sourceLabel)
        assertEquals("Rtings/Bruel & Kjaer 5128 over-ear/Sony WH-1000XM5", all[2].path)
        assertEquals("HypetheSonics/GRAS RA0045 in-ear/1MORE Aero (ANC Off)", all[3].path)
    }

    @Test fun searchIgnoresPunctuationAndNeedsEveryWord() {
        val all = parseAutoEqIndex(index)
        assertEquals(2, searchAutoEq(all, "hd600").size)
        assertEquals(listOf("Sony WH-1000XM5"), searchAutoEq(all, "wh1000").map { it.name })
        assertEquals(listOf("Sony WH-1000XM5"), searchAutoEq(all, "sony xm5").map { it.name })
        // "hd600" is inside "AH-D600" too, but it begins a word in "HD 600", so those lead
        val mixed = parseAutoEqIndex("- [Denon AH-D600](./a/b/Denon%20AH-D600) by a\n- [Sennheiser HD 600](./a/b/Sennheiser%20HD%20600) by a")
        assertEquals(listOf("Sennheiser HD 600", "Denon AH-D600"), searchAutoEq(mixed, "hd600").map { it.name })
        assertTrue(searchAutoEq(all, "zzz").isEmpty())
        assertTrue(searchAutoEq(all, "  ").isEmpty())
    }

    @Test fun percentDecodeKeepsPlusAndAccents() {
        assertEquals("A + B & C", percentDecode("A%20+%20B%20%26%20C"))
        assertEquals("Café", percentDecode("Caf%C3%A9"))
        assertEquals("100%", percentDecode("100%"))
    }

    @Test fun csvReadsTheThreeColumnsAndSkipsBadRows() {
        val c = parseAutoEqCsv("frequency,raw,smoothed,error,target\n20,1.5,0,0,0.5\n21,x,0,0,0\n\n22,2.5,0,0,1.5\n")
        assertEquals(listOf(20.0, 22.0), c.hz.toList())
        assertEquals(listOf(1.5, 2.5), c.raw.toList())
        assertEquals(listOf(.5, 1.5), c.target.toList())
        assertTrue(runCatching { parseAutoEqCsv("frequency,raw\n20,1") }.exceptionOrNull() is java.io.IOException)
    }
}
