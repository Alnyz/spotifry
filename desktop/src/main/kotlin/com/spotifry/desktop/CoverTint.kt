package com.spotifry.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb

/**
 * The colour a cover is mostly made of: dull pixels (near black, near white, grey) are ignored,
 * the rest are grouped by hue and the biggest group is averaged. Null when nothing colourful is left.
 */
fun dominantColor(pixels: IntArray): Color? {
    val groups = Array(12) { LongArray(4) } // r, g, b, count per 30-degree hue bucket
    for (p in pixels) {
        val r = (p shr 16 and 0xFF) / 255f; val g = (p shr 8 and 0xFF) / 255f; val b = (p and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (max < 0.15f || min > 0.9f || d / max < 0.25f) continue
        val h = (when (max) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60 + 360) % 360
        val slot = groups[(h / 30).toInt().coerceIn(0, 11)]
        slot[0] += (r * 255).toLong(); slot[1] += (g * 255).toLong(); slot[2] += (b * 255).toLong(); slot[3]++
    }
    val best = groups.maxByOrNull { it[3] } ?: return null
    if (best[3] == 0L) return null
    return Color((best[0] / best[3]).toInt(), (best[1] / best[3]).toInt(), (best[2] / best[3]).toInt())
}

/** [dominantColor] of this image, looked at on a 32 by 32 grid. */
fun ImageBitmap.tint(): Color? {
    val w = width; val h = height
    if (w == 0 || h == 0) return null
    val all = IntArray(w * h).also { readPixels(it) }
    val grid = IntArray(32 * 32) { all[(it / 32 * h / 32) * w + (it % 32) * w / 32] }
    return dominantColor(grid)
}
