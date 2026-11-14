package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private val rate = 48_000
    private val flat = List(EQ_BANDS) { 0 }
    private fun only(band: Int, mb: Int) = flat.toMutableList().also { it[band] = mb }

    private fun eq(levels: List<Int>?, channels: Int = 1) = Equalizer().apply { set(levels); configure(rate, channels) }

    /** Plays a tone through [e], then returns its level in dB against the same tone going in. */
    private fun gainDb(e: Equalizer, hz: Double): Double {
        var inn = 0.0; var out = 0.0
        for (i in 0 until rate) {
            val x = (.1 * sin(2 * PI * hz * i / rate)).toFloat()
            val y = e.process(x)
            if (i > rate / 2) { inn += x * x; out += y * y }
        }
        return 10 * log10(out / inn)
    }

    @Test fun aBoostRaisesItsOwnBandAndNotOneFarAway() {
        val levels = only(5, 600)
        val preamp = 20 * log10(EqDesign(levels, rate).preamp)
        assertEquals(6.0, gainDb(eq(levels), EQ_CENTERS_HZ[5]) - preamp, .3)
        assertEquals(0.0, gainDb(eq(levels), EQ_CENTERS_HZ[0]) - preamp, .3)
        assertEquals(0.0, gainDb(eq(levels), EQ_CENTERS_HZ[9]) - preamp, .3)
    }

    @Test fun aCutLowersItsOwnBand() {
        val levels = only(3, -900)
        assertEquals(-9.0, gainDb(eq(levels), EQ_CENTERS_HZ[3]) - 20 * log10(EqDesign(levels, rate).preamp), .3)
    }

    @Test fun eachBandLandsOnTheLevelAsked() {
        for ((name, levels) in EQ_PRESETS) {
            val d = EqDesign(levels, rate)
            for (i in 0 until EQ_BANDS) assertEquals("$name band $i", levels[i] / 100.0, d.responseDb(EQ_CENTERS_HZ[i]), .25)
        }
        // overlapping filters would add up to about +10 here without the correction
        val d = EqDesign(List(EQ_BANDS) { 600 }, rate)
        for (hz in EQ_CENTERS_HZ) assertEquals(6.0, d.responseDb(hz), .25)
    }

    @Test fun theCurveHasNoDipsBetweenBandsSetTheSame() {
        val d = EqDesign(List(EQ_BANDS) { 600 }, rate)
        var hz = EQ_CENTERS_HZ[0]
        while (hz <= EQ_CENTERS_HZ[9]) { assertEquals("at $hz Hz", 6.0, d.responseDb(hz), .5); hz *= 1.05 }
    }

    @Test fun theHarshestZigzagStaysSteady() {
        val e = eq(List(EQ_BANDS) { if (it % 2 == 0) 1200 else -1200 })
        for (i in 0 until rate) {
            val y = e.process((sin(2 * PI * 440.0 * i / rate) * .5).toFloat())
            assertTrue("sample $i was $y", y.isFinite() && abs(y) < 1.2f)
        }
    }

    @Test fun offAndFlatDoNotFilter() {
        assertTrue(!eq(null).active)
        assertTrue(!eq(flat).active)
        assertTrue(eq(only(0, 100)).active)
    }

    @Test fun aSteadyFullLevelToneStaysUnderFullLevelWhenBoosted() {
        for (levels in listOf(List(EQ_BANDS) { 1200 }, only(5, 1200), EQ_PRESETS.first { it.first == "Heavy Metal" }.second)) {
            for (hz in listOf(62.5, 1000.0, 1300.0, 8000.0)) {
                val e = eq(levels)
                var peak = 0f
                for (i in 0 until rate) e.process(sin(2 * PI * hz * i / rate).toFloat()).let { if (i > rate / 2) peak = maxOf(peak, abs(it)) }
                assertTrue("$hz Hz peaked at $peak", peak <= 1.01f)
            }
        }
    }

    @Test fun overshootAtTheStartOfASoundIsBentNotCutFlat() {
        // a loud tone starting from silence: the filters overshoot at first
        val p = processor(List(EQ_BANDS) { 1200 })
        val song = ShortArray(9_600) { (32_000 * sin(2 * PI * 1000.0 * (it / 2) / rate)).toInt().toShort() }
        p.queueInput(pcm(*song))
        val out = shorts(p.output)
        // cut flat, it would sit at the limit for several samples in a row
        var run = 0; var worst = 0
        for (s in out) { run = if (s >= 32_767 || s <= -32_768) run + 1 else 0; worst = maxOf(worst, run) }
        assertTrue("$worst samples in a row at the limit", worst < 3)
    }

    @Test fun changingTheSettingDoesNotClick() {
        val e = eq(flat)
        var last = 0f; var worst = 0f
        for (i in 0 until rate) {
            // a jump from flat to a big boost right in the music, then back to off
            if (i == rate / 3) { e.set(List(EQ_BANDS) { if (it < 5) 1200 else -1200 }); e.update() }
            if (i == 2 * rate / 3) { e.set(null); e.update() }
            val y = e.process((.5 * sin(2 * PI * 440.0 * i / rate)).toFloat())
            if (i > 0) worst = maxOf(worst, abs(y - last))
            last = y
        }
        // the tone itself moves about .029 per sample at this level; a click would be many times that
        assertTrue("jump of $worst", worst < .08f)
    }

    @Test fun afterAFadeToOffItStopsFiltering() {
        val e = eq(only(0, 600))
        e.set(null); e.update()
        repeat(rate / 10) { e.process(0f) }
        assertTrue(!e.active)
    }

    @Test fun lowSampleRateLeavesOutTheBandsItCannotFilter() {
        val d = EqDesign(only(9, 1200), 22_050)
        assertEquals(0.0, d.responseDb(1000.0), .001)
        assertEquals(1.0, d.preamp, .001)
    }

    private fun pcm(vararg s: Short): ByteBuffer =
        ByteBuffer.allocateDirect(s.size * 2).order(ByteOrder.nativeOrder()).also { b -> s.forEach { b.putShort(it) }; b.flip() }

    private fun processor(levels: List<Int>?) = EqualizerProcessor().apply {
        set(levels)
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun shorts(b: ByteBuffer) = ShortArray(b.remaining() / 2) { b.getShort() }

    @Test fun flatAndOffLeaveEverySampleAsItWas() {
        val song = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321, 7)
        for (levels in listOf(flat, null)) {
            val p = processor(levels)
            p.queueInput(pcm(*song))
            assertArrayEquals(song, shorts(p.output))
        }
    }

    /** Twelve seconds of a stereo 1 kHz tone through the real chain, as the player sends it. */
    private fun playThroughChain(levels: List<Int>?, tap: SpectrumTap? = null): LoudnessProcessor {
        val eq = EqualizerProcessor().apply { set(levels) }
        val loudness = LoudnessProcessor(tap)
        val chain = soundChain(eq, loudness, SpatialProcessor(), SweepProcessor())
        chain.forEach { it.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); it.flush(AudioProcessor.StreamMetadata.DEFAULT) }
        for (b in 0 until 120) {
            var sound = pcm(*ShortArray(rate / 5) { (11_600 * sin(2 * PI * 1000.0 * (b * rate / 10 + it / 2) / rate)).toInt().toShort() })
            for (p in chain) { p.queueInput(sound); sound = p.output }
        }
        return loudness
    }

    @Test fun theRememberedLevelOfASongDoesNotDependOnTheCurve() {
        val plain = playThroughChain(null).lastMeasuredDb!!
        for (levels in listOf(flat, only(8, 1200), only(5, 1200), List(EQ_BANDS) { -1200 })) {
            assertEquals(plain, playThroughChain(levels).lastMeasuredDb!!, .01f)
        }
    }

    @Test fun theLivePictureShowsTheMusicAtTheSameLevelWhenABandIsBoosted() {
        fun loudest(levels: List<Int>?): Float {
            val tap = SpectrumTap().apply { listening = true }
            playThroughChain(levels, tap)
            return tap.next(1000f)!!.max()
        }
        val plain = loudest(null)
        assertTrue("the picture read $plain", plain > -20f)
        assertEquals(plain, loudest(only(8, 1200)), .01f)
    }

    @Test fun offToOffStaysOutOfTheWay() {
        val e = eq(null)
        e.set(null); e.update()
        assertTrue(!e.active)
        e.set(flat); e.update()
        assertTrue(!e.active)
    }

    @Test fun anEmptyBufferBeforeAnySoundIsFine() {
        // the player does this; while off, the sound is copied across, and an empty buffer here is the output buffer itself
        for (levels in listOf(null, flat, only(0, 600))) {
            val p = processor(levels)
            p.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, p.output.remaining())
        }
    }

    @Test fun aSeekLeavesNothingOfTheOldSoundBehind() {
        val p = processor(only(2, 1200))
        p.queueInput(pcm(*ShortArray(4_800) { (20_000 * sin(2 * PI * 125.0 * (it / 2) / rate)).toInt().toShort() }))
        p.output
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(pcm(*ShortArray(960)))
        assertTrue(shorts(p.output).all { it == 0.toShort() })
    }

    @Test fun theOtherChannelIsFilteredOnItsOwn() {
        // sound on the left only: nothing may leak into the right
        val p = processor(only(5, 600))
        val song = ShortArray(4_800) { if (it % 2 == 0) (8_000 * sin(2 * PI * 1000.0 * (it / 2) / rate)).toInt().toShort() else 0 }
        p.queueInput(pcm(*song))
        val out = shorts(p.output)
        var right = 0.0; var left = 0.0
        out.forEachIndexed { i, s -> if (i % 2 == 0) left += s * s.toDouble() else right += s * s.toDouble() }
        assertEquals(0.0, sqrt(right), .001)
        assertTrue(left > 0)
    }

    @Test fun oldFiveBarLevelsBecomeTenBands() {
        val ten = eqFromOldBands(listOf(600, 300, 0, 100, 300))
        assertEquals(EQ_BANDS, ten.size)
        // below the old first bar: its level. Above the last: its level
        assertEquals(600, ten[0])
        assertEquals(300, ten[9])
        // 250 Hz sits just above the 230 Hz bar, 1 kHz just above the 910 Hz one
        assertEquals(300.0, ten[3].toDouble(), 30.0)
        assertEquals(0.0, ten[5].toDouble(), 10.0)
    }

    @Test fun oldLevelsPastTheNewRangeAreHeldAtItsEdge() {
        val ten = eqFromOldBands(listOf(1500, 0, 0, 0, -1500))
        assertEquals(EQ_MAX_MB, ten[0])
        assertEquals(-EQ_MAX_MB, ten[9])
    }

    @Test fun anyOldBarCountConverts() {
        assertEquals(List(EQ_BANDS) { 200 }, eqFromOldBands(listOf(200)))
        assertEquals(List(EQ_BANDS) { 0 }, eqFromOldBands(emptyList()))
        assertEquals(EQ_BANDS, eqFromOldBands(List(8) { 100 }).size)
    }
}
