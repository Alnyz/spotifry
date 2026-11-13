package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

class BeatAnalysisTest {
    private val rate = ANALYSIS_RATE

    /** Short thumps at [bpm], the first [firstMs] in, over [seconds]. */
    private fun clicks(bpm: Double, seconds: Int = 30, firstMs: Double = 200.0, jitterMs: Double = 0.0): FloatArray {
        val out = FloatArray(rate * seconds)
        var t = firstMs; var n = 0
        val r = java.util.Random(7)
        while (t < seconds * 1000.0) {
            val at = ((t + r.nextGaussian() * jitterMs) * rate / 1000).toInt()
            for (i in 0 until 1_500) if (at + i in out.indices && at + i >= 0) out[at + i] += (.8 * exp(-i / 300.0) * sin(2 * PI * 90 * i / rate)).toFloat()
            n++; t = firstMs + n * 60_000.0 / bpm
        }
        return out
    }

    private fun grid(a: FloatArray, startMs: Long = 0) = analyseBeats(a, a.size, startMs)

    private fun assertOnBeats(g: BeatGrid, firstMs: Double, periodMs: Double) {
        for (k in listOf(0, 10, 25)) {
            val want = firstMs + k * periodMs
            val got = g.beatAtOrAfter(want - periodMs / 2)
            assertEquals("beat $k", want, got, 20.0)
        }
    }

    @Test fun clicksGiveTheirTempoAndBeats() {
        for (bpm in listOf(90.0, 120.0, 128.0, 150.0)) {
            val g = grid(clicks(bpm))!!
            assertEquals(bpm, g.bpm, .5)
            assertTrue("$bpm read at ${g.confidence}", g.isSure)
            assertOnBeats(g, 200.0, 60_000 / bpm)
        }
    }

    @Test fun aKickUnderAPadAndNoiseStillReads() {
        val r = java.util.Random(3)
        val a = clicks(128.0)
        for (i in a.indices) a[i] += (.08 * sin(2 * PI * 220 * i / rate) + .06 * sin(2 * PI * 277 * i / rate) + .03 * r.nextGaussian()).toFloat()
        val g = grid(a)!!
        assertEquals(128.0, g.bpm, .5)
        assertTrue("read at ${g.confidence}", g.isSure)
        assertOnBeats(g, 200.0, 60_000 / 128.0)
    }

    @Test fun whereTheStretchStartsCountsTowardItsBeats() {
        val g = grid(clicks(120.0), startMs = 180_000)!!
        assertOnBeats(g, 180_200.0, 500.0)
    }

    @Test fun halfTempoFoldsUpAndStaysOnItsHits() {
        val g = grid(clicks(70.0))!!
        assertEquals(140.0, g.bpm, 1.0)
        assertTrue(g.isSure)
        // every hit is a beat of the grid, wherever the grid's extra beats fall
        assertOnBeats(g, 200.0, 60_000 / 70.0)
    }

    @Test fun noiseIsNotABeat() {
        val r = java.util.Random(1)
        val g = grid(FloatArray(rate * 30) { (r.nextGaussian() * .2).toFloat() })
        assertFalse("noise read as ${g?.bpm} at ${g?.confidence}", g?.isSure == true)
    }

    @Test fun aSteadyToneIsNotABeat() {
        val g = grid(FloatArray(rate * 30) { (.3 * sin(2 * PI * 440 * it / rate)).toFloat() })
        assertFalse(g?.isSure == true)
    }

    @Test fun silenceAndTooLittleSoundGiveNothing() {
        assertNull(grid(FloatArray(rate * 30)))
        assertNull(grid(clicks(120.0, seconds = 5)))
    }

    @Test fun aDrummerAllOverThePlaceIsNotSure() {
        val g = grid(clicks(120.0, jitterMs = 45.0))
        assertFalse("read at ${g?.confidence}", g?.isSure == true)
    }

    @Test fun aTrailingSilenceIsSeen() {
        val a = clicks(120.0)
        for (i in rate * 25 until a.size) a[i] = 0f
        assertEquals(25_000.0, grid(a)!!.soundToMs.toDouble(), 400.0)
    }

    // planner

    private fun beats(bpm: Double, firstMs: Double = 0.0, conf: Float = .9f, to: Long = Long.MAX_VALUE / 4, from: Long = 0) =
        BeatGrid(firstMs, 60_000 / bpm, conf, from, to)

    private fun onABeat(g: BeatGrid, ms: Double, tolerance: Double = 3.0) {
        val k = (ms - g.firstBeatMs) / g.periodMs
        assertEquals("$ms is ${k} beats in", k.roundToLong().toDouble(), k, tolerance / g.periodMs)
    }

    @Test fun theJoinStartsOnABeatAndItsMiddleLandsOnBoth() {
        val out = beats(120.0, 113.0); val inn = beats(120.0, 47.0)
        val p = planJoin(out, inn, 200_000, 180_000, 8_000, 1_500)!!
        assertTrue(p.onBeat)
        onABeat(out, p.startMs.toDouble())
        onABeat(inn, p.cueMs.toDouble())
        onABeat(out, p.startMs + p.lengthMs / 2.0)
        onABeat(inn, p.cueMs + p.lengthMs / 2.0)
        assertEquals(8_000, p.lengthMs)
        assertTrue(p.startMs + p.lengthMs <= 200_000)
        assertTrue("room to line up before it's heard", p.cueMs >= 1_500)
    }

    @Test fun aTrimmedSongEndsItsJoinBeforeItsEnd() {
        // a song that plays 0:10 to 3:00: its grid and length are in playing time, 170 s
        val whole = beats(120.0, 13.0)
        val out = whole.shifted(-10_000)
        val p = planJoin(out, beats(120.0), 170_000, 180_000, 8_000, 1_500)!!
        assertTrue(p.startMs + p.lengthMs <= 170_000)
        onABeat(whole, p.startMs + 10_000.0)
    }

    @Test fun aSilentTailIsLeftOut() {
        val p = planJoin(beats(120.0, to = 190_000), beats(120.0), 200_000, 180_000, 8_000, 1_500)!!
        assertTrue(p.startMs + p.lengthMs <= 190_000)
    }

    @Test fun doubleTimeSongsStillLand() {
        val out = beats(80.0, 21.0); val inn = beats(160.0, 9.0)
        val p = planJoin(out, inn, 200_000, 180_000, 8_000, 1_500)!!
        assertTrue(p.onBeat)
        onABeat(out, p.startMs + p.lengthMs / 2.0)
        onABeat(inn, p.cueMs + p.lengthMs / 2.0)
        val q = planJoin(inn, out, 200_000, 180_000, 8_000, 1_500)!!
        assertTrue(q.onBeat)
        onABeat(out, q.cueMs + q.lengthMs / 2.0)
    }

    @Test fun nearbyTemposGetAShorterJoinThatDoesNotSlipFar() {
        val out = beats(120.0); val inn = beats(122.0)
        val p = planJoin(out, inn, 200_000, 180_000, 12_000, 1_500)!!
        assertTrue(p.onBeat)
        val slip = abs(out.periodMs - inn.periodMs) * (p.lengthMs / out.periodMs) / 2
        assertTrue("slips $slip ms", slip <= 70.0)
        assertTrue(p.lengthMs < 12_000)
    }

    @Test fun tooShortOnTheBeatGetsThePlainOverlapAtTheChosenLength() {
        val p = planJoin(beats(120.0), beats(116.0), 200_000, 180_000, 12_000, 1_500)!!
        assertFalse(p.onBeat)
        assertEquals(12_000, p.lengthMs)
    }

    @Test fun tempoAPartMeetAtTheMiddleNotTheStart() {
        val out = beats(120.0, 113.0); val inn = beats(122.0, 47.0)
        val p = planJoin(out, inn, 200_000, 180_000, 8_000, 1_500)!!
        assertTrue(p.onBeat)
        onABeat(out, p.startMs + p.lengthMs / 2.0)
        onABeat(inn, p.cueMs + p.lengthMs / 2.0)
        assertTrue("room to line up before it's heard", p.cueMs >= 1_500)
        // at the start of the overlap the new song's beat is off by the slip, in the other direction from its end
        val atStart = (p.cueMs - inn.firstBeatMs).mod(inn.periodMs).let { minOf(it, inn.periodMs - it) }
        assertTrue("off by $atStart at the start", atStart in 1.0..70.0)
    }

    @Test fun veryDifferentTemposFallBackToThePlainOverlap() {
        val p = planJoin(beats(120.0), beats(97.0), 200_000, 180_000, 5_000, 1_500)!!
        assertFalse(p.onBeat)
        assertEquals(195_000, p.startMs)
        assertEquals(5_000, p.lengthMs)
        assertEquals(0, p.leadMs)
    }

    @Test fun lowConfidenceFallsBackToThePlainOverlap() {
        assertFalse(planJoin(beats(120.0, conf = .1f), beats(120.0), 200_000, 180_000, 5_000, 1_500)!!.onBeat)
        assertFalse(planJoin(beats(120.0), beats(120.0, conf = .1f), 200_000, 180_000, 5_000, 1_500)!!.onBeat)
        assertFalse(planJoin(null, beats(120.0), 200_000, 180_000, 5_000, 1_500)!!.onBeat)
    }

    @Test fun unsureWithNoFadeSetLeavesTheSongAlone() {
        assertNull(planJoin(beats(120.0, conf = .1f), beats(120.0), 200_000, 180_000, 0, 1_500))
    }

    @Test fun nextSongNotReadyMeansNoJoin() {
        assertNull(planJoin(beats(120.0), null, 200_000, 180_000, 5_000, 1_500))
    }

    @Test fun songsTooShortMeanNoJoin() {
        assertNull(planJoin(null, beats(120.0), 9_000, 180_000, 5_000, 1_500))
        assertNull(planJoin(null, beats(120.0), 200_000, 9_000, 5_000, 1_500))
        assertNotNull(planJoin(null, beats(120.0), 200_000, 0, 5_000, 1_500))
    }

    @Test fun beatJoinWithNoFadeStillOverlaps() {
        val p = planJoin(beats(120.0), beats(120.0), 200_000, 180_000, 0, 1_500)!!
        assertTrue(p.onBeat)
        assertEquals(6_000, p.lengthMs)
    }

    @Test fun aSongFadingOutReadsQuietAtItsEnd() {
        val a = clicks(120.0)
        for (i in 0 until rate * 8) a[a.size - 1 - i] *= i / (rate * 8f)
        assertTrue(grid(a)!!.endLevel < .4f)
        assertTrue(grid(clicks(120.0))!!.endLevel > .8f)
    }

    @Test fun aQuietEndGetsAShortPlainFade() {
        val quiet = beats(120.0).copy(endLevel = .05f)
        val p = planJoin(quiet, beats(120.0), 200_000, 180_000, 8_000, 1_500)!!
        assertFalse(p.onBeat)
        assertEquals(2_000, p.lengthMs)
        assertEquals(8_000, fadeFor(beats(120.0), 8_000))
        assertEquals(0, fadeFor(quiet, 0))
    }

    @Test fun theLouderSongIsBroughtDown() {
        assertEquals(0f, Leveler.matchDb(null, -10f, false), 0f)
        assertEquals(6f, Leveler.matchDb(-20f, -14f, false), .01f)
        assertEquals(0f, Leveler.matchDb(-20f, -14f, true), .01f)
        assertEquals(-9f, Leveler.matchDb(-10f, -30f, false), .01f)
    }
}
