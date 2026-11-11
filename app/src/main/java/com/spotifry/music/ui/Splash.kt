package com.spotifry.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.spotifry.music.ui.components.FADE_MS
import com.spotifry.music.ui.firstlaunch.BigHeading
import com.spotifry.music.ui.firstlaunch.WELCOME_QUESTION
import com.spotifry.music.ui.firstlaunch.welcomeHeading
import com.spotifry.music.ui.home.greetingText
import com.spotifry.music.ui.theme.Ember
import com.spotifry.music.ui.theme.EmberDeep
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/*
 * The animated splash (design/splash-mockup-2b.html, idea 1, "Five bars, five rows"). The system's still splash shows the logo;
 * the app's first frame draws the same picture in the same place over the real screen, which is already composed and loading
 * underneath. The play button turns a quarter and lies down as five lines, and each line flies to a part of the page and becomes
 * it. One clock, one Canvas, drawn in the draw step only. It takes no touches: the first touch anywhere ends it and still reaches
 * the app. It plays on a cold start only, never with the system's animations off, and is not seen by screen readers.
 */

/** Where the app opened, for the splash: the bars only fly to a page they know. */
enum class SplashPage { HOME, WELCOME }

/** Handed over by MainActivity on a cold start. [icon]: the centre of the system splash's icon, once Android says where it is. */
class SplashStart {
    val icon = mutableStateOf<Offset?>(null)
}

/** Plays on a fresh start of the screen ([fresh]: no saved state), once per process, and never with animations removed. */
fun shouldPlaySplash(fresh: Boolean, playedBefore: Boolean, animationsOff: Boolean) = fresh && !playedBefore && !animationsOff

/** Where the parts of the page the bars land on are, in root pixels. Only written while a splash plays. */
internal object SplashTargets {
    @Volatile var active = false
    val rects = mutableStateMapOf<String, Rect>()
    fun start() { rects.clear(); active = true }
    fun report(key: String, rect: Rect) { if (active) rects[key] = rect }
}

/** Tells the splash where this element is, as [key]. Costs nothing once the splash is over. */
fun Modifier.splashTarget(key: String?): Modifier = if (key == null) this else onGloballyPositioned {
    if (SplashTargets.active) SplashTargets.report(key, Rect(it.localToRoot(Offset.Zero), it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat()))))
}

// ---- the timeline, in ms (the mockup's numbers) ----

internal val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
internal val Decelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
internal val Accelerate = CubicBezierEasing(.3f, 0f, 1f, 1f)

internal object SplashTime {
    const val FLY0 = 360f
    const val GAP = 45f
    const val FLY = 500f
    const val WRITE0 = 860f
    const val WRITE = 280f
    /** Our part ends here; then the bars fade over Home's own fade time. */
    const val OURS = 1250f
    const val END = OURS + FADE_MS
    /** Without places to fly to: the logo shrinks and fades while the page shows. */
    const val FALLBACK = 300f
    /** Gone after this, whatever the frames did. */
    const val CAP_MS = 2500L
}

internal fun prog(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
internal fun turn(t: Float) = Standard.transform(prog(t, 60f, 360f))
internal fun discShrink(t: Float) = Accelerate.transform(prog(t, 40f, 330f))
private fun flightStart(i: Int) = SplashTime.FLY0 + i * SplashTime.GAP
internal fun flight(i: Int, t: Float) = Standard.transform(prog(t, flightStart(i), flightStart(i) + SplashTime.FLY))
/** White to the colour of what the bar becomes, over the second part of its flight. */
internal fun tint(i: Int, t: Float) = Standard.transform(prog(t, flightStart(i) + SplashTime.FLY * .35f, flightStart(i) + SplashTime.FLY))
/** A landed bar opening into its pieces, gaps growing from its middle. */
internal fun split(i: Int, t: Float) = Standard.transform(prog(t, flightStart(i) + SplashTime.FLY - 140f, flightStart(i) + SplashTime.FLY + 160f))
/** How much of line [i] has been written, as the bar pulls back to the right. */
internal fun write(i: Int, t: Float) = Standard.transform(prog(t, SplashTime.WRITE0 + i * SplashTime.GAP, SplashTime.WRITE0 + i * SplashTime.GAP + SplashTime.WRITE))
/** The rest of the page showing. */
internal fun pageIn(t: Float, page: SplashPage) = Decelerate.transform(prog(t, if (page == SplashPage.WELCOME) 950f else 880f, SplashTime.OURS))
internal fun endFade(t: Float) = 1f - prog(t, SplashTime.OURS, SplashTime.END)
internal fun fallback(t: Float) = Standard.transform(prog(t, SplashTime.FLY0, SplashTime.FLY0 + SplashTime.FALLBACK))

/** The bars fly only when the page is known and its main parts have reported where they are; anything else is the plain fade. */
internal fun fliesTo(page: SplashPage?, known: Set<String>): Boolean = when (page) {
    SplashPage.HOME -> known.containsAll(listOf("greeting", "hero", "tab"))
    SplashPage.WELCOME -> known.containsAll(listOf("welcome", "fresh"))
    null -> false
}

internal fun union(rects: List<Rect>) = rects.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

/** A landed bar ([whole]) split into [real]: each piece starts as an equal share of the bar and ends on its real place. */
internal fun pieces(whole: Rect, real: List<Rect>, k: Float): List<Rect> {
    val w = whole.width / real.size
    return real.mapIndexed { j, r -> lerp(Rect(whole.left + j * w, whole.top, whole.left + (j + 1) * w, whole.bottom), r, k) }
}

// ---- the logo, as splash_logo.xml draws it ----

/** The bars' centres and heights on the 108 icon grid (ic_launcher_foreground.xml); each is 7 wide. */
private val BAR_X = floatArrayOf(42.49f, 52f, 61.51f, 71.01f, 80.52f)
private val BAR_H = floatArrayOf(47.96f, 36.98f, 26f, 15.02f, 7f)
/** One grid unit on the 240 dp icon. */
private val UNIT = (240f / 108f).dp
private val SplashBg = Color(0xFF0C0D10)

private fun upright(i: Int, c: Offset, u: Float) = Rect(c.x + (BAR_X[i] - 54f - 3.5f) * u, c.y - BAR_H[i] / 2 * u, c.x + (BAR_X[i] - 54f + 3.5f) * u, c.y + BAR_H[i] / 2 * u)
/** The same bar after the quarter turn: lying down, the longest on top. */
private fun lying(i: Int, c: Offset, u: Float) = Rect(c.x - BAR_H[i] / 2 * u, c.y + (BAR_X[i] - 54f - 3.5f) * u, c.x + BAR_H[i] / 2 * u, c.y + (BAR_X[i] - 54f + 3.5f) * u)

private fun DrawScope.logo(t: Float, c: Offset, u: Float, alpha: Float) {
    val r = 36f * u * (1f - discShrink(t))
    if (r > .5f) drawCircle(Brush.verticalGradient(listOf(Ember, EmberDeep), c.y - 1.5f * r, c.y + 1.5f * r), r, c, alpha)
    rotate(turn(t) * 90f, c) { repeat(5) { val b = upright(it, c, u); drawRoundRect(Color.White, b.topLeft, b.size, CornerRadius(3.5f * u), alpha = alpha) } }
}

// ---- the plan, made once the page has had 0.36 s to lay out ----

/** What a bar lands on: [rect], split into [parts], or written as [text] at [textAt] between [clipTop] and [clipBottom]. */
private class Target(
    val rect: Rect, val parts: List<Rect> = emptyList(),
    val text: TextLayoutResult? = null, val textAt: Offset = Offset.Zero,
    val clipTop: Float = Float.NEGATIVE_INFINITY, val clipBottom: Float = Float.POSITIVE_INFINITY,
)

private class Plan(
    val page: SplashPage?, val fills: List<Color>, val corners: List<Float?>, val pageColor: Color,
    val greeting: TextLayoutResult? = null, val heading: TextLayoutResult? = null, val question: TextLayoutResult? = null,
) {
    val flies get() = page != null
    val end get() = if (flies) SplashTime.END else SplashTime.FLY0 + SplashTime.FALLBACK
}

/**
 * Draws the splash over [content] on a cold start ([start] not null). The first touch anywhere ends it and goes on to the app;
 * after that the touch watcher has returned and does nothing. [page]: which page is showing, asked once, 0.36 s in.
 */
@Composable
fun SplashHost(start: SplashStart?, page: () -> SplashPage?, content: @Composable BoxScope.() -> Unit) {
    var done by rememberSaveable { mutableStateOf(false) }
    val on = start != null && !done
    Box(
        // always the same modifier: taking it off mid-touch would cancel that touch for the app
        Modifier.fillMaxSize().pointerInput(Unit) {
            if (start == null || done) return@pointerInput
            // seen before the app sees it, and not consumed: the app still gets the touch
            awaitPointerEventScope { awaitPointerEvent(PointerEventPass.Initial) }
            done = true
        },
    ) {
        content()
        if (on) SplashOverlay(start!!, page) { done = true }
    }
}

@Composable
private fun SplashOverlay(start: SplashStart, page: () -> SplashPage?, onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // on in composition, before the page's first layout, so its first positions are heard
    remember { SplashTargets.start() }
    LaunchedEffect(Unit) {
        try {
            withTimeoutOrNull(SplashTime.CAP_MS) {
                clock.animateTo(SplashTime.FLY0, tween(SplashTime.FLY0.toInt(), easing = LinearEasing))
                val rects = SplashTargets.rects.toMap()
                val at = page().takeIf { fliesTo(it, rects.keys) }
                fun measure(text: androidx.compose.ui.text.AnnotatedString, style: TextStyle, width: Float) =
                    measurer.measure(text, style, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)))
                val grey = scheme.onSurface.copy(alpha = .1f).compositeOver(scheme.background)
                val p = when (at) {
                    SplashPage.HOME -> Plan(
                        at, listOf(scheme.onBackground, grey, grey, grey, scheme.primary.copy(alpha = .18f).compositeOver(scheme.background)),
                        listOf(null, 22f, null, 14f, null), scheme.background,
                        greeting = measure(greetingText(LocalTime.now().hour, scheme.primary), type.displaySmall.copy(color = scheme.onBackground), rects.getValue("greeting").width),
                    )
                    SplashPage.WELCOME -> {
                        val w = rects.getValue("welcome").width - 40 * density
                        Plan(
                            at, listOf(scheme.onBackground, scheme.primary, scheme.onSurfaceVariant, scheme.primary, scheme.primary),
                            listOf(null, null, null, null, null), scheme.background,
                            heading = measure(welcomeHeading(scheme.primary), BigHeading.copy(color = scheme.onBackground), w),
                            question = measure(androidx.compose.ui.text.AnnotatedString(WELCOME_QUESTION), type.bodyLarge.copy(color = scheme.onSurfaceVariant), w),
                        )
                    }
                    null -> Plan(null, emptyList(), emptyList(), scheme.background)
                }
                plan = p
                clock.animateTo(p.end, tween((p.end - SplashTime.FLY0).toInt(), easing = LinearEasing))
            }
        } finally {
            SplashTargets.active = false
        }
        onDone()
    }
    // no semantics and no pointer input: screen readers and touches go straight to the page under it
    Canvas(Modifier.fillMaxSize()) { splash(clock.value, plan, start.icon.value ?: center, SplashTargets.rects) }
}

private fun DrawScope.splash(t: Float, plan: Plan?, c: Offset, rects: Map<String, Rect>) {
    val u = UNIT.toPx()
    if (plan == null || !plan.flies) {
        val k = if (plan == null) 0f else fallback(t)
        drawRect(SplashBg, alpha = 1f - k)
        scale(1f - .15f * k, c) { logo(t, c, u, 1f - k) }
        return
    }
    val page = plan.page!!
    val fade = endFade(t)
    val shown = pageIn(t, page)
    if (page == SplashPage.HOME) drawRect(SplashBg, alpha = (1f - shown) * fade)
    else {
        // the welcome picture shows early, as its own entrance plays; the words and buttons stay covered until they are written
        drawRect(SplashBg, alpha = (1f - Standard.transform(prog(t, 360f, 700f))) * fade)
        rects["welcome"]?.let { drawRect(plan.pageColor, Offset(0f, it.top - 8.dp.toPx()), size.copy(height = size.height - it.top + 8.dp.toPx()), alpha = (1f - shown) * fade) }
    }
    val targets = targets(plan, rects)
    for (i in 0 until 5) {
        val from = lying(i, c, u)
        val to = targets[i]
        if (to == null) {
            // nowhere to land: it fades where it lies
            drawRoundRect(Color.White, from.topLeft, from.size, CornerRadius(3.5f * u), alpha = (1f - flight(i, t)) * fade)
            continue
        }
        val k = flight(i, t)
        val color = lerp(Color.White, plan.fills[i], tint(i, t))
        val end = plan.corners[i]?.dp?.toPx()
        fun radius(r: Rect) = end ?: (minOf(r.width, r.height) / 2)
        val alpha = fade * if (page == SplashPage.WELCOME && i == 3) 1f - shown else 1f
        val s = split(i, t)
        if (to.parts.size > 1 && s > 0f) {
            pieces(to.rect, to.parts, s).forEach { drawRoundRect(color, it.topLeft, it.size, CornerRadius(radius(it)), alpha = alpha) }
            continue
        }
        var r = lerp(from, to.rect, k)
        val cr = 3.5f * u + (radius(to.rect) - 3.5f * u) * k
        to.text?.let { text ->
            val w = write(i, t)
            if (w > 0f) {
                clipRect(0f, to.clipTop.coerceAtLeast(0f), to.rect.left + w * to.rect.width, to.clipBottom.coerceAtMost(size.height)) { drawText(text, topLeft = to.textAt, alpha = fade) }
                r = Rect(r.left + w * to.rect.width, r.top, r.right, r.bottom)
            }
        }
        if (r.width > .5f) drawRoundRect(color, r.topLeft, r.size, CornerRadius(minOf(cr, r.width / 2, r.height / 2)), alpha = alpha)
    }
}

private fun DrawScope.targets(plan: Plan, rects: Map<String, Rect>): List<Target?> {
    fun parts(prefix: String) = (0..2).mapNotNull { rects["$prefix$it"] }.takeIf { it.isNotEmpty() }?.let { Target(union(it), it) }
    /** A bar over line [line] of [text] laid out at [at]: from just above the capitals to just under the baseline. */
    fun line(text: TextLayoutResult, line: Int, at: Offset, top: Float = Float.NEGATIVE_INFINITY, bottom: Float = Float.POSITIVE_INFINITY): Target {
        val base = at.y + text.getLineBaseline(line)
        val fs = text.layoutInput.style.fontSize.toPx()
        val rect = Rect(at.x + text.getLineLeft(line), base - .8f * fs, at.x + text.getLineRight(line) + 2.dp.toPx(), base + .15f * fs)
        return Target(rect, text = text, textAt = at, clipTop = top, clipBottom = bottom)
    }
    return when (plan.page) {
        SplashPage.HOME -> listOf(
            rects["greeting"]?.let { g -> plan.greeting?.let { line(it, 0, g.topLeft) } },
            rects["hero"]?.let { Target(it) },
            parts("shortcut"),
            parts("card"),
            rects["tab"]?.let { Target(it) },
        )
        SplashPage.WELCOME -> {
            val w = rects["welcome"]
            val heading = plan.heading
            val question = plan.question
            val at = w?.let { Offset(it.left + 20.dp.toPx(), it.top) }
            val split = if (at != null && heading != null) at.y + heading.getLineBottom(0) else 0f
            listOf(
                if (at != null && heading != null) line(heading, 0, at, bottom = split) else null,
                if (at != null && heading != null && heading.lineCount > 1) line(heading, 1, at, top = split) else null,
                if (at != null && heading != null && question != null) line(question, 0, Offset(at.x, at.y + heading.size.height + 8.dp.toPx())) else null,
                rects["fresh"]?.let { Target(it) },
                // the record's orange label: 17% of its radius
                rects["record"]?.let { Target(Rect(it.center, it.width / 2 * .17f)) },
            )
        }
        null -> List(5) { null }
    }
}
