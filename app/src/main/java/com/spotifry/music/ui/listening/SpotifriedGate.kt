package com.spotifry.music.ui.listening

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.ui.theme.Albert
import com.spotifry.music.ui.theme.AlwaysDark
import com.spotifry.music.ui.theme.StoryBlack

/**
 * The way into Spotifried: a dark card with this year in huge digits cut off by its bottom edge, and one streak of the
 * accent sweeping through them when the page opens. The digits are decoration, sized from the card's width; the word scales.
 */
@Composable
internal fun SpotifriedGate(year: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val still = rememberNoMotion()
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(still) { if (!still) sweep.animateTo(1f, tween(2400, 400, CubicBezierEasing(.2f, .8f, .2f, 1f))) }
    AlwaysDark(StoryBlack, systemBars = false) {
        val accent = MaterialTheme.colorScheme.primary
        BoxWithConstraints(
            modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(StoryBlack)
                .heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Spotifried, $year" },
        ) {
            val w = constraints.maxWidth.toFloat()
            val digits = maxWidth.value * .38f
            // card height follows the width, like the digits, so nothing is cut off at any font size
            val d = LocalDensity.current
            Box(Modifier.fillMaxWidth().heightIn(min = maxWidth * .46f)) {
                Text(
                    "Spotifried", Modifier.padding(start = 20.dp, top = 22.dp, end = 20.dp).clearAndSetSemantics { },
                    style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = .26.sp, color = Color.White),
                )
                // the streak's centre runs from past the right edge to past the left; at rest, or with motion off, it is out of sight
                val centre = w * (1.3f - 1.6f * sweep.value)
                val brush = Brush.linearGradient(
                    0f to Color.White, .35f to Color.White, .5f to accent, .65f to Color.White, 1f to Color.White,
                    start = Offset(centre - w * .4f, 0f), end = Offset(centre + w * .4f, 0f),
                )
                CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
                    Text(
                        year.toString(),
                        Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Top, unbounded = true).offset(x = 14.dp, y = digits.dp * .2f).clearAndSetSemantics { },
                        style = TextStyle(
                            fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = digits.sp, lineHeight = digits.sp,
                            letterSpacing = (-digits * .045f).sp, fontFeatureSettings = "tnum", brush = brush,
                        ),
                        maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }
}
