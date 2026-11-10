package com.spotifry.music.ui.firstlaunch

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.unit.Dp
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
 * The two first pages, drawn as a cut-out collage (design/onboarding-mockup-3.html, option N): a record, black-and-white prints of
 * the starter artists' photos on paper, an orange circle, a squiggle, a round sticker. Everything is drawn in a 390 dp wide
 * space and scaled to the screen. The entrance runs once; the record and the sticker's text keep turning slowly. With the
 * system's "remove animations" on, the collage is simply there and still.
 */

private val Paper = Color(0xFFE9E4D4)
private val PaperInk = Color(0xFF17191E)
private val Vinyl = Color(0xFF0E0F12)
private const val ART_W = 390f

/** Welcome: restore a backup, or start fresh. [starters] only lend their photos to the collage. */
@Composable
internal fun WelcomeStep(starters: List<Starter>, onFresh: () -> Unit, onRestore: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val intro = rememberEntrance()
    Column(Modifier.fillMaxSize()) {
        TopRow(null, "", false) {}
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
internal fun NameStep(starters: List<Starter>, name: String, onName: (String) -> Unit, stepLabel: String, onContinue: () -> Unit, onSkip: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val intro = rememberEntrance()
    val ready = cleanName(name).isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        TopRow(stepLabel, "Skip", true, onSkip)
        Text(
            "What should\nwe call you?", Modifier.padding(horizontal = 20.dp).semantics { heading() }.graphicsLayer { translationY = (1 - ease(intro.seg(.1f, .6f))) * 42.dp.toPx(); alpha = ease(intro.seg(.1f, .5f)) },
            style = MediumHeading,
        )
        // the keyboard is up: the picture gives its room to the field and the button, which stay in view
        if (WindowInsets.isImeVisible) Spacer(Modifier.weight(1f))
        else Collage(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp), 300f) { NamePicture(starters, intro) }
        Foot {
            NameField(name, onName, onContinue = { if (ready) onContinue() })
            Pill(
                null, "Continue", if (ready) scheme.primary else scheme.surfaceContainerHigh, if (ready) scheme.onPrimary else scheme.onSurfaceVariant,
                { if (ready) onContinue() }, Modifier.fillMaxWidth().padding(top = 10.dp).then(if (ready) Modifier else Modifier.semantics { disabled() }),
            )
        }
    }
}

private val BigHeading = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 58.sp, lineHeight = 55.sp, letterSpacing = (-2.6).sp)
private val MediumHeading = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.6).sp)

@Composable
private fun NameField(name: String, onName: (String) -> Unit, onContinue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val count = remember(name) { cutName(name).let { java.text.BreakIterator.getCharacterInstance().run { setText(it); var n = 0; while (next() != java.text.BreakIterator.DONE) n++; n } } }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        name, { onName(cutName(it.replace('\n', ' '))) },
        Modifier.fillMaxWidth().height(56.dp).clip(shape).background(scheme.surface).then(if (focused) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Name or nickname" }.testTag("name field"),
        singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface), cursorBrush = Brush.linearGradient(listOf(scheme.primary, scheme.primary)),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onContinue() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), Alignment.CenterStart) {
                    if (name.isEmpty()) Text("Name or nickname", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    inner()
                }
                // the count only shows when the end is near
                if (count >= NAME_MAX - 5) Text("$count/$NAME_MAX", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = if (count >= NAME_MAX) scheme.onSurface else scheme.onSurfaceVariant)
            }
        },
    )
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

@Composable
private fun NamePicture(starters: List<Starter>, intro: Entrance) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.offset(196.dp, 92.dp).size(120.dp).graphicsLayer { val p = ease(intro.seg(0f, .45f)); scaleX = .6f + .4f * p; scaleY = scaleX; alpha = p }.background(scheme.primary, CircleShape))
    Record(-90, -40, 270, intro)
    Print(starters.photoAt(2), starters.nameAt(2), 176, 10, 170, 4f, intro, .15f)
    Note(22, 128, intro)
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
    val paint = remember(ink) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 8.6f }
    }
    val ring = remember { android.graphics.Path().apply { addCircle(48f, 48f, 34f, android.graphics.Path.Direction.CW) } }
    val text = "just between us · just between us ·"
    Canvas(Modifier.offset(x.dp, y.dp).size(98.dp).graphicsLayer {
        val p = spring(intro.seg(.4f, 1f))
        rotationZ = -10f - (1 - p) * 30f; scaleX = .4f + .6f * p; scaleY = scaleX; alpha = intro.seg(.4f, .6f)
    }) {
        val k = size.width / 96f
        drawCircle(fill)
        val w = paint.measureText(text)
        paint.letterSpacing = (2f * PI.toFloat() * 34f - w) / text.length / paint.textSize
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save(); n.scale(k, k); n.rotate(turn, 48f, 48f)
            n.drawTextOnPath(text, ring, 0f, 3f, paint)
            n.restore()
        }
        val a = Path().apply { moveTo(38f * k, 58f * k); lineTo(58f * k, 38f * k); moveTo(44f * k, 38f * k); lineTo(58f * k, 38f * k); lineTo(58f * k, 52f * k) }
        drawPath(a, ink, style = Stroke(5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A taped paper note: the same words as the sticker, on the page that asks for the name. */
@Composable
private fun Note(x: Int, y: Int, intro: Entrance) {
    Box(Modifier.offset(x.dp, y.dp).graphicsLayer {
        val p = spring(intro.seg(.3f, .8f))
        translationY = -(1 - p) * 36.dp.toPx(); rotationZ = 4f + (1 - p) * 16f; alpha = intro.seg(.3f, .5f)
    }.shadow(8.dp, RoundedCornerShape(2.dp)).background(Paper).padding(horizontal = 15.dp, vertical = 12.dp)) {
        Text("Just\nbetween us.", style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, lineHeight = 21.sp, letterSpacing = (-0.4).sp, color = PaperInk))
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-21).dp).size(46.dp, 16.dp).background(Color.White.copy(alpha = .28f)))
    }
}
