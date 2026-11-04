package com.spotifry.music.ui

import androidx.compose.ui.graphics.Color
import com.spotifry.music.ui.theme.AccentChoice
import com.spotifry.music.ui.theme.contrast
import com.spotifry.music.ui.theme.hsv
import com.spotifry.music.ui.theme.readableAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentTest {
    private val darkBg = Color(0xFF0C0D10)

    @Test fun anyPickedColourEndsUpReadableInBothThemes() {
        for (h in 0 until 360 step 15) for (s in listOf(0f, .3f, .7f, 1f)) for (v in listOf(.05f, .3f, .6f, 1f)) {
            val a = readableAccent(Color.hsv(h.toFloat(), s, v))
            assertTrue("dark theme $h/$s/$v", contrast(a.dark, darkBg) >= 4.5f)
            assertTrue("light theme $h/$s/$v", contrast(a.light, Color.White) >= 4.5f)
        }
    }

    @Test fun readableColourIsLeftAlone() {
        val ember = AccentChoice.EMBER.accent!!.dark
        assertEquals(ember, readableAccent(ember).dark)
    }

    @Test fun hueIsKept() {
        val a = readableAccent(Color.hsv(210f, .9f, .25f))
        assertEquals(210f, hsv(a.dark).first, 2f)
        assertEquals(210f, hsv(a.light).first, 2f)
    }

    @Test fun readyColoursPassToo() {
        for (c in AccentChoice.entries.mapNotNull { it.accent }) {
            assertTrue(contrast(c.dark, darkBg) >= 4.5f)
            assertTrue(contrast(c.light, Color.White) >= 4.5f)
        }
    }
}
