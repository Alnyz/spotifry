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
import com.spotifry.music.player.Spectrum
import com.spotifry.music.player.fitAutoEq
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

private val TABS = listOf("Graph", "Bands", "Headphone")
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
            PageHeader("Equalizer", if (on) "On · ${preset?.removePrefix(AUTOEQ_PREFIX) ?: "Custom"}" else "Off") {
                Pill("Reset") { settings.setEq("Flat", List(EQ_BANDS) { 0 }) }
                Box(Modifier.align(androidx.compose.ui.Alignment.CenterVertically).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settings.setEqOn(!on) }) { OnOffSwitch(on) }
            }
        }
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
                TABS.forEachIndexed { i, t -> Chip(t, tab == i) { tab = i } }
            }
        }
        item {
            when (tab) {
                0 -> Column(Modifier.alpha(dim)) { Graph(on, levels, shape, settings::saveEq, rememberLivePicture(services.listening, playing.isPlaying)) }
                1 -> Column(Modifier.alpha(dim)) { Bands(levels, shape, settings::saveEq) }
                // the headphone list works with the equalizer off, so it isn't dimmed like the rest
                else -> Headphone(settings, autoEq, preset, on)
            }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
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
private fun Graph(on: Boolean, levels: List<Int>, set: (List<Pair<Int, Int>>) -> Unit, done: () -> Unit, picture: LivePicture) {
    val change by rememberUpdatedState(set)
    val finished by rememberUpdatedState(done)
    var dragging by remember { mutableStateOf(-1) }
    val design = remember(levels) { EqDesign(levels) }
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
    fun nearest(p: Plot, x: Float) = (0 until EQ_BANDS).minBy { abs(p.x(EQ_CENTERS_HZ[it]) - x) }
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
            val c = Offset(p.x(EQ_CENTERS_HZ[b]), p.y(levels[b].toFloat()))
            drawCircle(accent.copy(alpha = .25f), (if (b == dragging) 13 else 10).dp.toPx(), c)
            drawCircle(accent, 7.dp.toPx(), c)
        }
        if (dragging >= 0) {
            val t = measurer.measure("${dbText(levels[dragging])} dB", TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val c = Offset(p.x(EQ_CENTERS_HZ[dragging]), p.y(levels[dragging].toFloat()))
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
private fun Bands(levels: List<Int>, set: (List<Pair<Int, Int>>) -> Unit, done: () -> Unit) {
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
                    onDragStart = { o -> at(o, size.toSize()).let { last = it; change(listOf(it)) } },
                    onDrag = { c, _ ->
                        val a = at(c.position, size.toSize())
                        last?.let { l -> change(sweep(l.first, l.second, a.first, a.second)) }
                        last = a
                    },
                    onDragEnd = { last = null; finished() }, onDragCancel = { last = null; finished() },
                )
            }
            .pointerInput(Unit) { detectTapGestures { o -> change(listOf(at(o, size.toSize()))); finished() } },
    ) {
        val bottom = size.height - gap
        val w = size.width / EQ_BANDS
        val track = 8.dp.toPx()
        fun y(mb: Int) = top + (EQ_MAX_MB - mb) / (2f * EQ_MAX_MB) * (bottom - top)
        for (b in 0 until EQ_BANDS) {
            val cx = w * b + w / 2
            drawRoundRect(ink.copy(alpha = .08f), Offset(cx - track / 2, top), Size(track, bottom - top), CornerRadius(track / 2))
            val y0 = y(0); val y1 = y(levels[b])
            drawRoundRect(accent, Offset(cx - track / 2, minOf(y0, y1)), Size(track, abs(y1 - y0)), CornerRadius(track / 2))
            drawCircle(accent, 9.dp.toPx(), Offset(cx, y1))
            val v = measurer.measure(dbText(levels[b]), TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            drawText(v, topLeft = Offset(cx - v.size.width / 2, 8.dp.toPx()))
            val h = measurer.measure(BAND_LABELS[b], TextStyle(color = ink2, fontSize = 11.sp))
            drawText(h, topLeft = Offset(cx - h.size.width / 2, bottom + 8.dp.toPx()))
        }
    }
    Text("Drag a slider, or sweep across them to draw a curve.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
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
