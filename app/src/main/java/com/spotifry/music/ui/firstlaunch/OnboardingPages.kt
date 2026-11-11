package com.spotifry.music.ui.firstlaunch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.spotifry.music.R
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.NAME_MAX
import com.spotifry.music.data.cleanName
import com.spotifry.music.data.cutName
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.Pill
import com.spotifry.music.ui.components.rememberStill
import com.spotifry.music.ui.theme.Albert
import kotlin.math.PI
import kotlin.math.pow

/*
 * Welcome is drawn as a cut-out collage (design/onboarding-mockup-3.html, option N): a record, black-and-white prints of the
 * starter artists' photos on paper, an orange circle, a squiggle, a round sticker. Everything is drawn in a 390 dp wide
 * space and scaled to the screen. The entrance runs once; the record and the sticker's text keep turning slowly.
 * The Name page is a one-ink record sleeve (design/name-page-mockup-4b.html): a black-and-white photo that takes the accent
 * colour as the name is typed, with the name set as its title. With the system's "remove animations" on, nothing moves.
 */

private val Paper = Color(0xFFE9E4D4)
private val Vinyl = Color(0xFF0E0F12)
private const val ART_W = 390f

/** Welcome: restore a backup, or start fresh. [starters] only lend their photos to the collage. */
@Composable
internal fun WelcomeStep(starters: List<Starter>, onFresh: () -> Unit, onRestore: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val intro = rememberEntrance()
    Column(Modifier.fillMaxSize()) {
        TopRow(null, "", false, {})
        Collage(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp), 440f) { Welcome(starters, intro) }
        Column(Modifier.graphicsLayer { translationY = (1 - ease(intro.seg(.1f, .6f))) * 42.dp.toPx(); alpha = ease(intro.seg(.1f, .5f)) }) {
            Text(
                buildAnnotatedString { append("Welcome to\n"); withStyle(SpanStyle(color = scheme.primary)) { append("Spotifry.") } },
                Modifier.padding(horizontal = 20.dp).semantics { heading() }, style = BigHeading,
            )
            Text("Do you have a backup to bring back?", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        }
        Foot {
            Pill(null, "Start fresh", scheme.primary, scheme.onPrimary, onFresh, Modifier.fillMaxWidth())
            if (onRestore != null) Pill(null, "Restore a backup", scheme.surfaceContainerHigh, scheme.onSurface, onRestore, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** Name: a name or nickname, kept on the phone and shown once, on the Spotifried listener card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NameStep(name: String, onName: (String) -> Unit, stepLabel: String, onContinue: () -> Unit, onSkip: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ready = cleanName(name).isNotEmpty()
    val keyboard = WindowInsets.isImeVisible
    // the photo takes what the question, field and button leave; at a large font scale it stops at a strip and the page scrolls.
    // Worked out in one measuring pass from the room on screen, so a size seen a frame late never moves the field again.
    Layout(
        {
            Sleeve(name, keyboard, Modifier.fillMaxWidth()) { TopRow(stepLabel, "Skip", true, onSkip, ink = Color.White) }
            Column {
                Text(
                    "What should we call you?", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).semantics { heading() },
                    style = if (keyboard) Question.copy(fontSize = 20.sp) else Question,
                )
                Foot {
                    NameField(name, onName, onContinue = { if (ready) onContinue() })
                    Pill(
                        null, "Continue", if (ready) scheme.primary else scheme.surfaceContainerHigh, if (ready) scheme.onPrimary else scheme.onSurfaceVariant,
                        { if (ready) onContinue() }, Modifier.fillMaxWidth().padding(top = 12.dp).then(if (ready) Modifier else Modifier.semantics { disabled() }),
                    )
                }
            }
        },
        // the scroll keeps the screen's height as the least the page may take
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) { (sleeve, body), c ->
        val b = body.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val photoH = maxOf(140.dp.roundToPx(), c.minHeight - b.height)
        val s = sleeve.measure(Constraints.fixed(c.maxWidth, photoH))
        layout(c.maxWidth, photoH + b.height) { s.place(0, 0); b.place(0, photoH) }
    }
}

private val BigHeading = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 58.sp, lineHeight = 55.sp, letterSpacing = (-2.6).sp)
private val Question = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 1.02.em, letterSpacing = (-0.025).em, lineBreak = LineBreak.Heading)

@Composable
private fun NameField(name: String, onName: (String) -> Unit, onContinue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // no focus and no keyboard until the field is tapped: the page opens whole, photo and question first
    val count = remember(name) { clusters(cutName(name)).size }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        name, { onName(cutName(it.replace('\n', ' '))) },
        Modifier.fillMaxWidth().height(60.dp).clip(shape).background(scheme.surface).border(if (focused) 2.dp else 1.dp, if (focused) scheme.primary else scheme.outlineVariant, shape)
            .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Name or nickname" }.testTag("name field"),
        singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface), cursorBrush = Brush.linearGradient(listOf(scheme.primary, scheme.primary)),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onContinue() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // empty and not in use: the words sit in the box; otherwise they move up as its label
                    if (name.isEmpty() && !focused) Text("Name or nickname", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    else Text("Name or nickname", Modifier.align(Alignment.TopStart).padding(top = 10.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (focused) scheme.primary else scheme.onSurfaceVariant)
                    Box(Modifier.align(Alignment.CenterStart).padding(top = 16.dp)) { inner() }
                }
                // the count only shows when the end is near
                if (count >= NAME_MAX - 5) Text("$count/$NAME_MAX", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = if (count >= NAME_MAX) scheme.onSurface else scheme.onSurfaceVariant)
            }
        },
    )
}

// ---- the sleeve ----

/** The letters of [s] as the reader sees them (an accent typed as its own mark stays with its letter). */
private fun clusters(s: String): List<IntRange> = java.text.BreakIterator.getCharacterInstance().run {
    setText(s)
    var from = first()
    buildList { while (true) { val to = next(); if (to == java.text.BreakIterator.DONE) break; add(from until to); from = to } }
}

/** How much accent ink the photo has taken: none for an empty name, full from the third letter. */
internal fun inkFor(name: String): Float = (clusters(cleanName(name).replace(" ", "")).size / 3f).coerceAtMost(1f)

/** The name on the photo: two lines with the keyboard closed, one while it is open. */
internal fun nameLines(keyboard: Boolean) = if (keyboard) 1 else 2

private val SleeveEase = CubicBezierEasing(.16f, 1f, .3f, 1f)

@OptIn(ExperimentalTextApi::class)
private val Bodoni = FontFamily(Font(R.font.bodonimoda, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900), FontVariation.Setting("opsz", 28f))))
internal val NameStyle = TextStyle(color = Color.White, fontFamily = Bodoni, fontWeight = FontWeight.Black, lineHeight = 0.9.em, letterSpacing = (-0.02).em, lineBreak = LineBreak.Heading)

/**
 * The grey print with the accent multiplied over it: luminance, then contrast and brightness as the ink comes up ([p] 0 to 1),
 * then each channel scaled from 1 (plain grey) towards [ink]'s, so grey runs from black to the accent.
 */
internal fun sleeveMatrix(p: Float, ink: Color): ColorMatrix {
    val c = 1.05f + .2f * p
    val b = .8f + .2f * p
    val rows = listOf(ink.red, ink.green, ink.blue).flatMap { channel ->
        val k = (1 - p + p * channel) * c * b
        listOf(.2126f * k, .7152f * k, .0722f * k, 0f, 127.5f * (1 - c) * b * (1 - p + p * channel))
    }
    return ColorMatrix((rows + listOf(0f, 0f, 0f, 1f, 0f)).toFloatArray())
}

/** The photo, tinted and pushed in as the name is typed; with the keyboard open a wide strip with its own crop. [top] sits on it. */
@Composable
private fun Sleeve(name: String, keyboard: Boolean, modifier: Modifier, top: @Composable () -> Unit) {
    val still = rememberStill()
    val named = cleanName(name).isNotEmpty()
    val p by animateFloatAsState(inkFor(name), if (still) snap() else tween(900, easing = SleeveEase), label = "ink")
    val k by animateFloatAsState(if (keyboard) 1f else 0f, if (still) snap() else tween(600, easing = SleeveEase), label = "strip")
    val shade = animateFloatAsState(if (named) 1f else 0f, if (still) snap() else tween(600), label = "shade")
    val ink = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(Color.Black)) {
        Image(
            painterResource(R.drawable.name_photo), null,
            Modifier.fillMaxSize().graphicsLayer { val s = 1f + .16f * k + (.1f - .04f * k) * p; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(.48f, .26f) },
            // the raised hands: a little above the middle, higher in the strip
            alignment = BiasAlignment(0f, -.4f - .16f * k), contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(sleeveMatrix(p, ink)),
        )
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.62f).graphicsLayer { alpha = shade.value }.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))))
        top()
        val density = LocalDensity.current
        val maxW = with(density) { (maxWidth - 36.dp).roundToPx() }
        val maxH = with(density) { if (keyboard) (maxHeight * .5f).roundToPx() else (maxHeight - 96.dp).roundToPx() }
        Mast(cleanName(name), nameLines(keyboard), maxW, maxH, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
    }
}

/**
 * The largest size, from 12 to [lines] == 1 ? 88 : 200 dp, at which [text] fits [maxW] x [maxH] in [lines] lines, breaking only
 * between words: every word must fit the width on its own, else a line would break inside it.
 */
internal fun fitName(measurer: TextMeasurer, text: String, density: Density, maxW: Int, maxH: Int, lines: Int): TextLayoutResult {
    val w = maxW.coerceAtLeast(1)
    val words = text.split(' ').filter { it.isNotEmpty() }
    fun style(px: Int) = NameStyle.copy(fontSize = with(density) { px.toSp() })
    fun layout(px: Int) = measurer.measure(text, style(px), constraints = Constraints(maxWidth = w))
    fun fits(px: Int): Boolean {
        if (words.any { measurer.measure(it, style(px), softWrap = false, maxLines = 1).size.width > w }) return false
        val l = layout(px)
        return l.lineCount <= lines && l.size.height <= maxH
    }
    var lo = with(density) { 12.dp.roundToPx() }
    var hi = with(density) { (if (lines == 1) 88 else 200).dp.roundToPx() }
    while (hi - lo > 1) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
    return layout(lo)
}

/** One letter of the name and how far it has risen in (0 to 1). */
private class Rise(val range: IntRange, val t: Animatable<Float, AnimationVector1D>)

/** The typed name, set as large as the photo allows. Drawn, not read: the field already says it to TalkBack. */
@Composable
private fun Mast(text: String, lines: Int, maxW: Int, maxH: Int, modifier: Modifier) {
    if (text.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(text, lines, maxW, maxH, density) { fitName(measurer, text, density, maxW, maxH, lines) }
    val still = rememberStill()
    // letters kept from the last name stay put; new ones start low and clear
    val last = remember { arrayOf("" to emptyList<Rise>()) }
    val rises = remember(text) {
        val (before, old) = last[0]
        val keep = before.commonPrefixWith(text).length
        clusters(text).map { r -> old.firstOrNull { it.range == r && r.last < keep } ?: Rise(r, Animatable(if (still) 1f else 0f)) }.also { last[0] = text to it }
    }
    LaunchedEffect(rises) { rises.forEach { if (it.t.value < 1f) launch { it.t.animateTo(1f, tween(500, easing = SleeveEase)) } } }
    with(density) {
        Spacer(modifier.size(layout.size.width.toDp(), layout.size.height.toDp()).testTag("sleeve name").drawBehind {
            // the whole name is laid out once, so each letter keeps the font's pairing with its neighbours; a rising letter is
            // that same layout clipped to the letter's box, moved down and faded
            val rising = rises.filter { it.t.value < 1f }
            val font = layout.layoutInput.style.fontSize.toPx()
            fun box(r: Rise): Rect {
                val a = layout.getBoundingBox(r.range.first)
                val z = layout.getBoundingBox(r.range.last)
                val line = layout.getLineForOffset(r.range.first)
                return Rect(minOf(a.left, z.left), layout.getLineTop(line) - .25f * font, maxOf(a.right, z.right), layout.getLineBottom(line) + .1f * font)
            }
            val holes = Path().apply { rising.forEach { addRect(box(it)) } }
            clipPath(holes, ClipOp.Difference) { drawText(layout) }
            rising.forEach { r ->
                val v = r.t.value
                withTransform({ translate(top = (1 - v) * .32f * font); clipRect(box(r).left, box(r).top, box(r).right, box(r).bottom) }) { drawText(layout, alpha = v) }
            }
        })
    }
}

// ---- the collage ----

/** Time of the entrance, 0 to about 1.6 s as a fraction; [seg] cuts it into a piece for one element. */
private class Entrance(private val t: Animatable<Float, *>) {
    val value get() = t.value
    fun seg(from: Float, to: Float) = ((t.value - from) / (to - from)).coerceIn(0f, 1f)
}

@Composable
private fun rememberEntrance(): Entrance {
    val still = rememberStill()
    val t = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) { if (!still) t.animateTo(1f, tween(1700, easing = LinearEasing)) }
    return remember(t) { Entrance(t) }
}

private fun ease(x: Float) = 1f - (1f - x).pow(3)
private fun spring(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }

/** A [height]-tall piece of the 390-wide drawing, scaled to the width it is given; what doesn't fit the room below is cut off. */
@Composable
private fun Collage(modifier: Modifier, height: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
        val k = maxWidth.value / ART_W
        Box(Modifier.requiredSize(ART_W.dp, height.dp).graphicsLayer { scaleX = k; scaleY = k; transformOrigin = TransformOrigin(0f, 0f) }) { content() }
    }
}

@Composable
private fun Welcome(starters: List<Starter>, intro: Entrance) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.offset(60.dp, 170.dp).size(190.dp).graphicsLayer { val p = ease(intro.seg(0f, .45f)); scaleX = .6f + .4f * p; scaleY = scaleX; alpha = p }.background(scheme.primary, CircleShape))
    Record(-110, -10, 340, intro)
    Squiggle(-6, 150, intro)
    Print(starters.photoAt(0), starters.nameAt(0), 112, 46, 236, -3f, intro, .15f)
    Print(starters.photoAt(4), starters.nameAt(4), 262, 250, 112, 7f, intro, .3f)
    Sticker(196, 196, intro)
}

private fun List<Starter>.photoAt(i: Int) = getOrNull(i)?.photo
private fun List<Starter>.nameAt(i: Int) = getOrNull(i)?.name ?: "artist $i"

/** The record: rings, a slow sheen and an orange label; turns once every 16 seconds. */
@Composable
private fun Record(x: Int, y: Int, diameter: Int, intro: Entrance) {
    val still = rememberStill()
    val turn = if (still) 0f else rememberInfiniteTransition(label = "record").animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    val label = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    Canvas(Modifier.offset(x.dp, y.dp).size(diameter.dp).graphicsLayer { val p = ease(intro.seg(.06f, .5f)); scaleX = .6f + .4f * p; scaleY = scaleX; alpha = p; rotationZ = turn }) {
        val c = center; val r = size.minDimension / 2
        drawCircle(Vinyl, r, c)
        var rr = r - 6.dp.toPx()
        while (rr > r * .36f) { drawCircle(Color(0xFF23262C), rr, c, style = Stroke(1.2.dp.toPx())); rr -= 6.dp.toPx() }
        drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .08f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = .05f), Color.Transparent), c), r, c)
        drawCircle(label, r * .17f, c)
        drawCircle(bg, r * .026f, c)
    }
}

/** The zigzag: it draws itself from the left, once. */
@Composable
private fun Squiggle(x: Int, y: Int, intro: Entrance) {
    val color = MaterialTheme.colorScheme.primary
    val pts = remember { listOf(6f to 84f, 20f to 70f, 32f to 104f, 50f to 20f, 66f to 112f, 84f to 38f, 98f to 94f, 116f to 52f, 132f to 78f, 146f to 60f) }
    Canvas(Modifier.offset(x.dp, y.dp).size(210.dp, 182.dp)) {
        val k = size.width / 150f
        val path = Path().apply { pts.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(px * k, py * k) else lineTo(px * k, py * k) } }
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * ease(intro.seg(.45f, 1f)), part, true)
        drawPath(part, color, style = Stroke(5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A black-and-white print on paper, dropped in at a slight angle. Without a photo yet (or offline) it is the plain placeholder, also in grey. */
@Composable
private fun Print(photo: String?, seed: String, x: Int, y: Int, size: Int, angle: Float, intro: Entrance, at: Float) {
    Box(
        Modifier.offset(x.dp, y.dp).size(size.dp).graphicsLayer {
            val p = ease(intro.seg(at, at + .5f))
            translationY = (1 - p) * 34.dp.toPx(); rotationZ = angle + (1 - p) * 12f; alpha = p; scaleX = .92f + .08f * p; scaleY = scaleX
            shadowElevation = 0f
        }.shadow(10.dp, RoundedCornerShape(1.dp)).background(Paper).padding(6.dp),
    ) {
        CoverArt(seed, photo, null, Modifier.fillMaxSize().grey(), large = true)
    }
}

private val greyMatrix = ColorMatrix(floatArrayOf(
    .2126f * 1.2f, .7152f * 1.2f, .0722f * 1.2f, 0f, -25f,
    .2126f * 1.2f, .7152f * 1.2f, .0722f * 1.2f, 0f, -25f,
    .2126f * 1.2f, .7152f * 1.2f, .0722f * 1.2f, 0f, -25f,
    0f, 0f, 0f, 1f, 0f,
))

private fun Modifier.grey() = drawWithContent {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(greyMatrix) }
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

/** The round sticker: "just between us" turning round an arrow. */
@Composable
private fun Sticker(x: Int, y: Int, intro: Entrance) {
    val still = rememberStill()
    val turn = if (still) 0f else rememberInfiniteTransition(label = "sticker").animateFloat(0f, 360f, infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart), label = "ring").value
    val fill = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
    val text = "just between us · just between us ·"
    // Each letter is a filled outline placed round the ring once; turning and scaling then stay sharp (letters drawn as text blurred while moving).
    val letters = remember {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 8.6f }
        val adv = text.map { paint.measureText(it.toString()) }
        val gap = (2f * PI.toFloat() * 31f - adv.sum()) / text.length
        var d = 0f
        text.indices.map { i ->
            val mid = d + adv[i] / 2f
            d += adv[i] + gap
            val theta = mid / 31f
            val glyph = android.graphics.Path().also { paint.getTextPath(text[i].toString(), 0, 1, 0f, 0f, it) }
            glyph.transform(android.graphics.Matrix().apply {
                postTranslate(-adv[i] / 2f, 0f)
                postRotate(Math.toDegrees(theta.toDouble()).toFloat() + 90f)
                postTranslate(48f + 31f * kotlin.math.cos(theta), 48f + 31f * kotlin.math.sin(theta))
            })
            glyph.asComposePath()
        }
    }
    Canvas(Modifier.offset(x.dp, y.dp).size(98.dp).graphicsLayer {
        val p = spring(intro.seg(.4f, 1f))
        rotationZ = -10f - (1 - p) * 30f; scaleX = .4f + .6f * p; scaleY = scaleX; alpha = intro.seg(.4f, .6f)
    }) {
        val k = size.width / 96f
        drawCircle(fill)
        withTransform({ scale(k, k, Offset.Zero); rotate(turn, Offset(48f, 48f)) }) { letters.forEach { drawPath(it, ink) } }
        val a = Path().apply { moveTo(38f * k, 58f * k); lineTo(58f * k, 38f * k); moveTo(44f * k, 38f * k); lineTo(58f * k, 38f * k); lineTo(58f * k, 52f * k) }
        drawPath(a, ink, style = Stroke(5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
