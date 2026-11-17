package com.spotifry.music.data

import kotlin.math.max
import kotlin.math.min

/** Where the square cover window sits on a picture, in the picture's own pixels. */
data class CropBox(val x: Int, val y: Int, val size: Int)

/**
 * The square window over a [w] by [h] picture. [zoom] is 1 when the picture just fills the window and grows from there;
 * [panX] and [panY] are how far the picture was dragged, as a share of what it can move (-1 to 1, 0 is centred).
 */
fun cropBox(w: Int, h: Int, zoom: Float, panX: Float, panY: Float): CropBox {
    val z = max(zoom, 1f)
    val size = (min(w, h) / z).toInt().coerceAtLeast(1)
    val x = ((w - size) / 2f * (1f + panX.coerceIn(-1f, 1f))).toInt().coerceIn(0, w - size)
    val y = ((h - size) / 2f * (1f + panY.coerceIn(-1f, 1f))).toInt().coerceIn(0, h - size)
    return CropBox(x, y, size)
}
