package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPathTest {
    @Test fun `lists source, volume, equalizer and output in plain words`() {
        val rows = audioPathRows("Lossless", "monochrome", leveling = true, eqOn = true, eqName = AUTOEQ_PREFIX + "Sennheiser HD 600", output = "Phone speaker")
        assertEquals(
            listOf(
                AudioPathRow("Source", "Lossless server · Lossless"),
                AudioPathRow("Same volume for every song", "On"),
                AudioPathRow("Equalizer", "Sennheiser HD 600"),
                AudioPathRow("Output", "Phone speaker"),
            ), rows,
        )
    }

    @Test fun `a file on the phone, equalizer off, own mix`() {
        assertEquals("On this phone · FLAC", audioPathRows("FLAC", null, false, false, null, "x")[0].value)
        assertEquals("Off", audioPathRows(null, "youtube", false, false, "Rock", "x")[2].value)
        assertEquals("Your own mix", audioPathRows(null, "youtube", false, true, null, "x")[2].value)
        assertEquals("YouTube", audioPathRows(null, "youtube", false, false, null, "x")[0].value)
    }
}
