package com.spotifry.music.ui.listening

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.spotifry.music.R
import com.spotifry.music.ui.theme.Albert
import com.spotifry.music.ui.theme.AlwaysDark
import com.spotifry.music.ui.theme.StoryBlack
import kotlinx.coroutines.delay

// All lengths below are in "card units": the design was drawn on a 342 wide card, so one unit is width / 342.
private const val CARD = 342f
private const val DISC = 232f // record radius; its centre sits 26 left of the right edge and 10 below the bottom
private const val LABEL = 70f
internal const val PULL_MS = 1250f
internal const val OPEN_MS = 1900f // the pull, then the glint
private val Out = CubicBezierEasing(.16f, 1f, .3f, 1f)
private val Ease = CubicBezierEasing(.25f, .1f, .25f, 1f)
private val Glint = PathParser().parsePathString("M0-10C.7-2.6 2.6-.7 10 0 2.6.7.7 2.6 0 10-.7 2.6-2.6.7-10 0-2.6-.7-.7-2.6 0-10Z").toPath()

/** The record's shift (card units) and turn, and the glint, [ms] after the page opened. [OPEN_MS] and later is the rest. */
internal data class VinylPose(val shift: Float, val turn: Float, val glintAlpha: Float, val glintScale: Float, val glintTurn: Float)

internal fun vinylPose(ms: Float): VinylPose {
    val p = Out.transform((ms / PULL_MS).coerceIn(0f, 1f))
    val f = (ms / OPEN_MS).coerceIn(0f, 1f)
    return when {
        f < .55f -> VinylPose(96f * (1 - p), -150f * (1 - p), 0f, .2f, -45f)
        f < .72f -> Ease.transform((f - .55f) / .17f).let { VinylPose(96f * (1 - p), -150f * (1 - p), it, .2f + 1.05f * it, -45f * (1 - it)) }
        else -> Ease.transform((f - .72f) / .28f).let { VinylPose(96f * (1 - p), -150f * (1 - p), 1 - .15f * it, 1.25f - .25f * it, 0f) }
    }
}

/**
 * The way into Spotifried: a black sleeve with a record half out, its label in the accent, and this year in huge digits.
 * On open the record slides out and turns into place, then the rim glints once; the label keeps turning, once in 30 s.
 * Everything is drawn on the canvas, so it looks the same on every Android version. The digits are decoration, sized
 * from the card's width; the word scales.
 */
@Composable
internal fun SpotifriedGate(year: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val still = rememberNoMotion()
    val open = remember { Animatable(if (still) OPEN_MS else 0f) }
    LaunchedEffect(still) { if (!still) open.animateTo(OPEN_MS, tween(OPEN_MS.toInt(), easing = LinearEasing)) }
    // the label's turn: only while the card is composed (on screen) and the app is in front; only the label's layer redraws
    val spin = remember { mutableFloatStateOf(0f) }
    val life = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(still) {
        if (!still) life.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withInfiniteAnimationFrameMillis { it }
            while (true) withInfiniteAnimationFrameMillis { t -> spin.floatValue = (spin.floatValue + (t - last) * (360f / 30_000f)) % 360f; last = t }
        }
    }
    // a quick tap is held a moment so the press shows
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { if (pressed) held = true else { delay(220); held = false } }
    val nudge by animateFloatAsState(if (held) 9f else 0f, if (still) snap() else tween(500, easing = Out), label = "nudge")
    val give by animateFloatAsState(if (held) .982f else 1f, if (still) snap() else tween(320, easing = Out), label = "give")

    AlwaysDark(StoryBlack, systemBars = false) {
        val accent = MaterialTheme.colorScheme.primary
        val deep = lerp(accent, Color.Black, .3f)
        val grain = ImageBitmap.imageResource(R.drawable.grain)
        BoxWithConstraints(
            modifier.fillMaxWidth().graphicsLayer { scaleX = give; scaleY = give }.clip(RoundedCornerShape(24.dp)).background(StoryBlack)
                .heightIn(min = 48.dp).clickable(source, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Spotifried, $year" },
        ) {
            val digits = maxWidth.value * (110f / CARD)
            Box(Modifier.fillMaxWidth().heightIn(min = maxWidth * (184f / CARD)).drawBehind { sleeve() }) {
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer {
                            val pose = vinylPose(open.value)
                            translationX = pose.shift * size.width / CARD
                            rotationZ = pose.turn + nudge
                            transformOrigin = discOrigin(size)
                        }
                        .drawWithCache { record() },
                ) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { rotationZ = spin.floatValue; transformOrigin = discOrigin(size) }
                            .drawWithCache { label(accent, deep) },
                    )
                }
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer {
                            val pose = vinylPose(open.value)
                            alpha = pose.glintAlpha; scaleX = pose.glintScale; scaleY = pose.glintScale; rotationZ = pose.glintTurn
                            val g = glintAt(size)
                            transformOrigin = TransformOrigin(g.x / size.width, g.y / size.height)
                        }
                        .drawWithCache { glint() },
                )
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    Text(
                        year.toString(),
                        // the baseline sits 17 units above the bottom, as drawn; Albert's line keeps .21 of the size below it
                        Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Top, unbounded = true)
                            .offset(x = digits.dp * (15f / 110f), y = digits.dp * .05f).clearAndSetSemantics { },
                        style = TextStyle(
                            fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = digits.sp, lineHeight = digits.sp,
                            letterSpacing = (-digits * .045f).sp, fontFeatureSettings = "tnum", color = Color(0xFFF6F3EE),
                        ),
                        maxLines = 1, softWrap = false,
                    )
                }
                Spacer(Modifier.matchParentSize().drawWithCache { grain(grain) })
                Text(
                    "Spotifried", Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp).clearAndSetSemantics { },
                    style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = .14.sp, color = Color.White),
                )
            }
        }
    }
}

private fun discCentre(size: Size): Offset { val u = size.width / CARD; return Offset(size.width - 26 * u, size.height + 10 * u) }
private fun discOrigin(size: Size): TransformOrigin = discCentre(size).let { TransformOrigin(it.x / size.width, it.y / size.height) }
private fun glintAt(size: Size): Offset = discCentre(size) + Offset(-142f, -180f) * (size.width / CARD)

/** The sleeve: matt black, a touch lighter at the top left. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.sleeve() {
    val rx = size.width * 1.2f; val ry = size.height * 1.4f
    scale(1f, ry / rx, Offset.Zero) {
        drawRect(
            Brush.radialGradient(0f to Color(0xFF1C1C1F), .55f to Color(0xFF121214), 1f to Color(0xFF0E0E10), center = Offset.Zero, radius = rx),
            size = Size(size.width, size.height * rx / ry),
        )
    }
}

/** The record without its label: shadow on the sleeve, grooves, the gaps between songs, run-out, light and rim. Recorded once. */
private fun androidx.compose.ui.draw.CacheDrawScope.record() = run {
    val u = size.width / CARD
    val c = discCentre(size)
    val r = DISC * u
    val white = Color.White
    val shadowAt = c + Offset(-18f, -10f) * u
    val shadow = Brush.radialGradient(0f to Color.Black.copy(.7f), (206f / 246f) to Color.Black.copy(.7f), 1f to Color.Transparent, center = shadowAt, radius = 246 * u)
    val base = Brush.radialGradient(0f to Color(0xFF0A0A0B), .6f to Color(0xFF0A0A0B), 1f to Color(0xFF070708), center = c, radius = r)
    val groove = Stroke(.6f * u)
    // the light caught by the grooves: one pair of wedges, opposite each other; fractions of a turn from the top, clockwise
    val light = Brush.sweepGradient(
        0f to Color.Transparent, (100f / 360) to white.copy(.035f), (132f / 360) to white.copy(.16f), (160f / 360) to white.copy(.035f),
        (200f / 360) to Color.Transparent, (285f / 360) to white.copy(.04f), (312f / 360) to white.copy(.2f), (338f / 360) to white.copy(.04f),
        1f to Color.Transparent, center = c,
    )
    onDrawBehind {
        drawCircle(shadow, 246 * u, shadowAt)
        drawCircle(base, r, c)
        for (i in 0 until 110) drawCircle(white.copy(.05f), (96.5f + i * (130.5f / 109)) * u, c, style = groove)
        for ((at, w) in listOf(129.5f to 2f, 159.5f to 2f, 195.25f to 1.5f)) drawCircle(white.copy(.07f), at * u, c, style = Stroke(w * u))
        drawCircle(Color(0xFF0B0B0C), 94 * u, c)
        drawCircle(white.copy(.05f), 95 * u, c, style = Stroke(u))
        rotate(-90f, c) { drawCircle(light, r, c) }
        drawCircle(white.copy(.04f), 226.5f * u, c, style = Stroke(u))
        drawCircle(Color(0xFF0B0B0C), 229.5f * u, c, style = Stroke(5 * u))
        drawCircle(white.copy(.09f), 231.5f * u, c, style = Stroke(u))
    }
}

/** The label in the accent, with a printed arc so the turn shows, and the spindle hole. */
private fun androidx.compose.ui.draw.CacheDrawScope.label(accent: Color, deep: Color) = run {
    val u = size.width / CARD
    val c = discCentre(size)
    val face = Brush.radialGradient(
        0f to lerp(accent, Color.White, .18f), .45f to accent, 1f to lerp(accent, deep, .22f),
        center = c + Offset(-16.8f, -28f) * u, radius = 130.9f * u,
    )
    val sheen = Brush.verticalGradient(0f to Color.White.copy(.18f), .12f to Color.Transparent, startY = c.y - LABEL * u, endY = c.y + LABEL * u)
    onDrawBehind {
        drawCircle(face, LABEL * u, c)
        drawCircle(deep.copy(.8f), 41 * u, c, style = Stroke(u))
        drawCircle(Color.Black.copy(.18f), 63 * u, c, style = Stroke(u))
        val a = 54.75f * u
        drawArc(deep.copy(.85f), 195f, 90f, false, c - Offset(a, a), Size(2 * a, 2 * a), style = Stroke(2.5f * u))
        drawCircle(sheen, 67.5f * u, c, style = Stroke(4 * u))
        drawCircle(Color.Black.copy(.25f), 69.5f * u, c, style = Stroke(u))
        drawCircle(Color(0xFF050505), 3.8f * u, c)
    }
}

/** One small four-point star where the light hits the rim, with a soft glow. */
private fun androidx.compose.ui.draw.CacheDrawScope.glint() = run {
    val u = size.width / CARD
    val at = glintAt(size)
    val glow = Brush.radialGradient(0f to Color.White.copy(.55f), 1f to Color.Transparent, center = at, radius = 12 * u)
    onDrawBehind {
        drawCircle(glow, 12 * u, at)
        translate(at.x, at.y) { scale(.9f * u, Offset.Zero) { drawPath(Glint, Color.White) } }
    }
}

/** Fine film grain over the whole card; the tile is 256 px, drawn at 256 dp like the design. */
private fun androidx.compose.ui.draw.CacheDrawScope.grain(tile: ImageBitmap) = run {
    val shader = ImageShader(tile, TileMode.Repeated, TileMode.Repeated).apply { setLocalMatrix(Matrix().apply { setScale(density, density) }) }
    val brush = ShaderBrush(shader)
    onDrawBehind { drawRect(brush, alpha = .55f, blendMode = BlendMode.Overlay) }
}
