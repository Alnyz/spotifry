package com.spotifry.desktop

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The app's logo: a play button made of sound bars on ember orange, the phone's launcher icon cropped to a rounded square.
 * The bars are the phone's own path data (108 grid), moved so they sit in the middle of a 72 by 72 square.
 */
object Logo {
    private const val BARS = "M42.49,30.02 H42.49 A3.5,3.5 0 0 1 45.99,33.52 V74.48 A3.5,3.5 0 0 1 42.49,77.98 H42.49 A3.5,3.5 0 0 1 38.99,74.48 V33.52 A3.5,3.5 0 0 1 42.49,30.02 Z M52,35.51 H52 A3.5,3.5 0 0 1 55.5,39.01 V68.99 A3.5,3.5 0 0 1 52,72.49 H52 A3.5,3.5 0 0 1 48.5,68.99 V39.01 A3.5,3.5 0 0 1 52,35.51 Z M61.51,41 H61.51 A3.5,3.5 0 0 1 65.01,44.5 V63.5 A3.5,3.5 0 0 1 61.51,67 H61.51 A3.5,3.5 0 0 1 58.01,63.5 V44.5 A3.5,3.5 0 0 1 61.51,41 Z M71.01,46.49 H71.01 A3.5,3.5 0 0 1 74.51,49.99 V58.01 A3.5,3.5 0 0 1 71.01,61.51 H71.01 A3.5,3.5 0 0 1 67.51,58.01 V49.99 A3.5,3.5 0 0 1 71.01,46.49 Z M80.52,50.5 H80.52 A3.5,3.5 0 0 1 84.02,54 V54 A3.5,3.5 0 0 1 80.52,57.5 H80.52 A3.5,3.5 0 0 1 77.02,54 V54 A3.5,3.5 0 0 1 80.52,50.5 Z"

    val image: ImageVector = ImageVector.Builder("logo", 72.dp, 72.dp, 72f, 72f).apply {
        // rounded square, lighter at the top like the phone's background
        addPath(
            addPathNodes("M16,0 H56 A16,16 0 0 1 72,16 V56 A16,16 0 0 1 56,72 H16 A16,16 0 0 1 0,56 V16 A16,16 0 0 1 16,0 Z"),
            fill = Brush.verticalGradient(listOf(Color(0xFFFF8A3D), Color(0xFFC2410C))),
        )
        // the bars, moved so their middle (61.5, 54) lands on the square's middle (36, 36)
        group(translationX = -25.5f, translationY = -18f) { addPath(addPathNodes(BARS), fill = SolidColor(Color.White)) }
    }.build()

    /** For the tray: a full square with no transparent corners, because Java's tray paints white behind transparent pixels. */
    val tray: ImageVector = ImageVector.Builder("logo-tray", 72.dp, 72.dp, 72f, 72f).apply {
        addPath(addPathNodes("M0,0 H72 V72 H0 Z"), fill = Brush.verticalGradient(listOf(Color(0xFFFF8A3D), Color(0xFFC2410C))))
        group(translationX = -25.5f, translationY = -18f) { addPath(addPathNodes(BARS), fill = SolidColor(Color.White)) }
    }.build()
}
