package com.spotifry.music.ui.names

import androidx.compose.animation.togetherWith
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spotifry.music.R
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.Shown
import com.spotifry.music.ui.home.Bodoni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Marker = FontFamily(Font(R.font.permanentmarker))
private val Pirata = FontFamily(Font(R.font.pirataone))
private val Anton = FontFamily(Font(R.font.anton))
private val Russo = FontFamily(Font(R.font.russoone))
private val Yellowtail = FontFamily(Font(R.font.yellowtail))
private val TiltNeon = FontFamily(Font(R.font.tiltneon))
private val Silkscreen = FontFamily(Font(R.font.silkscreen_bold, FontWeight.Bold))

/** The styles written in capitals. */
val NameStyle.caps get() = this == NameStyle.BRUSH || this == NameStyle.BLOCK || this == NameStyle.OUTLINE || this == NameStyle.PIXEL

private val DarkInk = Color(0xFF15181C)

/**
 * A playlist or artist name as [shown]: text in a style, or a logo picture ([logo]) fitted in 300 × 118 dp. A logo that is
 * missing or can't be read shows the name in [fallback] instead, never a blank header. [glow] is the cover's colour (Neon).
 */
@Composable
fun StyledName(name: String, shown: Shown, logo: File?, ink: Color, glow: Color, size: TextUnit, modifier: Modifier = Modifier, fallback: NameStyle = NameStyle.CLASSIC) {
    val tint = when (shown) { is Shown.Own -> shown.tint; is Shown.Official -> shown.tint; is Shown.Text -> false }
    val picture by produceState<ImageBitmap?>(null, shown, logo?.path) {
        value = if (shown is Shown.Text || logo == null) null
        else withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(logo.path)?.asImageBitmap() }.getOrNull() }
    }
    // the official logo arrives a moment after the page opens: it fades in over the name instead of popping in
    androidx.compose.animation.AnimatedContent(
        picture, modifier, label = "name",
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(450, delayMillis = 90)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(450, delayMillis = 90), initialScale = .94f))
                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        },
    ) { pic -> NameOrLogo(name, shown, pic, tint, ink, glow, size, fallback) }
}

@Composable
private fun NameOrLogo(name: String, shown: Shown, pic: ImageBitmap?, tint: Boolean, ink: Color, glow: Color, size: TextUnit, fallback: NameStyle) {
    val modifier = Modifier
    if (pic != null) {
        // never drawn bigger than the picture itself: stretching makes it soft
        val (w, h) = with(LocalDensity.current) { minOf(300.dp, pic.width.toDp()) to minOf(118.dp, pic.height.toDp()) }
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(300.dp, 118.dp), Alignment.Center) {
                Image(pic, name, Modifier.size(w, h), contentScale = ContentScale.Fit, colorFilter = if (tint) ColorFilter.tint(ink, BlendMode.SrcIn) else null)
            }
            // some logos are hard to read: the plain name goes under it
            Text(name, Modifier.padding(top = 6.dp), color = ink.copy(alpha = .9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    } else {
        val style = (shown as? Shown.Text)?.style ?: fallback
        NameText(name, style, ink, glow, size, modifier)
    }
}

@Composable
private fun NameText(name: String, s: NameStyle, ink: Color, glow: Color, size: TextUnit, modifier: Modifier) {
    val px = with(LocalDensity.current) { size.toPx() }
    val light = ink.luminance() < .5f
    fun at(scale: Float) = (size.value * scale).sp
    val base = TextStyle(color = ink, textAlign = TextAlign.Center)
    val ts = when (s) {
        NameStyle.CLASSIC -> base.copy(fontFamily = Bodoni, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = at(1.04f), letterSpacing = (-.025).em)
        NameStyle.BRUSH -> base.copy(fontFamily = Marker, fontSize = at(.8f), lineHeight = at(.8f * 1.08f), letterSpacing = .01.em)
        NameStyle.GOTHIC -> base.copy(fontFamily = Pirata, fontSize = at(1.02f), lineHeight = at(1.02f * 1.02f), letterSpacing = .01.em)
        NameStyle.BLOCK -> base.copy(fontFamily = Anton, fontSize = at(.9f), lineHeight = at(.9f), letterSpacing = .01.em)
        NameStyle.OUTLINE -> base.copy(fontFamily = Russo, fontSize = at(.7f), lineHeight = at(.7f * 1.08f), letterSpacing = .03.em, drawStyle = Stroke(width = px * .036f))
        NameStyle.SCRIPT -> base.copy(fontFamily = Yellowtail, fontSize = at(1.08f), lineHeight = at(1.08f * 1.12f))
        NameStyle.NEON -> base.copy(fontFamily = TiltNeon, fontSize = at(.88f), lineHeight = at(.88f * 1.12f), color = if (light) DarkInk else Color.White, shadow = Shadow(glow, Offset.Zero, px * (if (light) .2f else .36f)))
        NameStyle.PIXEL -> base.copy(fontFamily = Silkscreen, fontWeight = FontWeight.Bold, fontSize = at(.68f), lineHeight = at(.68f * 1.15f), letterSpacing = .02.em,
            shadow = Shadow(if (light) DarkInk.copy(alpha = .18f) else Color.Black.copy(alpha = .45f), Offset(px * .05f, px * .05f), 0f))
    }
    Text(
        if (s.caps) name.uppercase() else name, if (s == NameStyle.BRUSH) modifier.rotate(-2f) else modifier,
        style = ts, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** The small spaced capitals under the name ("24 SONGS · 18 SAVED"). */
@Composable
fun NameMeta(text: String, ink: Color, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier, color = ink.copy(alpha = .78f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = .14.em, lineHeight = 16.sp, textAlign = TextAlign.Center,
)
