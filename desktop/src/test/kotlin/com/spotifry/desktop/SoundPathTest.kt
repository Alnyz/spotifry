package com.spotifry.desktop

import com.spotifry.music.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPathTest {
    private val opus = MpvSound("youtube", "opus", 160_000.0, AudioFormat(48_000, "floatp", 2), AudioFormat(48_000, "float", 2), "pipewire")
    private val speakers = SoundOutput(false, "Built-in Audio Speaker", false, "s32le 2ch 48000Hz", "PipeWire")

    @Test fun formatsReadInPlainWords() {
        assertEquals("32-bit float", bitsText("floatp")); assertEquals("16-bit", bitsText("s16")); assertNull(bitsText("weird"))
        assertEquals(48_000 to "32-bit", serverFormat("s32le 2ch 48000Hz"))
        assertEquals(44_100 to "32-bit float", serverFormat("float32le 2ch 44100Hz"))
        assertNull(serverFormat(null)); assertNull(serverFormat("garbage"))
        assertEquals("44.1 kHz", khz(44_100)); assertEquals("48 kHz", khz(48_000))
    }

    @Test fun whereTheSongComesFrom() {
        assertEquals("local", originOf("local:/m/a.flac", true, null))
        assertEquals("saved", originOf("yt1", true, "youtube"))
        assertEquals("saavn", originOf("yt1", false, "saavn"))
    }

    @Test fun theVerdictNamesTheFirstThingInTheWay() {
        assertEquals("Not guaranteed, the sound goes through the sound server's mixer", soundPathVerdict(opus, PathSettings(), speakers))
        assertEquals("No, the equalizer is on", soundPathVerdict(opus, PathSettings(eqOn = true, leveling = true), speakers))
        assertEquals("No, Bluetooth sends compressed sound", soundPathVerdict(opus, PathSettings(eqOn = true), speakers.copy(bluetooth = true)))
        assertEquals("No, the sound server changes the sample rate", soundPathVerdict(opus, PathSettings(), speakers.copy(spec = "s32le 2ch 44100Hz")))
        // trimming only cuts the ends; the sound itself is untouched
        assertEquals(soundPathVerdict(opus, PathSettings(), speakers), soundPathVerdict(opus, PathSettings(trim = com.spotifry.music.player.Trim(5_000)), speakers))
    }

    @Test fun theRowsShowOnlyWhatIsKnownAndOnlyWhatIsOn() {
        val g = soundPathGroups(opus, PathSettings(eqOn = true, eqName = "Bass boost", soundSet = Settings.SPEAKER), speakers)
        assertEquals(listOf("Input", "Spotifry", "Sound server", "Output"), g.map { it.name })
        assertEquals("48 kHz · Stereo · 160 kbps", g[0].rows[1].detail)
        assertEquals("FFmpeg, inside mpv\nGives out 32-bit float", g[0].rows[2].detail)
        assertEquals(listOf("Sound set", "Equalizer", "Same volume for every song"), g[1].rows.map { it.title })
        assertTrue(g[1].lit); assertEquals("Bass boost", g[1].rows[1].detail)
        assertEquals(listOf("Fade · Off", "Speed · 1.0×", "Whole song"), g[1].idle)
        assertEquals("PipeWire", g[2].rows[0].title)
        assertEquals("48 kHz · 32-bit float → 48 kHz · 32-bit\nNo change", g[2].rows[0].detail)
        assertFalse(g[2].lit)
        assertEquals("32-bit · 48 kHz", g[3].rows[1].detail)
        // no sound server answer: the output says so instead of guessing
        assertEquals("Can't tell on this computer", soundPathGroups(opus, PathSettings(), null)[3].rows.single().detail)
        assertEquals("48 kHz · 32-bit float → —", soundPathGroups(opus, PathSettings(), null)[2].rows[0].detail)
    }
}
