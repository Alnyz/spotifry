// Layout, graph and sweep-to-draw bands adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt

package com.spotifry.music.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.Settings
import com.spotifry.music.player.EQ_BANDS
import com.spotifry.music.player.EQ_CENTERS_HZ
import com.spotifry.music.player.EQ_MAX_MB
import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.EqDesign
import com.spotifry.music.player.Spectrum
import com.spotifry.music.player.SpectrumTap
import com.spotifry.music.ui.components.ScreenHeader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** A new way to shape the sound gets its tab here. */
private val TABS = listOf("Graph", "Bands")
private const val STEP_MB = 50
private const val MIN_HZ = 20.0
private const val MAX_HZ = 20_000.0
private const val CURVE_POINTS = 160
private val CURVE_HZ = DoubleArray(CURVE_POINTS) { MIN_HZ * (MAX_HZ / MIN_HZ).pow(it.toDouble() / (CURVE_POINTS - 1)) }
private val GRID_HZ = listOf(20 to "20", 50 to "50", 100 to "100", 200 to "200", 500 to "500", 1000 to "1k", 2000 to "2k", 5000 to "5k", 10_000 to "10k", 20_000 to "20k")
private val GRID_MB = listOf(1200 to "+12", 600 to "+6", 0 to "0", -600 to "−6", -1200 to "−12")

/** Where frequencies and levels sit inside a drawing. Top is +12 dB. */
internal class EqPlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun x(hz: Double) = left + (log10(hz / MIN_HZ) / log10(MAX_HZ / MIN_HZ)).toFloat() * (right - left)
    fun y(mb: Float) = top + (EQ_MAX_MB - mb) / (2f * EQ_MAX_MB) * (bottom - top)
    /** The level at [y], held to the range, in half-dB steps. */
    fun mbAt(y: Float): Int {
        val mb = EQ_MAX_MB - (y - top) / (bottom - top) * 2 * EQ_MAX_MB
        return ((mb / STEP_MB).roundToInt() * STEP_MB).coerceIn(-EQ_MAX_MB, EQ_MAX_MB)
    }
}

/** The band whose column holds [x] across a drawing [width] wide. */
internal fun bandAt(x: Float, width: Float) = (x / width * EQ_BANDS).toInt().coerceIn(0, EQ_BANDS - 1)

/**
 * A finger moved from one band's column to another. A quick sweep can jump over columns, so those get levels along the
 * line between the two; the rest of the page sees every band on the way.
 */
internal fun sweep(fromBand: Int, fromMb: Int, toBand: Int, toMb: Int): List<Pair<Int, Int>> {
    if (fromBand == toBand) return listOf(toBand to toMb)
    val dir = if (toBand > fromBand) 1 else -1
    return generateSequence(fromBand + dir) { it + dir }.takeWhile { it != toBand + dir }.map { b ->
        val t = (b - fromBand).toFloat() / (toBand - fromBand)
        b to ((fromMb + (toMb - fromMb) * t) / STEP_MB).roundToInt() * STEP_MB
    }.toList()
}

@Composable
fun EqualizerScreen(settings: Settings, tap: SpectrumTap, isPlaying: Boolean, onBack: () -> Unit, bottomInset: Dp) {
    val on by settings.eqOn.collectAsStateWithLifecycle()
    val preset by settings.eqPreset.collectAsStateWithLifecycle()
    val levels by settings.eqLevels.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme
    // any shaping means the user wants to hear it, so it also turns the equalizer on
    val shape = { changes: List<Pair<Int, Int>> ->
        val next = settings.eqLevels.value.toMutableList()
        changes.forEach { (band, mb) -> next[band] = mb }
        settings.setEq(null, next); settings.setEqOn(true)
    }
    val dim by animateFloatAsState(if (on) 1f else .55f, label = "dim")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset + 24.dp)) {
        ScreenHeader("Equalizer", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
            Card {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch, onClickLabel = "Equalizer") { settings.setEqOn(!on) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) scheme.primary else scheme.outline))
                    Column(Modifier.weight(1f)) {
                        Text("Equalizer", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                        Text(if (on) "On · ${preset ?: "Custom"}" else "Off", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(on, settings::setEqOn, Modifier.semantics { contentDescription = "Equalizer" }, colors = switchColors())
                }
            }
            Segments(TABS, tab, Modifier.padding(top = 14.dp).fillMaxWidth(), fill = true) { tab = it }
            Column(Modifier.padding(top = 18.dp).alpha(dim)) {
                Label("Presets") {
                    Text(
                        "Reset", Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { settings.setEq("Flat", List(EQ_BANDS) { 0 }) }.padding(horizontal = 10.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurface,
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                    if (EQ_PRESETS.none { it.first == preset }) Chip("Custom", true) { }
                    EQ_PRESETS.forEach { (name, l) -> Chip(name, preset == name) { settings.setEq(name, l); settings.setEqOn(true) } }
                }
                Spacer(Modifier.height(18.dp))
                Crossfade(tab, label = "tab") { page ->
                    Column {
                        when (page) {
                            0 -> GraphTab(tap, isPlaying, levels, shape)
                            else -> BandsTab(levels, shape)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surface), content = content)

@Composable
private fun Label(text: String, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 40.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier.background(scheme.surfaceContainerHigh))
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick).padding(horizontal = 16.dp),
        Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1) }
}

/** The dark box a drawing sits in. */
private val stageShape = RoundedCornerShape(18.dp)

@Composable
private fun Modifier.stage(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return clip(stageShape).background(scheme.background).border(BorderStroke(1.dp, scheme.outline), stageShape)
}

/** What a screen reader gets for one band: a slider, in its own column over the drawing. */
@Composable
private fun BandSemantics(levels: List<Int>, modifier: Modifier, set: (List<Pair<Int, Int>>) -> Unit) {
    Row(modifier) {
        for (band in 0 until EQ_BANDS) {
            val mb = levels[band]
            Box(
                Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = hzText(EQ_CENTERS_HZ[band].toInt()); stateDescription = "${dbText(mb)} dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(mb.toFloat(), -EQ_MAX_MB.toFloat()..EQ_MAX_MB.toFloat())
                    setProgress { v -> set(listOf(band to ((v / STEP_MB).roundToInt() * STEP_MB).coerceIn(-EQ_MAX_MB, EQ_MAX_MB))); true }
                },
            )
        }
    }
}

/**
 * The real curve (every band's own hill, and the sum you hear) over the music as it plays. One point per band: a press
 * near a point grabs it and it follows the finger up and down; a press anywhere else scrolls the page.
 */
@Composable
private fun GraphTab(tap: SpectrumTap, isPlaying: Boolean, levels: List<Int>, set: (List<Pair<Int, Int>>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val current by rememberUpdatedState(levels)
    val change by rememberUpdatedState(set)
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var dragging by remember { mutableIntStateOf(-1) }
    val pulse by animateFloatAsState(if (dragging >= 0) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "pulse")
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val picture = rememberLivePicture(tap, isPlaying)
    val design = remember(levels) { EqDesign(levels) }
    val total = remember(design) { FloatArray(CURVE_POINTS) { design.responseDb(CURVE_HZ[it]).toFloat() * 100 } }
    val hills = remember(design) { List(EQ_BANDS) { b -> FloatArray(CURVE_POINTS) { design.bandDb(b, CURVE_HZ[it]).toFloat() * 100 } } }
    // music gets quieter toward the high notes; tilting the picture up 3 dB per octave (level at 1 kHz) keeps the
    // highs moving instead of lying on the floor, and the bass off the ceiling. Like most music analyzers.
    val tilt = remember { FloatArray(Spectrum.BANDS) { 3f * log2(Spectrum.bandCenterHz(it) / 1000f) } }
    val labels = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)

    Card {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp), Arrangement.SpaceBetween) {
            Text(hzText(EQ_CENTERS_HZ[selected].toInt()), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text("${dbText(levels[selected])} dB", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
        Box(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).fillMaxWidth().height(300.dp).stage()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val plot = plotIn(size.width.toFloat(), size.height.toFloat(), 30.dp.toPx(), 10.dp.toPx(), 24.dp.toPx())
                        val hit = (0 until EQ_BANDS).minBy { hypot(down.position.x - plot.x(EQ_CENTERS_HZ[it]), down.position.y - plot.y(current[it].toFloat())) }
                        val pointY = plot.y(current[hit].toFloat())
                        if (hypot(down.position.x - plot.x(EQ_CENTERS_HZ[hit]), down.position.y - pointY) > 30.dp.toPx()) return@awaitEachGesture
                        down.consume()
                        selected = hit; dragging = hit
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // keeps the finger's distance from the point, so grabbing it doesn't make it jump
                        val offset = pointY - down.position.y
                        try {
                            drag(down.id) { move ->
                                move.consume()
                                val to = plot.mbAt(move.position.y + offset)
                                if (to != current[hit]) {
                                    if (to == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    change(listOf(hit to to))
                                }
                            }
                        } finally { dragging = -1 }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val plot = plotIn(size.width, size.height, 30.dp.toPx(), 10.dp.toPx(), 24.dp.toPx())
                val faint = scheme.onSurface.copy(alpha = .07f)
                GRID_MB.forEach { (mb, text) ->
                    val y = plot.y(mb.toFloat())
                    drawLine(if (mb == 0) scheme.onSurface.copy(alpha = .22f) else faint, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
                    val t = measurer.measure(text, labels)
                    drawText(t, topLeft = Offset(plot.left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
                }
                GRID_HZ.forEach { (hz, text) ->
                    val x = plot.x(hz.toDouble())
                    drawLine(faint, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
                    val t = measurer.measure(text, labels)
                    drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(plot.left - 4.dp.toPx(), plot.right - t.size.width), plot.bottom + 6.dp.toPx()))
                }

                // the music, faintly, so you can see what you're shaping: -75 dB on the floor, 0 dB near the top
                picture.frame.let { }
                val px = { i: Int -> plot.x(Spectrum.bandCenterHz(i).toDouble()) }
                val py = { i: Int -> plot.bottom - ((picture.shown[i] + tilt[i] + 75f) / 75f).coerceIn(0f, 1f) * (plot.bottom - plot.top) * .7f }
                val line = Path().apply {
                    moveTo(px(0), py(0))
                    for (i in 1 until Spectrum.BANDS - 1) quadraticTo(px(i), py(i), (px(i) + px(i + 1)) / 2, (py(i) + py(i + 1)) / 2)
                    lineTo(px(Spectrum.BANDS - 1), py(Spectrum.BANDS - 1))
                }
                val under = Path().apply { addPath(line); lineTo(px(Spectrum.BANDS - 1), plot.bottom); lineTo(px(0), plot.bottom); close() }
                drawPath(under, Brush.verticalGradient(listOf(scheme.onSurface.copy(alpha = .16f), scheme.onSurface.copy(alpha = .02f)), plot.top, plot.bottom))
                drawPath(line, scheme.onSurface.copy(alpha = .18f), style = Stroke(1.dp.toPx()))

                clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                    val zero = plot.y(0f)
                    fun curve(of: FloatArray) = Path().apply {
                        for (i in 0 until CURVE_POINTS) plot.x(CURVE_HZ[i]).let { x -> plot.y(of[i]).let { y -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
                    }
                    hills.forEachIndexed { b, hill ->
                        if (levels[b] == 0 && b != selected) return@forEachIndexed
                        val strength = if (b == selected) 1f else .5f
                        val path = curve(hill)
                        drawPath(Path().apply { addPath(path); lineTo(plot.right, zero); lineTo(plot.left, zero); close() }, scheme.primary.copy(alpha = .18f * strength))
                        drawPath(path, scheme.primary.copy(alpha = .75f * strength), style = Stroke(1.2.dp.toPx()))
                    }
                    drawPath(curve(total), scheme.onSurface, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                for (b in 0 until EQ_BANDS) {
                    val at = Offset(plot.x(EQ_CENTERS_HZ[b]), plot.y(levels[b].toFloat()))
                    val r = (if (b == selected) 8.5.dp.toPx() + 3.dp.toPx() * pulse else 6.5.dp.toPx())
                    if (b == selected) drawCircle(scheme.primary.copy(alpha = .18f + .1f * pulse), r * 2.1f, at)
                    drawCircle(scheme.background, r + 2.dp.toPx(), at)
                    drawCircle(scheme.primary, r, at)
                    if (b == selected) drawCircle(scheme.onSurface, r + 2.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
                }
            }
            BandSemantics(levels, Modifier.matchParentSize(), set)
        }
        Text(
            "Drag a point up for more of that sound, down for less.",
            Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}

private fun plotIn(width: Float, height: Float, left: Float, inset: Float, below: Float) = EqPlot(left, inset, width - inset, height - below)

/** The same ten bands as sliders. One sweep of a finger across them draws the curve. */
@Composable
private fun BandsTab(levels: List<Int>, set: (List<Pair<Int, Int>>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val current by rememberUpdatedState(levels)
    val change by rememberUpdatedState(set)
    var active by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val small = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)

    Card {
        Box(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().height(280.dp).stage()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat()
                        val plot = EqPlot(0f, 30.dp.toPx(), w, size.height - 30.dp.toPx())
                        var band = bandAt(down.position.x, w)
                        var mb = plot.mbAt(down.position.y)
                        active = band
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(listOf(band to mb))
                        try {
                            drag(down.id) { move ->
                                move.consume()
                                val toBand = bandAt(move.position.x, w)
                                val toMb = plot.mbAt(move.position.y)
                                if (toBand != band) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (toBand != band || toMb != current[toBand]) change(sweep(band, mb, toBand, toMb))
                                band = toBand; mb = toMb; active = toBand
                            }
                        } finally { active = -1 }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val plot = EqPlot(0f, 30.dp.toPx(), size.width, size.height - 30.dp.toPx())
                val column = size.width / EQ_BANDS
                val zero = plot.y(0f)
                drawLine(
                    scheme.onSurface.copy(alpha = .2f), Offset(10.dp.toPx(), zero), Offset(size.width - 10.dp.toPx(), zero), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )
                // a smooth line through the thumbs (Catmull-Rom), behind them
                val points = List(EQ_BANDS) { Offset(column * (it + .5f), plot.y(levels[it].toFloat())) }
                val line = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (k in 0 until EQ_BANDS - 1) {
                        val p0 = points[maxOf(k - 1, 0)]; val p1 = points[k]; val p2 = points[k + 1]; val p3 = points[minOf(k + 2, EQ_BANDS - 1)]
                        cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
                    }
                }
                drawPath(line, scheme.onSurface.copy(alpha = .3f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val track = 4.dp.toPx()
                for (b in 0 until EQ_BANDS) {
                    val at = points[b]
                    drawRoundRect(scheme.onSurface.copy(alpha = .1f), Offset(at.x - track / 2, plot.top), Size(track, plot.bottom - plot.top), CornerRadius(track / 2))
                    drawRoundRect(scheme.primary, Offset(at.x - track / 2, minOf(at.y, zero)), Size(track, abs(at.y - zero)), CornerRadius(track / 2))
                    val thumbW = (if (b == active) 28.dp else 24.dp).toPx().coerceAtMost(column - 2.dp.toPx())
                    val thumbH = (if (b == active) 14.dp else 12.dp).toPx()
                    drawRoundRect(scheme.onSurface, Offset(at.x - thumbW / 2, at.y - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbH / 2))
                    val value = measurer.measure(dbText(levels[b]), if (b == active) small.copy(color = scheme.onSurface) else small)
                    drawText(value, topLeft = Offset(at.x - value.size.width / 2f, plot.top - value.size.height - 8.dp.toPx()))
                    val hz = EQ_CENTERS_HZ[b].toInt()
                    val name = measurer.measure(if (hz < 1000) "$hz" else "${hz / 1000}k", small)
                    drawText(name, topLeft = Offset(at.x - name.size.width / 2f, plot.bottom + 9.dp.toPx()))
                }
            }
            BandSemantics(levels, Modifier.matchParentSize(), set)
        }
        Text(
            "Sweep across the sliders to draw a curve.",
            Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}

private class LivePicture {
    val shown = FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB }
    /** Counts drawn frames; reading it is what makes the graph redraw with the music. */
    var frame by mutableIntStateOf(0)
}

/** The level of each [Spectrum] band to draw now, following the music smoothly; sinks to the floor when paused. */
@Composable
private fun rememberLivePicture(tap: SpectrumTap, isPlaying: Boolean): LivePicture {
    val playing by rememberUpdatedState(isPlaying)
    val target = remember { FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB } }
    val picture = remember { LivePicture() }
    val context = LocalContext.current
    // with animations turned off in the phone's settings the wave drifts slowly instead of jumping to the beat
    val reduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    LifecycleResumeEffect(Unit) { tap.clear(); tap.listening = true; onPauseOrDispose { tap.listening = false } }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            tap.next((now - last) / 1e6f)?.let { f ->
                // neighbours mixed in a little, so one loud band shows as a hill, not a spike
                for (i in target.indices) target[i] = (f[maxOf(i - 1, 0)] + 2 * f[i] + f[minOf(i + 1, f.size - 1)]) / 4
            }
            last = now
            for (i in picture.shown.indices) {
                val to = if (playing) target[i] else Spectrum.FLOOR_DB
                picture.shown[i] += (to - picture.shown[i]) * if (reduced) .03f else if (to > picture.shown[i]) .45f else .2f
            }
            picture.frame++
        }
    }
    return picture
}

/** "+3", "−1.5", "0". */
internal fun dbText(mb: Int): String {
    val db = abs(mb) / 100f
    val text = if (mb % 100 == 0) "${db.toInt()}" else "%.1f".format(java.util.Locale.US, db)
    return if (mb > 0) "+$text" else if (mb < 0) "−$text" else text
}

/** "62 Hz", "910 Hz", "3.6 kHz", "14 kHz". */
internal fun hzText(hz: Int): String = when {
    hz < 1000 -> "$hz Hz"
    hz < 10_000 -> "%.1f".format(java.util.Locale.US, hz / 1000f).removeSuffix(".0") + " kHz"
    else -> "${(hz / 1000f).roundToInt()} kHz"
}
