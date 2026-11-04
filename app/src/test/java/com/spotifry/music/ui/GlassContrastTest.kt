package com.spotifry.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.spotifry.music.ui.theme.Ember
import com.spotifry.music.ui.theme.GlassTint
import com.spotifry.music.ui.theme.contrastRatio
import com.spotifry.music.ui.theme.darkColors
import com.spotifry.music.ui.theme.lightColors
import com.spotifry.music.ui.theme.placeholderTones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassContrastTest {
    private val backdrops = placeholderTones + Ember + Color.Black + Color.White + Color(0xFF22262E)

    @Test fun contrastRatioMatchesWcagReference() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
    }

    @Test fun darkGlassKeepsTextReadableOverWorstBackdrops() {
        backdrops.forEach { b ->
            val glass = GlassTint.dark.compositeOver(b)
            assertTrue("dark text contrast over $b", contrastRatio(darkColors.onSurface, glass) >= 4.5)
            assertTrue("dark secondary contrast over $b", contrastRatio(darkColors.onSurfaceVariant, glass) >= 4.5)
        }
    }

    @Test fun lightGlassKeepsTextReadableOverWorstBackdrops() {
        backdrops.forEach { b ->
            val glass = GlassTint.light.compositeOver(b)
            assertTrue("light text contrast over $b", contrastRatio(lightColors.onSurface, glass) >= 4.5)
            assertTrue("light secondary contrast over $b", contrastRatio(lightColors.onSurfaceVariant, glass) >= 4.5)
        }
    }
}
