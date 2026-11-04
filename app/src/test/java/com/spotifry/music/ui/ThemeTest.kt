package com.spotifry.music.ui

import androidx.compose.ui.graphics.Color
import com.spotifry.music.ui.theme.darkColors
import com.spotifry.music.ui.theme.lightColors
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test fun darkIsDefaultPalette() {
        assertEquals(Color(0xFF0C0D10), darkColors.background)
        assertEquals(Color(0xFFF4F5F7), lightColors.background)
        assertEquals(Color(0xFF5B95FF), darkColors.primary)
        assertEquals(Color(0xFFFFFFFF), lightColors.onPrimary)
    }
}
