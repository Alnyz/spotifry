package com.spotifry.music.ui.nowplaying

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.ui.Icons

/**
 * The song's real sound under the cover: a lossless song gets Apple's three-ribbon mark, italic words and a slow glint
 * running across both; a lossy one gets headphones and plain words, so it never blends into the artist's name.
 */
@Composable
fun QualityPill(text: String, modifier: Modifier = Modifier) {
    val lossless = text.contains("Lossless")
    val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    // a quiet grey that a brighter band sweeps over: white on the dark theme, black on the light one
    val base = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    val glint = MaterialTheme.colorScheme.onBackground
    val shine = rememberInfiniteTransition(label = "shine").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    // the glint is painted over the words and the mark only where they have pixels (SrcIn), read while drawing so it never recomposes
    val shiny = if (!lossless) Modifier else Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val band = size.width * 0.7f
        val x = -band + (size.width + 2 * band) * shine.value
        drawRect(Brush.linearGradient(listOf(base, glint, base), start = Offset(x, 0f), end = Offset(x + band, 0f)), blendMode = BlendMode.SrcIn)
    }
    Row(modifier.then(shiny), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lossless) Icon(Icons.Lossless, null, Modifier.size(width = 22.dp, height = 14.dp), tint = ink)
        else Icon(Icons.Headphones, null, Modifier.size(14.dp), tint = ink)
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontStyle = if (lossless) FontStyle.Italic else FontStyle.Normal, color = ink, maxLines = 1)
    }
}
