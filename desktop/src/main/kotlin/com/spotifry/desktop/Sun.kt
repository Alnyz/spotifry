package com.spotifry.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.hourName
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// The phone's sun (app/.../ui/listening/Sun.kt), with the desktop's colours and no phone lifecycle.

private const val RAYS = 288
private const val RAYS_SMALL = 72
private const val HOLD = 300f
/** While music plays the flames move at this rate; the rest of the time nothing is drawn at all. */
private const val LIVE_FRAME_MS = 33L
private const val SUN_INNER = .205f
private const val SUN_REACH = .285f

private val spread = FloatArray(RAYS) { i -> val x = sin(i * 12.9898) * 43758.5453; (x - floor(x)).toFloat() }

/** Where the sun is now. Kept by the page, so it doesn't grow again each time it comes back into view. */
class SunMotion {
    val now = FloatArray(24)
    var scale = 0f
    var clock = 0f
    /** How much the flames move: eases out when music pauses, so they settle instead of jumping. */
    var wave = 0f
}

/** Which hour a click at ([x], [y]) on a sun [width] wide falls on: midnight at the top, going clockwise. Null in the middle. */
internal fun hourAt(x: Float, y: Float, width: Float): Int? {
    val dx = x - width / 2; val dy = y - width / 2
    if (hypot(dx, dy) / width < .2f) return null
    val a = (atan2(dy, dx) + PI / 2 + 2 * PI) % (2 * PI)
    return (a / (2 * PI) * 24).roundToInt() % 24
}

/**
 * The 24 hours of the day as a burning sun. Each ray's direction is a time and its length follows the minutes heard at
 * that hour; the unevenness between neighbours is only the look of fire. [live]: the flames move, while music plays.
 */
@Composable
fun Sun(
    hours: List<Long>, scale: Float, live: Boolean, motion: SunMotion, modifier: Modifier = Modifier, small: Boolean = false,
    selected: Int? = null, onTap: ((Int?) -> Unit)? = null, labels: Boolean = true,
) {
    val peak = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val target = remember(hours) { FloatArray(24) { hours[it] / peak } }
    var frame by remember { mutableLongStateOf(0L) }
    var shapeFrame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(target, scale, live) {
        var last = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { it }
            val dt = ((t - last) / 1_000_000f).coerceAtMost(50f); last = t
            val k = 1 - exp(-dt / HOLD)
            var moving = false
            for (i in 0 until 24) { val d = target[i] - motion.now[i]; motion.now[i] += d * k; if (kotlin.math.abs(d) > .002f) moving = true }
            val d = scale - motion.scale; motion.scale += d * k; if (kotlin.math.abs(d) > .002f) moving = true
            val w = (if (live) 1f else 0f) - motion.wave; motion.wave += w * k; if (kotlin.math.abs(w) > .01f) moving = true else if (!live) motion.wave = 0f
            motion.clock += dt / 1000
            frame++
            if (moving) shapeFrame++
            else if (!live) break
            else delay(LIVE_FRAME_MS)
        }
    }

    val dark = Palette.dark
    val hot = Palette.accentChoice.dark
    val line = Palette.accent
    val stops = remember(hot, line, dark) {
        if (dark) arrayOf(0f to deeper(hot), .35f to hot, .72f to lerp(hot, Color.White, .45f), 1f to lerp(hot, Color.White, .85f))
        else arrayOf(0f to lerp(line, Color.Black, .37f), .45f to line, 1f to hot)
    }
    val inner = if (small) .17f else SUN_INNER
    val reach = if (small) .31f else SUN_REACH
    val rayCount = if (small) RAYS_SMALL else RAYS
    val labelColor = Palette.ink2
    val measurer = rememberTextMeasurer()
    val midnight = remember(measurer) { measurer.measure(hourName(0), TextStyle(fontSize = 11.sp)) }
    val noon = remember(measurer) { measurer.measure(hourName(12), TextStyle(fontSize = 11.sp)) }

    fun length(i: Int, moving: Boolean): Float {
        val a = i.toFloat() / rayCount * 2 * PI.toFloat()
        val wave = if (moving) 1 + motion.wave * (.07f * sin(motion.clock * 1.7f + a * 9) + .05f * sin(motion.clock * 2.9f - a * 17)) else 1f
        return valueAt(motion.now, a).pow(.7f) * (.5f + .5f * spread[i * (RAYS / rayCount)]) * wave * motion.scale
    }

    Box(modifier.aspectRatio(1f)) {
        // the glow: blurred once and left alone until the shape changes, so the moving rays don't cost a new blur each frame
        Canvas(
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); drawCircle(Color.Black, size.width * inner, blendMode = BlendMode.Clear) }
                .graphicsLayer { val r = size.width * (if (small) .05f else .08f); renderEffect = BlurEffect(r, r, TileMode.Decal) },
        ) {
            shapeFrame
            val w = size.width; val c = w / 2
            val path = Path()
            val step = if (small) 2 else 4
            for (i in 0 until rayCount step step) {
                val r = (inner + reach * length(i, false) * .9f) * w
                val a = i.toFloat() / rayCount * 2 * PI.toFloat() - PI.toFloat() / 2
                val x = c + cos(a) * r; val y = c + sin(a) * r
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, hot, alpha = if (dark) .62f else .42f)
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer().then(if (onTap != null) Modifier.pointerInput(onTap) { detectTapGestures { p -> onTap(hourAt(p.x, p.y, size.width.toFloat())) } } else Modifier)) {
            frame
            val w = size.width; val c = w / 2
            val r0 = w * inner; val mx = w * reach
            val brush = Brush.radialGradient(*stops, center = Offset(c, c), radius = r0 + mx)
            val pick = selected?.let { it.toFloat() / 24 * rayCount }
            val half = rayCount / 48f
            fun point(i: Int, r: Float): Offset { val a = i.toFloat() / rayCount * 2 * PI.toFloat() - PI.toFloat() / 2; return Offset(c + cos(a) * r, c + sin(a) * r) }
            if (!small) for (i in 0 until rayCount step 3) {
                drawLine(brush, point(i, r0 + 1), point(i, r0 + 1 + maxOf(length(i, true) * mx * .72f, .5f)), w * .016f, StrokeCap.Round, alpha = if (pick == null) .3f else .07f)
            }
            for (i in 0 until rayCount) {
                val dim = if (pick == null) 1f else { val d = kotlin.math.abs(i - pick); if (minOf(d, rayCount - d) <= half) 1f else .18f }
                val e = spread[i * (RAYS / rayCount)]
                drawLine(brush, point(i, r0 + 1), point(i, r0 + 1 + maxOf(length(i, true) * mx, w * .0033f)), w * (if (small) .012f else .0058f), StrokeCap.Round, alpha = dim * (.55f + .45f * e))
            }
            if (!small && labels) {
                val rl = r0 - 12 * density
                drawText(midnight, labelColor, Offset(c - midnight.size.width / 2f, c - rl - midnight.size.height / 2f))
                drawText(noon, labelColor, Offset(c - noon.size.width / 2f, c + rl - noon.size.height / 2f))
            }
        }
    }
}

/** Between two hours the length eases from one to the other, so an hour with listening shows as one round tongue. */
private fun valueAt(now: FloatArray, a: Float): Float {
    val h = ((a / (2 * PI.toFloat()) * 24) + 24) % 24
    val i = h.toInt() % 24
    val f = h - h.toInt()
    val s = (1 - cos(f * PI.toFloat())) / 2
    return (now[i] * (1 - s) + now[(i + 1) % 24] * s).coerceAtLeast(0f)
}

/** The same colour hotter and darker, for the base of the flames on the dark theme. */
private fun deeper(c: Color): Color {
    val argb = c.toArgb()
    val hsv = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)
    return Color.hsv(((hsv[0] * 360) - 4 + 360) % 360, minOf(1f, hsv[1] + .18f), hsv[2] * .91f)
}
