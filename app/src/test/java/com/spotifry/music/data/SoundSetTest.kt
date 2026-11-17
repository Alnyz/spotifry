package com.spotifry.music.data

import com.spotifry.music.player.EQ_BANDS
import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.EqShape
import com.spotifry.music.player.FineTuneSetting
import com.spotifry.music.player.OutputKind
import com.spotifry.music.player.soundSetFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSetTest {
    private val prefs = MemoryPrefs()
    private val s = Settings(prefs)
    private val rock = EQ_PRESETS.first { it.first == "Rock" }.second
    private val moved = EqShape.DEFAULT.with(6, hz = 3150.0, q = 4.0)

    @Test fun onlyThePhoneSpeakerIsTheSpeaker() {
        assertEquals(Settings.SPEAKER, soundSetFor(OutputKind.Phone))
        OutputKind.entries.filter { it != OutputKind.Phone }.forEach { assertEquals(Settings.HEADPHONES, soundSetFor(it)) }
    }

    @Test fun offItNeverSwitches() {
        s.setEq("Rock", rock); s.setEqOn(true)
        s.useSoundSet(Settings.SPEAKER)
        assertEquals("Rock", s.eqPreset.value)
        assertTrue(s.eqOn.value)
    }

    @Test fun eachOutputKeepsItsOwnSound() {
        s.setEq("Rock", rock); s.setEqOn(true); s.setTune(FineTuneSetting(gainDb = 3))
        s.setSpeed(1.25f)
        s.setPerDevice(true, Settings.HEADPHONES)
        // both start from the sound in use; the speaker gets its own from here
        s.useSoundSet(Settings.SPEAKER)
        assertEquals("Rock", s.eqPreset.value)
        s.setEqOn(false); s.setTune(FineTuneSetting()); s.setMono(true)
        s.useSoundSet(Settings.HEADPHONES)
        assertTrue(s.eqOn.value)
        assertEquals(3, s.tune.value.gainDb)
        assertFalse(s.mono.value)
        s.useSoundSet(Settings.SPEAKER)
        assertFalse(s.eqOn.value)
        assertTrue(s.mono.value)
        // speed is the same everywhere
        assertEquals(1.25f, s.speed.value)
        // and it all survives a restart
        val again = Settings(prefs)
        assertTrue(again.perDevice.value)
        assertEquals(Settings.SPEAKER, again.soundSet.value)
        assertFalse(again.eqOn.value)
    }

    @Test fun movedBandsBelongToTheirSet() {
        s.setPerDevice(true, Settings.HEADPHONES)
        s.setEqShape(moved)
        s.useSoundSet(Settings.SPEAKER)
        assertTrue(s.eqShape.value.isDefault)
        s.useSoundSet(Settings.HEADPHONES)
        assertEquals(moved, s.eqShape.value)
    }

    @Test fun turningItOffKeepsTheSoundInUse() {
        s.setPerDevice(true, Settings.HEADPHONES)
        s.useSoundSet(Settings.SPEAKER)
        s.setEq("Rock", rock)
        s.setPerDevice(false, Settings.SPEAKER)
        s.useSoundSet(Settings.HEADPHONES)
        assertEquals("Rock", s.eqPreset.value)
    }

    @Test fun aPresetPutsBandsBackAndADragKeepsThem() {
        s.setEq("Rock", rock)
        s.setEqShape(moved)
        assertNull(s.eqPreset.value)
        assertEquals("Rock", s.eqBase.value)
        s.setEq(null, List(EQ_BANDS) { 100 })
        assertEquals(moved, s.eqShape.value)
        assertEquals(moved, Settings(prefs).eqShape.value)
        s.setEq("Rock", rock)
        assertTrue(s.eqShape.value.isDefault)
        assertTrue(Settings(prefs).eqShape.value.isDefault)
    }

    @Test fun theSettingKeptBeforeACorrectionBringsItsBandsBack() {
        s.setEqShape(moved); s.setEqOn(true)
        assertTrue(s.applyEqCorrection(com.spotifry.music.player.AUTOEQ_PREFIX + "HD 600", List(EQ_BANDS) { 200 }, s.eqPreset.value, s.eqLevels.value, true))
        assertTrue(s.eqShape.value.isDefault)
        s.restoreEqBefore()
        assertEquals(moved, s.eqShape.value)
    }

    @Test fun aSettingKeptBeforeBandsCouldMoveStillComesBack() {
        prefs.edit().putString(Settings.EQ_BEFORE, "Rock\n${rock.joinToString(",")}\ntrue").apply()
        val old = Settings(prefs)
        old.restoreEqBefore()
        assertEquals("Rock", old.eqPreset.value)
        assertTrue(old.eqShape.value.isDefault)
    }
}
