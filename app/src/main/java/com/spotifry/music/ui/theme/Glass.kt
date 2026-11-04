package com.spotifry.music.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Dark tint over the blurred backdrop. Alphas are chosen so text stays above 4.5:1 (see GlassContrastTest). */
object GlassTint {
    val dark = darkColors.background.copy(alpha = 0.84f)
    val light = lightColors.background.copy(alpha = 0.86f)
}

/** WCAG 2 contrast ratio between two opaque colors. */
fun contrastRatio(fg: Color, bg: Color): Double {
    val a = fg.luminance().toDouble() + 0.05
    val b = bg.luminance().toDouble() + 0.05
    return maxOf(a, b) / minOf(a, b)
}

/**
 * The glass look. Used on exactly two components: the mini player and the Now playing controls panel.
 * Blurs whatever [state]'s hazeSource draws behind it; on devices that cannot blur it shows the plain tint.
 */
@Composable
fun Modifier.glass(state: HazeState, shape: Shape): Modifier {
    val dark = isDark()
    val tint = if (dark) GlassTint.dark else GlassTint.light
    val base = if (dark) darkColors.background else lightColors.background
    val edge = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    return this.clip(shape)
        .hazeEffect(state) { style = HazeStyle(backgroundColor = base, tint = HazeTint(tint), blurRadius = 22.dp) }
        .border(1.dp, edge, shape)
}
