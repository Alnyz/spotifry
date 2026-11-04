package com.spotifry.music.ui.share

import androidx.compose.ui.graphics.Color
import com.spotifry.music.ui.theme.AccentChoice
import com.spotifry.music.ui.theme.contrast
import com.spotifry.music.ui.theme.readableAccent
import org.junit.Assert.assertTrue
import org.junit.Test

class CardInkTest {
    // every ready accent, plus picked colours from pale to deep: the picture's text must read on each background
    private val accents = AccentChoice.entries.mapNotNull { it.accent?.dark } +
        listOf(Color(0xFFFFF59D), Color(0xFF7B1FA2), Color(0xFF00897B)).map { readableAccent(it).dark }

    @Test fun textReadsOnEveryBackground() {
        for (a in accents) for (bg in CardBackground.entries) {
            val ink = cardInk(bg, Color(0xFF3A1520), a)
            assertTrue("$bg on $a", contrast(ink.text, ink.top) >= 4.5f && contrast(ink.text, ink.bottom) >= 4.5f)
        }
    }
}
