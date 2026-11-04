package com.spotifry.music.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.Settings
import com.spotifry.music.player.EqInfo
import com.spotifry.music.player.Spectrum
import com.spotifry.music.player.SpectrumTap
import com.spotifry.music.player.eqDbAt
import com.spotifry.music.ui.components.ScreenHeader
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(settings: Settings, info: EqInfo?, tap: SpectrumTap, isPlaying: Boolean, onBack: () -> Unit, bottomInset: Dp) {
    val on by settings.eqOn.collectAsStateWithLifecycle()
    val preset by settings.eqPreset.collectAsStateWithLifecycle()
    val saved by settings.eqLevels.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val levels = if (info != null && saved.size == info.centersHz.size) saved else List(info?.centersHz?.size ?: 0) { 0 }
    // any change means the user wants to hear it, so it also turns the equalizer on
    val pick = { name: String?, l: List<Int> -> settings.setEq(name, l); settings.setEqOn(true) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset + 24.dp)) {
        ScreenHeader("Equalizer", onBack = onBack, trailing = {
            Switch(on, settings::setEqOn, Modifier.padding(end = 12.dp).semantics { contentDescription = "Equalizer" }, enabled = info != null, colors = switchColors())
        })
        if (info == null) {
            Text("This phone has no equalizer.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            return@Column
        }
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
            info.presets.forEach { (name, l) -> Chip(name, on && preset == name) { pick(name, l) } }
            Chip("Custom", on && preset == null) { pick(null, levels) }
        }
        Wave(tap, isPlaying, info, if (on) levels else emptyList())
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surface)
                .padding(horizontal = 10.dp, vertical = 14.dp).alpha(if (on) 1f else .5f),
            Arrangement.SpaceAround,
        ) {
            info.centersHz.forEachIndexed { i, hz ->
                Band(levels[i], info.minMb, info.maxMb, hzText(hz)) { mb -> pick(null, levels.toMutableList().also { it[i] = mb }) }
            }
        }
        Text(
            "Drag a bar up for more of that range, down for less. Low notes are on the left, high on the right. Changing a bar switches to Custom.",
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 40.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier.border(BorderStroke(1.dp, scheme.outline), CircleShape))
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick).padding(horizontal = 14.dp),
        Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface) }
}

/** The sound as it plays, low notes left, high right, with the equalizer's [levels] already applied; flat when paused. */
@Composable
private fun Wave(tap: SpectrumTap, isPlaying: Boolean, info: EqInfo, levels: List<Int>) {
    val scheme = MaterialTheme.colorScheme
    val raw by tap.bands.collectAsStateWithLifecycle()
    val target by rememberUpdatedState(if (isPlaying) raw else null)
    val shown = remember { FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB } }
    var frame by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // with animations turned off in the phone's settings the wave drifts slowly instead of jumping to the beat
    val reduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    LifecycleResumeEffect(Unit) { tap.listening = true; onPauseOrDispose { tap.listening = false } }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val t = target
            for (i in shown.indices) {
                val to = t?.get(i) ?: Spectrum.FLOOR_DB
                shown[i] += (to - shown[i]) * if (reduced) .03f else if (to > shown[i]) .5f else .15f
            }
            frame++
        }
    }
    val eqDb = remember(levels) { FloatArray(Spectrum.BANDS) { eqDbAt(Spectrum.bandCenterHz(it), info.centersHz, levels) } }
    val span = maxOf(info.maxMb, -info.minMb, 100) / 100f
    Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp).fillMaxWidth().height(132.dp).clip(RoundedCornerShape(18.dp)).background(scheme.surface)) {
        Canvas(Modifier.fillMaxSize().padding(top = 6.dp).semantics { contentDescription = "Live picture of the music" }) {
            frame.let { }
            val w = size.width; val h = size.height
            val x = { i: Int -> i * w / (Spectrum.BANDS - 1) }
            // -70 dB at the bottom, -10 dB near the top
            val y = { i: Int -> h - ((shown[i] + eqDb[i] + 70f) / 60f).coerceIn(0f, 1f) * h * .9f }
            val line = Path().apply { moveTo(0f, y(0)); for (i in 1 until Spectrum.BANDS) lineTo(x(i), y(i)) }
            val fill = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .55f), scheme.primary.copy(alpha = .04f))))
            drawPath(line, scheme.primary, style = Stroke(2.dp.toPx()))
            if (levels.isNotEmpty()) {
                val curve = Path().apply {
                    for (i in 0 until Spectrum.BANDS) (h / 2 - eqDb[i] / span * h * .35f).let { if (i == 0) moveTo(0f, it) else lineTo(x(i), it) }
                }
                drawPath(curve, scheme.onSurface.copy(alpha = .55f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            }
        }
        Text("Now playing", Modifier.padding(start = 14.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** One equalizer bar: drag or tap to set its level; 0 dB is the line across the middle. */
@Composable
private fun Band(mb: Int, minMb: Int, maxMb: Int, label: String, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onChange)
    val db = (mb / 100f).roundToInt()
    val dbText = if (db > 0) "+$db" else if (db < 0) "−${-db}" else "0"
    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(dbText, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        val at = { yPx: Float, hPx: Float -> (((1 - yPx / hPx) * (maxMb - minMb) + minMb) / 100f).roundToInt().times(100).coerceIn(minMb, maxMb) }
        Canvas(
            Modifier.width(44.dp).height(150.dp)
                .semantics {
                    contentDescription = label; stateDescription = "$dbText dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(mb.toFloat(), minMb.toFloat()..maxMb.toFloat())
                    setProgress { v -> change(((v / 100f).roundToInt() * 100).coerceIn(minMb, maxMb)); true }
                }
                .pointerInput(minMb, maxMb) { detectTapGestures { change(at(it.y, size.height.toFloat())) } }
                .pointerInput(minMb, maxMb) { detectVerticalDragGestures { c, _ -> change(at(c.position.y, size.height.toFloat())) } },
        ) {
            val cx = size.width / 2
            val knob = 11.dp.toPx()
            val top = knob; val bottom = size.height - knob
            val yOf = { v: Int -> bottom - (v - minMb).toFloat() / (maxMb - minMb) * (bottom - top) }
            val mid = yOf(0.coerceIn(minMb, maxMb))
            drawLine(scheme.surfaceContainerHigh, Offset(cx, top), Offset(cx, bottom), 6.dp.toPx(), StrokeCap.Round)
            drawLine(scheme.outline, Offset(cx - 9.dp.toPx(), mid), Offset(cx + 9.dp.toPx(), mid), 1.dp.toPx())
            drawLine(scheme.primary, Offset(cx, mid), Offset(cx, yOf(mb)), 6.dp.toPx(), StrokeCap.Round)
            drawCircle(scheme.primary, knob, Offset(cx, yOf(mb)))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** "60 Hz", "910 Hz", "3.6 kHz", "14 kHz". */
internal fun hzText(hz: Int): String = when {
    hz < 1000 -> "$hz Hz"
    hz < 10_000 -> "%.1f".format(java.util.Locale.US, hz / 1000f).removeSuffix(".0") + " kHz"
    else -> "${(hz / 1000f).roundToInt()} kHz"
}
