package com.spotifry.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of the upper part when it holds an entrance, and the strip left above Radio when it is alone. */
val ENTRANCE_UPPER = 92.dp
private val ALONE_STRIP = 10.dp

/**
 * Home's entrances in one card of two flat tones, split by a hand-drawn wave: [upper] above it (Your rotation; not drawn
 * when [hasUpper] is false) and [lower] under it (Radio). The wave is Radio's own mark: it is gentle, and swells into
 * two full swings while a station is [live].
 */
@Composable
fun WaveCard(hasUpper: Boolean, live: Boolean, upper: @Composable () -> Unit, lower: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val seam = scheme.outlineVariant
    val lowerTone = scheme.surfaceContainerHigh
    val upperH: Dp = if (hasUpper) ENTRANCE_UPPER else ALONE_STRIP
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surface).drawBehind {
            val line = wave(size, (upperH + 8.dp).toPx(), live, density)
            val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, lowerTone)
            drawPath(line, seam, style = Stroke(1.6.dp.toPx()))
        },
    ) {
        // always composed: Your rotation reads its week even while it has nothing to show yet
        Box(Modifier.fillMaxWidth().height(upperH)) { upper() }
        Spacer(Modifier.height(8.dp))
        lower()
    }
}

/** The seam: a calm curve, or (live) two swings across the width, around the height [b]. [d] is pixels per dp. */
internal fun wave(size: Size, b: Float, live: Boolean, d: Float): Path {
    val w = size.width
    fun x(at: Float) = at / 372f * w
    return Path().apply {
        if (!live) {
            val a = 13f * d
            moveTo(0f, b + 4f * d)
            cubicTo(x(70f), b - a, x(120f), b + a, x(190f), b)
            cubicTo(x(260f), b - a, x(310f), b - a, w, b - 3f * d)
        } else {
            val a = 16f * d
            moveTo(0f, b)
            cubicTo(x(30f), b - a, x(60f), b - a, x(93f), b)
            cubicTo(x(126f), b + a, x(156f), b + a, x(186f), b)
            cubicTo(x(216f), b - a, x(249f), b - a, x(279f), b)
            cubicTo(x(309f), b + a, x(342f), b + a, w, b)
        }
    }
}
