// The idea of picking a headphone from AutoEq's list and fitting a correction to it is from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.
// The measurements are from the AutoEq project (MIT, https://github.com/jaakkopasanen/AutoEq).

package com.spotifry.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.spotifry.music.data.AutoEqSource
import com.spotifry.music.data.Settings
import com.spotifry.music.player.AUTOEQ_PREFIX
import com.spotifry.music.player.AutoEqBadFile
import com.spotifry.music.player.AutoEqEntry
import com.spotifry.music.player.AutoEqFit
import com.spotifry.music.player.EQ_BANDS
import com.spotifry.music.player.EQ_CENTERS_HZ
import com.spotifry.music.player.EQ_MAX_MB
import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.EqDesign
import com.spotifry.music.player.EqShape
import com.spotifry.music.player.Spectrum
import com.spotifry.music.player.fitAutoEq
import com.spotifry.music.player.eqHzAt
import com.spotifry.music.player.eqHzPos
import com.spotifry.music.player.eqQAt
import com.spotifry.music.player.eqQPos
import com.spotifry.music.player.eqQText
import com.spotifry.music.player.searchAutoEq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val TABS = listOf("Graph", "Bands", "Headphone", "Tuning")
private const val STEP_MB = 50
private const val MIN_HZ = 20.0
private const val MAX_HZ = 20_000.0
private val CURVE_HZ = DoubleArray(160) { MIN_HZ * (MAX_HZ / MIN_HZ).pow(it / 159.0) }
private val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private val GRID_MB = listOf(1200 to "+12 dB", 600 to "+6", 0 to "0", -600 to "−6", -1200 to "−12")

/** Ten bands from 31 Hz to 16 kHz, the phone's presets and headphone correction; the same settings the phone backs up. */
@Composable
fun EqualizerPage(services: Services, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings = services.library.settings
    val on by settings.eqOn.collectAsState()
    val preset by settings.eqPreset.collectAsState()
    val levels by settings.eqLevels.collectAsState()
    val bands by settings.eqShape.collectAsState()
    val tune by settings.tune.collectAsState()
    val compare by settings.compare.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val playing by services.player.state.collectAsState()
    val autoEq = remember { AutoEqSource(services.library.client, File(dataDir(), "autoeq")) }
    // any shaping means the listener wants to hear it, so it also turns the equalizer on; saved when the mouse lets go
    val shape = { changes: List<Pair<Int, Int>> ->
        val next = settings.eqLevels.value.toMutableList()
        changes.forEach { (band, mb) -> next[band] = mb }
        settings.setEq(null, next, save = false)
        if (!settings.eqOn.value) settings.setEqOn(true)
    }
    val dim = if (on) 1f else .55f

    LazyColumn(modifier.widthIn(max = 900.dp)) {
        item {
            Text("‹ Settings", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
        }
        item {
            PageHeader("Equalizer & tuning", if (on) "On · ${preset?.removePrefix(AUTOEQ_PREFIX) ?: "Custom"}" else if (!tune.neutral) "Off · Tuning still works" else "Off") {
                // hear the music without the equalizer and the tuning; "Original" and filled while it is on
                Tip("Hear the music without your changes") { Pill(if (compare) "Original" else "Compare", accent = compare) { settings.setCompare(!compare) } }
                Pill("Reset") { settings.setEq("Flat", List(EQ_BANDS) { 0 }) }
                Box(Modifier.align(androidx.compose.ui.Alignment.CenterVertically).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settings.setEqOn(!on) }) { OnOffSwitch(on) }
            }
        }
        item { SoundSetCard(settings, services.output.collectAsState().value) }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 24.dp).alpha(dim), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EQ_PRESETS.forEach { (name, l) -> Chip(name, preset == name) { settings.setEq(name, l); settings.setEqOn(true) } }
                // Custom only shows while it's the one in use, so no chip ever moves
                if (EQ_PRESETS.none { it.first == preset }) Chip(if (preset?.startsWith(AUTOEQ_PREFIX) == true) "Headphone" else "Custom", true) {}
            }
        }
        item {
            Row(Modifier.padding(top = 26.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TABS.forEachIndexed { i, t -> Chip(if (i == 3 && !tune.neutral) "$t •" else t, tab == i) { tab = i } }
            }
        }
        item {
            // Compare dims everything, as none of it is heard while it is on
            Column(Modifier.alpha(if (compare) .45f else 1f)) { when (tab) {
                0 -> Column(Modifier.alpha(dim)) { Graph(on, levels, bands, shape, settings::saveEq, rememberLivePicture(services.listening, playing.isPlaying)) }
                1 -> Column(Modifier.alpha(dim)) { Bands(levels, bands, shape, settings::saveEq, settings) }
                // the headphone list works with the equalizer off, so it isn't dimmed like the rest
                2 -> Headphone(settings, autoEq, preset, on)
                // the tuning works with the equalizer off, so it isn't dimmed either
                else -> Tuning(settings)
            } }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

/**
 * Headphones and speakers each keep their own sound, when switched on; the app switches by itself as headphones come and
 * go. Not shown when the sound server can't say which is playing.
 */
@Composable
private fun SoundSetCard(settings: Settings, output: SoundOutput?) {
    output ?: return
    val on by settings.perDevice.collectAsState()
    val set by settings.soundSet.collectAsState()
    val now = if (output.headphones) Settings.HEADPHONES else Settings.SPEAKER
    Column(Modifier.padding(top = 20.dp).fillMaxWidth().stage().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settings.setPerDevice(!on, now) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Separate sound for headphones", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.ink)
                Text(if (on) "On · switches by itself" else "Off · one sound for everything", fontSize = 13.sp, color = Palette.ink2)
            }
            OnOffSwitch(on)
        }
        if (on) {
            val headphones = set == Settings.HEADPHONES
            val what = if (headphones == output.headphones) "${output.name}${if (output.bluetooth) " · Bluetooth" else ""}" else if (headphones) "Headphones" else "Speakers"
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.accent.copy(alpha = .18f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.Icon(if (headphones) Icons.Headphones else Icons.Volume, null, Modifier.size(20.dp), tint = Palette.accent)
                }
                Column {
                    Text("For: " + if (headphones) "Headphones" else "Speakers", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.ink)
                    Text("$what. Changes on this page are for ${if (headphones) "these headphones" else "the speakers"} only.", fontSize = 13.sp, color = Palette.ink2)
                }
            }
            Text(if (headphones) "Unplug them to set the speakers' sound." else "Plug in headphones to set their sound.", fontSize = 13.sp, color = Palette.ink2)
        }
    }
}

private fun tuneDb(v: Int) = if (v > 0) "+$v dB" else if (v < 0) "−${-v} dB" else "0 dB"
private const val HIGH_PASS_MAX = 500f
private const val LOW_PASS_MIN = 1_000f
private const val LOW_PASS_TOP = 20_000f
/** Below this on the high-pass track it is off. */
private const val HIGH_PASS_FLOOR = 20f

/** Where a slider's coloured part starts: the middle (zero is empty), the left end, or the right end. */
private enum class Fill { CENTRE, LEFT, RIGHT }

/** Balance, width, gain, the limiter, the two filters and night mode, as on the phone. Works with the equalizer off. */
@Composable
private fun Tuning(settings: Settings) {
    val t by settings.tune.collectAsState()
    val save = { settings.setTune(settings.tune.value) }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Back to how the music was recorded.", Modifier.weight(1f), fontSize = 13.sp, color = Palette.ink2)
        Box(Modifier.alpha(if (t.neutral) .4f else 1f)) { Chip("Reset", false) { if (!t.neutral) settings.setTune(com.spotifry.music.player.FineTuneSetting()) } }
    }
    TuneGroup("Stereo") {
        TuneSlider("Balance", if (t.balance == 0) "Centre" else if (t.balance < 0) "Left ${-t.balance}%" else "Right ${t.balance}%", t.balance != 0, t.balance.toFloat(), -100f..100f, 0f, Fill.CENTRE,
            { settings.setTune(t.copy(balance = it.roundToInt()), false) }, save)
        TuneSlider("Stereo width", if (t.width == 0) "Mono" else "${t.width}%", t.width != 100, t.width.toFloat(), 0f..200f, 100f, Fill.CENTRE,
            { settings.setTune(t.copy(width = it.roundToInt()), false) }, save)
    }
    TuneGroup("Volume") {
        TuneSlider("Gain", tuneDb(t.gainDb), t.gainDb != 0, t.gainDb.toFloat(), -15f..15f, 0f, Fill.CENTRE,
            { settings.setTune(t.copy(gainDb = it.roundToInt()), false) }, save)
        Row(
            Modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settings.setTune(t.copy(limiter = !t.limiter)) }.padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Volume limiter", fontSize = 15.sp, color = Palette.ink)
                Text("Keeps peaks under −1 dB so a boost can't distort.", fontSize = 13.sp, color = Palette.ink2)
            }
            OnOffSwitch(t.limiter)
        }
    }
    TuneGroup("Filters") {
        TuneSlider("High-pass", if (t.highPassHz == 0) "Off" else "${t.highPassHz} Hz", t.highPassHz != 0, if (t.highPassHz == 0) 0f else t.highPassHz.toFloat(), 0f..HIGH_PASS_MAX, 0f, Fill.LEFT,
            { v -> settings.setTune(t.copy(highPassHz = if (v < HIGH_PASS_FLOOR) 0 else v.roundToInt()), false) }, save, hint = "Removes low rumble.")
        TuneSlider("Low-pass", if (t.lowPassHz == 0) "Off" else "${t.lowPassHz} Hz", t.lowPassHz != 0, if (t.lowPassHz == 0) LOW_PASS_TOP else t.lowPassHz.toFloat(), LOW_PASS_MIN..LOW_PASS_TOP, LOW_PASS_TOP, Fill.RIGHT,
            { v -> settings.setTune(t.copy(lowPassHz = if (v >= LOW_PASS_TOP - 100) 0 else (v / 50).roundToInt() * 50), false) }, save, hint = "Softens harsh highs.")
    }
    TuneGroup("Night mode") {
        Text("Strength", fontSize = 15.sp, color = Palette.ink)
        Text("Evens out loud and quiet parts, so you can play it low.", fontSize = 13.sp, color = Palette.ink2)
        Row(Modifier.padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Off", "Light", "Strong").forEachIndexed { i, n -> Chip(n, t.night == i) { settings.setTune(t.copy(night = i)) } }
        }
    }
}

@Composable
private fun TuneGroup(name: String, content: @Composable ColumnScope.() -> Unit) {
    Kicker(name, Modifier.padding(top = 18.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().stage().padding(horizontal = 20.dp, vertical = 10.dp), content = content)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TuneSlider(
    title: String, value: String, changed: Boolean, v: Float, range: ClosedFloatingPointRange<Float>, centre: Float, fill: Fill,
    onChange: (Float) -> Unit, onDone: () -> Unit, hint: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 15.sp, color = Palette.ink)
            Text(value, fontSize = 14.sp, color = if (changed) Palette.accent else Palette.ink2, fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal)
        }
        if (hint != null) Text(hint, fontSize = 13.sp, color = Palette.ink2)
        val base = Palette.ink.copy(alpha = .1f); val color = Palette.accent; val tick = Palette.ink.copy(alpha = .35f)
        androidx.compose.material3.Slider(
            v, onChange, Modifier.fillMaxWidth(), valueRange = range, onValueChangeFinished = onDone,
            thumb = { Box(Modifier.size(width = 4.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(if (changed) Palette.accent else tick)) },
            track = { st ->
                val span = range.endInclusive - range.start
                val at = (st.value - range.start) / span
                val mid = (centre - range.start) / span
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(base, size = size, cornerRadius = r)
                    val (from, to) = when (fill) { Fill.CENTRE -> minOf(mid, at) to maxOf(mid, at); Fill.LEFT -> 0f to at; Fill.RIGHT -> at to 1f }
                    if (to > from) drawRoundRect(color, Offset(size.width * from, 0f), Size(size.width * (to - from), size.height), r)
                    if (fill == Fill.CENTRE) drawRect(tick, Offset(size.width * mid - 1f, -3f), Size(2f, size.height + 6f))
                }
            },
        )
    }
}

/** Where frequencies and levels sit inside a drawing. Top is +12 dB. */
private class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun x(hz: Double) = left + (log10(hz / MIN_HZ) / log10(MAX_HZ / MIN_HZ)).toFloat() * (right - left)
    fun y(mb: Float) = top + (EQ_MAX_MB - mb) / (2f * EQ_MAX_MB) * (bottom - top)
    /** The level at [y], held to the range, in half-dB steps. */
    fun mbAt(y: Float): Int = mbAt(y, top, bottom)
}

private fun mbAt(y: Float, top: Float, bottom: Float): Int {
    val mb = EQ_MAX_MB - (y - top) / (bottom - top) * 2 * EQ_MAX_MB
    return ((mb / STEP_MB).roundToInt() * STEP_MB).coerceIn(-EQ_MAX_MB, EQ_MAX_MB)
}

internal fun hzLabel(hz: Double): String { val n = hz.roundToInt(); return if (n < 1000) "$n" else "%.1f".format(java.util.Locale.US, n / 1000f).removeSuffix(".0") + "k" }

private fun dbText(mb: Int) = (if (mb > 0) "+" else if (mb < 0) "−" else "") + "%.1f".format(java.util.Locale.US, abs(mb) / 100f).removeSuffix(".0")

private fun Modifier.stage() = clip(RoundedCornerShape(14.dp)).background(Palette.ink.copy(alpha = 0.05f)).border(1.dp, Palette.ink.copy(alpha = 0.1f), RoundedCornerShape(14.dp))

private class LivePicture {
    val shown = FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB }
    /** Counts drawn frames; reading it is what makes the graph redraw with the music. */
    var frame by mutableStateOf(0)
}

/**
 * The level of each [Spectrum] band to draw now, following the music smoothly; sinks to the floor when paused. The song
 * heard is decoded once (a few seconds), then read at the moment playing, every frame.
 */
@Composable
private fun rememberLivePicture(listening: () -> Pair<String, Long>?, isPlaying: Boolean): LivePicture {
    val playing by rememberUpdatedState(isPlaying)
    val picture = remember { LivePicture() }
    LaunchedEffect(Unit) {
        val raw = FloatArray(Spectrum.BANDS)
        val target = FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB }
        var song: SongPicture? = null
        while (true) {
            withFrameNanos { }
            val now = listening()
            if (now != null && song?.url != now.first) { val next = SongPicture(now.first); song = next; launch(Dispatchers.IO) { next.load() } }
            val have = now != null && playing && song?.bandsAt(now.second, raw) == true
            // neighbours mixed in a little, so one loud band shows as a hill, not a spike
            if (have) for (i in target.indices) target[i] = (raw[maxOf(i - 1, 0)] + 2 * raw[i] + raw[minOf(i + 1, raw.size - 1)]) / 4
            for (i in picture.shown.indices) {
                val to = if (have) target[i] else Spectrum.FLOOR_DB
                picture.shown[i] += (to - picture.shown[i]) * if (to > picture.shown[i]) .3f else .12f
            }
            picture.frame++
        }
    }
    return picture
}

/** Music gets quieter toward the high notes; tilting the picture up 3 dB an octave keeps the highs moving, like most music analyzers. */
private val TILT = FloatArray(Spectrum.BANDS) { 3f * (kotlin.math.ln(Spectrum.bandCenterHz(it) / 1000f) / kotlin.math.ln(2f)) }

/**
 * The real curve you hear over the music as it plays, with one point per band: a press grabs the nearest point and it
 * follows the mouse up and down.
 */
@Composable
private fun Graph(on: Boolean, levels: List<Int>, bands: EqShape, set: (List<Pair<Int, Int>>) -> Unit, done: () -> Unit, picture: LivePicture) {
    val change by rememberUpdatedState(set)
    val finished by rememberUpdatedState(done)
    var dragging by remember { mutableStateOf(-1) }
    val design = remember(levels, bands) { EqDesign(levels, shape = bands) }
    val bandHz by rememberUpdatedState(bands.hz)
    val curve = remember(design) { FloatArray(CURVE_HZ.size) { design.responseDb(CURVE_HZ[it]).toFloat() * 100 } }
    // the music as it comes out: shaped by the curve and turned down by its preamp, only while the equalizer is on
    val shaped = remember(design, on) {
        val preampDb = 20 * log10(design.preamp).toFloat()
        FloatArray(Spectrum.BANDS) { if (on) design.responseDb(Spectrum.bandCenterHz(it).toDouble()).toFloat() + preampDb else 0f }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ink = Palette.ink; val ink2 = Palette.ink2; val accent = Palette.accent
    fun plot(size: Size) = with(density) { Plot(52.dp.toPx(), 16.dp.toPx(), size.width - 20.dp.toPx(), size.height - 30.dp.toPx()) }
    fun nearest(p: Plot, x: Float) = (0 until EQ_BANDS).minBy { abs(p.x(bandHz[it]) - x) }
    Canvas(
        Modifier.fillMaxWidth().height(340.dp).stage()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> val p = plot(size.toSize()); dragging = nearest(p, o.x); change(listOf(dragging to p.mbAt(o.y))) },
                    onDrag = { c, _ -> if (dragging >= 0) change(listOf(dragging to plot(size.toSize()).mbAt(c.position.y))) },
                    onDragEnd = { dragging = -1; finished() }, onDragCancel = { dragging = -1; finished() },
                )
            }
            .pointerInput(Unit) { detectTapGestures { o -> val p = plot(size.toSize()); change(listOf(nearest(p, o.x) to p.mbAt(o.y))); finished() } },
    ) {
        val p = plot(size)
        val label = TextStyle(color = ink2, fontSize = 11.sp)
        GRID_MB.forEach { (mb, text) ->
            val y = p.y(mb.toFloat())
            drawLine(ink.copy(alpha = if (mb == 0) .18f else .07f), Offset(p.left, y), Offset(p.right, y), 1.dp.toPx())
            val t = measurer.measure(text, label)
            drawText(t, topLeft = Offset(p.left - t.size.width - 8.dp.toPx(), y - t.size.height / 2))
        }
        for (b in 0 until EQ_BANDS) {
            val t = measurer.measure(BAND_LABELS[b], label)
            drawText(t, topLeft = Offset(p.x(EQ_CENTERS_HZ[b]) - t.size.width / 2, p.bottom + 8.dp.toPx()))
        }
        // the music, faintly, so you can see what you're shaping: -75 dB on the floor, 0 dB near the top
        picture.frame
        val px = { i: Int -> p.x(Spectrum.bandCenterHz(i).toDouble()) }
        val py = { i: Int -> p.bottom - ((picture.shown[i] + TILT[i] + shaped[i] + 75f) / 75f).coerceIn(0f, 1f) * (p.bottom - p.top) * .7f }
        val last = Spectrum.BANDS - 1
        val line = Path().apply {
            // the analysis covers 40 Hz to 16 kHz; its end values are held out to the edges
            moveTo(p.left, py(0)); lineTo(px(0), py(0))
            for (i in 1 until last) quadraticTo(px(i), py(i), (px(i) + px(i + 1)) / 2, (py(i) + py(i + 1)) / 2)
            lineTo(px(last), py(last)); lineTo(p.right, py(last))
        }
        val under = Path().apply { addPath(line); lineTo(p.right, p.bottom); lineTo(p.left, p.bottom); close() }
        drawPath(under, Brush.verticalGradient(listOf(ink.copy(alpha = .16f), ink.copy(alpha = .02f)), p.top, p.bottom))
        drawPath(line, ink.copy(alpha = .18f), style = Stroke(1.dp.toPx()))
        val path = Path()
        CURVE_HZ.forEachIndexed { i, hz ->
            val x = p.x(hz); val y = p.y(curve[i].coerceIn(-EQ_MAX_MB.toFloat(), EQ_MAX_MB.toFloat()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        for (b in 0 until EQ_BANDS) {
            val c = Offset(p.x(bands.hz[b]), p.y(levels[b].toFloat()))
            drawCircle(accent.copy(alpha = .25f), (if (b == dragging) 13 else 10).dp.toPx(), c)
            drawCircle(accent, 7.dp.toPx(), c)
        }
        if (dragging >= 0) {
            val t = measurer.measure("${dbText(levels[dragging])} dB", TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val c = Offset(p.x(bands.hz[dragging]), p.y(levels[dragging].toFloat()))
            drawText(t, topLeft = Offset((c.x - t.size.width / 2).coerceIn(p.left, p.right - t.size.width), (c.y - t.size.height - 18.dp.toPx()).coerceAtLeast(0f)))
        }
    }
    Text("Drag a point up for more of that sound, down for less.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
}

/**
 * The mouse moved from one band's column to another. A quick sweep can jump over columns, so those get levels along the
 * line between the two.
 */
internal fun sweep(fromBand: Int, fromMb: Int, toBand: Int, toMb: Int): List<Pair<Int, Int>> {
    if (fromBand == toBand) return listOf(toBand to toMb)
    val dir = if (toBand > fromBand) 1 else -1
    return generateSequence(fromBand + dir) { it + dir }.takeWhile { it != toBand + dir }.map { b ->
        val t = (b - fromBand).toFloat() / (toBand - fromBand)
        b to ((fromMb + (toMb - fromMb) * t) / STEP_MB).roundToInt() * STEP_MB
    }.toList()
}

/** Ten sliders side by side; dragging across them draws a curve. */
@Composable
private fun Bands(levels: List<Int>, bands: EqShape, set: (List<Pair<Int, Int>>) -> Unit, done: () -> Unit, settings: Settings) {
    var selected by remember { mutableStateOf(0) }
    val base by settings.eqBase.collectAsState()
    // the last preset picked comes back with its own levels and every band in its usual place; with none, only the places
    val presetLevels = EQ_PRESETS.firstOrNull { it.first == base }?.second
    val canGoBack = !bands.isDefault || (presetLevels != null && levels != presetLevels)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
        Box(Modifier.alpha(if (canGoBack) 1f else .4f)) {
            Chip(if (presetLevels != null) "Back to $base" else "Reset bands", false) { if (canGoBack) { if (presetLevels != null) settings.setEq(base, presetLevels) else settings.setEqShape(EqShape.DEFAULT) } }
        }
    }
    val change by rememberUpdatedState(set)
    val finished by rememberUpdatedState(done)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ink = Palette.ink; val ink2 = Palette.ink2; val accent = Palette.accent
    val top = with(density) { 34.dp.toPx() }; val gap = with(density) { 30.dp.toPx() }
    fun at(o: Offset, size: Size) = (o.x / size.width * EQ_BANDS).toInt().coerceIn(0, EQ_BANDS - 1) to mbAt(o.y, top, size.height - gap)
    Canvas(
        Modifier.fillMaxWidth().height(320.dp).stage()
            .pointerInput(Unit) {
                var last: Pair<Int, Int>? = null
                detectDragGestures(
                    onDragStart = { o -> at(o, size.toSize()).let { last = it; selected = it.first; change(listOf(it)) } },
                    onDrag = { c, _ ->
                        val a = at(c.position, size.toSize())
                        last?.let { l -> change(sweep(l.first, l.second, a.first, a.second)) }
                        last = a; selected = a.first
                    },
                    onDragEnd = { last = null; finished() }, onDragCancel = { last = null; finished() },
                )
            }
            .pointerInput(Unit) { detectTapGestures { o -> at(o, size.toSize()).let { selected = it.first; change(listOf(it)) }; finished() } },
    ) {
        val bottom = size.height - gap
        val w = size.width / EQ_BANDS
        val track = 8.dp.toPx()
        fun y(mb: Int) = top + (EQ_MAX_MB - mb) / (2f * EQ_MAX_MB) * (bottom - top)
        drawRoundRect(accent.copy(alpha = .12f), Offset(w * selected + 2.dp.toPx(), 4.dp.toPx()), Size(w - 4.dp.toPx(), size.height - 8.dp.toPx()), CornerRadius(12.dp.toPx()))
        for (b in 0 until EQ_BANDS) {
            val cx = w * b + w / 2
            drawRoundRect(ink.copy(alpha = .08f), Offset(cx - track / 2, top), Size(track, bottom - top), CornerRadius(track / 2))
            val y0 = y(0); val y1 = y(levels[b])
            drawRoundRect(accent, Offset(cx - track / 2, minOf(y0, y1)), Size(track, abs(y1 - y0)), CornerRadius(track / 2))
            drawCircle(accent, 9.dp.toPx(), Offset(cx, y1))
            val v = measurer.measure(dbText(levels[b]), TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            drawText(v, topLeft = Offset(cx - v.size.width / 2, 8.dp.toPx()))
            // a band moved from its usual place shows its own frequency in the accent colour, with a dot under it, like the phone
            val moved = bands.moved(b)
            val h = measurer.measure(hzLabel(bands.hz[b]), TextStyle(color = if (moved) accent else ink2, fontSize = 11.sp, fontWeight = if (moved) FontWeight.Bold else null))
            drawText(h, topLeft = Offset(cx - h.size.width / 2, bottom + 8.dp.toPx()))
            if (moved) drawCircle(accent, 2.dp.toPx(), Offset(cx, size.height - 5.dp.toPx()))
        }
    }
    Text("Drag a slider, or sweep across them to draw a curve. Click one to fine-tune it below.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    BandEditor(selected, levels[selected], bands, { selected = (selected + it).coerceIn(0, EQ_BANDS - 1) }, set, done, settings)
}

/** One band's frequency, level and width, like the phone's. Moving or widening a band makes the curve the listener's own, like a drag does. */
@Composable
private fun BandEditor(band: Int, mb: Int, bands: EqShape, step: (Int) -> Unit, set: (List<Pair<Int, Int>>) -> Unit, done: () -> Unit, settings: Settings) {
    val default = EqShape.DEFAULT
    val move = { next: EqShape -> settings.setEqShape(next, save = false); if (!settings.eqOn.value) settings.setEqOn(true) }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().stage().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Band ${band + 1} of $EQ_BANDS", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.ink)
            Text("‹", fontSize = 22.sp, color = if (band > 0) Palette.ink else Palette.ink2.copy(alpha = .4f), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = band > 0) { step(-1) }.padding(horizontal = 12.dp, vertical = 2.dp))
            Text("›", fontSize = 22.sp, color = if (band < EQ_BANDS - 1) Palette.ink else Palette.ink2.copy(alpha = .4f), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = band < EQ_BANDS - 1) { step(1) }.padding(horizontal = 12.dp, vertical = 2.dp))
        }
        BandSlider("Frequency", hzText(bands.hz[band].roundToInt()), "Which part of the sound this band changes.", eqHzPos(bands.hz[band]), 0f..1f, { move(bands.with(band, hz = eqHzAt(it))) }, done)
        BandSlider("Level", "${dbText(mb)} dB", null, mb / 100f, -EQ_MAX_MB / 100f..EQ_MAX_MB / 100f, { set(listOf(band to ((it * 100 / STEP_MB).roundToInt() * STEP_MB).coerceIn(-EQ_MAX_MB, EQ_MAX_MB))) }, done)
        BandSlider("Width", eqQText(bands.q[band]), "Narrow fixes one spot. Wide shapes a whole range.", eqQPos(bands.q[band]), 0f..1f, { move(bands.with(band, q = eqQAt(it))) }, done)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.alpha(if (bands.moved(band)) 1f else .4f)) { Chip("Reset this band", false) { if (bands.moved(band)) settings.setEqShape(bands.with(band, default.hz[band], default.q[band])) } }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BandSlider(title: String, value: String, hint: String?, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.ink.copy(alpha = 0.14f))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(220.dp)) {
            Text(title, fontSize = 14.sp, color = Palette.ink)
            if (hint != null) Text(hint, fontSize = 12.sp, color = Palette.ink2)
        }
        androidx.compose.material3.Slider(
            v, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone, colors = colors,
            thumb = { Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.accent)) },
            track = { androidx.compose.material3.SliderDefaults.Track(it, Modifier.height(4.dp), colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
        )
        Text(value, Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 14.sp, color = Palette.ink)
    }
}

/** A frequency the way the phone writes it: 250 Hz, 1.5 kHz, 12 kHz. */
internal fun hzText(hz: Int): String = when {
    hz < 1000 -> "$hz Hz"
    hz < 10_000 -> "%.1f".format(java.util.Locale.US, hz / 1000f).removeSuffix(".0") + " kHz"
    else -> "${(hz / 1000f).roundToInt()} kHz"
}

private const val MAX_RESULTS = 30

private sealed interface Step {
    data object Idle : Step
    data class Fitting(val entry: AutoEqEntry) : Step
    data class Done(val entry: AutoEqEntry, val fit: AutoEqFit) : Step
    data class Failed(val text: String) : Step
}

/** Pick a headphone, and the ten bands are set to bend its measured sound toward the target curve. */
@Composable
private fun Headphone(settings: Settings, source: AutoEqSource, preset: String?, on: Boolean) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var listError by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    // what was found for which text, so "nothing found" isn't shown for a search still running
    var found by remember { mutableStateOf<Pair<String, List<AutoEqEntry>>?>(null) }
    val before by settings.eqBeforeSaved.collectAsState()
    val active = preset?.takeIf { it.startsWith(AUTOEQ_PREFIX) }?.removePrefix(AUTOEQ_PREFIX)

    LaunchedEffect(attempt) {
        listError = false
        try { list = source.index() } catch (e: Exception) { if (e is CancellationException) throw e; listError = true }
    }
    LaunchedEffect(query, list) {
        val all = list ?: return@LaunchedEffect
        found = query to withContext(Dispatchers.Default) { searchAutoEq(all, query) }
    }

    fun pick(entry: AutoEqEntry) {
        if (step is Step.Fitting) return
        // the download takes a while; if the equalizer is changed meanwhile, the result is dropped, not laid over the change
        val seenPreset = settings.eqPreset.value; val seenLevels = settings.eqLevels.value; val seenOn = settings.eqOn.value
        step = Step.Fitting(entry)
        scope.launch {
            step = try {
                val fit = withContext(Dispatchers.Default) { fitAutoEq(source.curve(entry)) }
                if (settings.applyEqCorrection(AUTOEQ_PREFIX + entry.name, fit.levelsMb, seenPreset, seenLevels, seenOn)) Step.Done(entry, fit) else Step.Idle
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Step.Failed(
                    if (e is AutoEqBadFile || e is IllegalArgumentException) "The measurement for ${entry.name} isn't available or can't be read. Try another result."
                    else "Couldn't get the measurement for ${entry.name}. Check your connection and try again.",
                )
            }
        }
    }

    val body = TextStyle(color = Palette.ink, fontSize = 15.sp)
    Column(Modifier.fillMaxWidth().stage().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (active != null) {
            if (on) {
                Kicker("Correcting for")
                Text(active, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.ink)
            } else Text("Equalizer is off. Turn it on to correct for $active.", style = body)
            Row { Chip(if (before) "Back to my curve" else if (on) "Turn off correction" else "Remove correction", false) { settings.restoreEqBefore() } }
        }
        when (val s = step) {
            Step.Idle -> {}
            is Step.Fitting -> Text("Fitting ${s.entry.name}…", style = body)
            is Step.Failed -> Text(s.text, style = body)
            is Step.Done -> if (active == s.entry.name) Text(resultText(s.fit), color = Palette.ink2, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Palette.ink.copy(alpha = 0.06f)).border(1.dp, Palette.ink.copy(alpha = 0.14f), RoundedCornerShape(23.dp)).padding(horizontal = 18.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            if (query.isEmpty()) Text("Headphone model", color = Palette.ink2, fontSize = 15.sp)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = body, cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        val all = list
        when {
            listError -> {
                Text("Couldn't load the headphone list. Check your connection and try again.", style = body)
                Row { Chip("Try again", false) { attempt++ } }
            }
            all == null -> Text("Loading the headphone list…", color = Palette.ink2, fontSize = 14.sp)
            query.isBlank() -> Text("Type part of the model name, like \"HD 600\" or \"WH-1000XM5\".", color = Palette.ink2, fontSize = 13.sp)
            else -> {
                // the old rows stay until the new ones arrive, so the page doesn't jump
                val results = found?.second.orEmpty()
                if (results.isEmpty() && found?.first == query) Text("No headphone with that name in the list.", color = Palette.ink2, fontSize = 14.sp)
                results.take(MAX_RESULTS).forEach { e ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = step !is Step.Fitting) { pick(e) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(e.name, color = Palette.ink, fontSize = 15.sp)
                        Text(e.sourceLabel, color = Palette.ink2, fontSize = 13.sp)
                    }
                }
                if (results.size > MAX_RESULTS) Text("${results.size - MAX_RESULTS} more. Type more of the name to narrow it down.", color = Palette.ink2, fontSize = 13.sp)
            }
        }
        Text(
            "Measurements by the AutoEq project, fitted to the ten bands here. Needs a connection to load the list and to pick a headphone.",
            color = Palette.ink2, fontSize = 13.sp,
        )
    }
}

/** Says what the fit got and what it cost, without claiming more than ten bands can do. */
private fun resultText(fit: AutoEqFit): String {
    fun db(x: Double) = "%.1f".format(java.util.Locale.US, x)
    val quieter = if (fit.peakBoostDb >= 1) " The sound is turned down by ${db(fit.peakBoostDb)} dB overall so the boost can't clip, so it will play quieter." else ""
    return "Done. Typical gap to the target: ${db(fit.errorBeforeDb)} dB before, ${db(fit.errorAfterDb)} dB now. Ten bands can't follow narrow dips.$quieter"
}
