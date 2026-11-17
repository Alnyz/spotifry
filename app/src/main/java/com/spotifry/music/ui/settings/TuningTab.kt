package com.spotifry.music.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.Settings
import com.spotifry.music.player.FineTuneSetting
import kotlin.math.roundToInt

/** Where a slider's coloured part starts: the middle (zero is empty), the left end, or the right end. */
internal enum class Fill { CENTRE, LEFT, RIGHT }

private fun tuneDb(v: Int) = if (v > 0) "+$v dB" else if (v < 0) "−${-v} dB" else "0 dB"
private const val HIGH_PASS_MAX = 500f
private const val LOW_PASS_MIN = 1_000f
private const val LOW_PASS_TOP = 20_000f
/** Below this on the high-pass track it is off. */
private const val HIGH_PASS_FLOOR = 20f

/** Balance, width, gain, the limiter, the two filters and night mode. Works with the equalizer off. */
@Composable
internal fun TuningTab(settings: Settings) {
    val t by settings.tune.collectAsStateWithLifecycle()
    val save = { settings.setTune(settings.tune.value) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("Back to how the music was recorded.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text(
            "Reset", Modifier.alpha(if (t.neutral) .4f else 1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = !t.neutral, role = Role.Button) { settings.setTune(FineTuneSetting()) }.padding(horizontal = 10.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge, color = scheme.onSurface,
        )
    }
    Group("Stereo") {
        TuneSlider("Balance", if (t.balance == 0) "Centre" else if (t.balance < 0) "Left ${-t.balance}%" else "Right ${t.balance}%", t.balance != 0, t.balance.toFloat(), -100f..100f, 0f, Fill.CENTRE,
            { settings.setTune(t.copy(balance = it.roundToInt()), false) }, save)
        TuneSlider("Stereo width", if (t.width == 0) "Mono" else "${t.width}%", t.width != 100, t.width.toFloat(), 0f..200f, 100f, Fill.CENTRE,
            { settings.setTune(t.copy(width = it.roundToInt()), false) }, save)
    }
    Group("Volume") {
        TuneSlider("Gain", tuneDb(t.gainDb), t.gainDb != 0, t.gainDb.toFloat(), -15f..15f, 0f, Fill.CENTRE,
            { settings.setTune(t.copy(gainDb = it.roundToInt()), false) }, save)
        Item("Volume limiter", "Keeps peaks under −1 dB so a boost can't distort.", onClick = { settings.setTune(t.copy(limiter = !t.limiter)) }) {
            Switch(t.limiter, null, colors = switchColors())
        }
    }
    Group("Filters") {
        TuneSlider("High-pass", if (t.highPassHz == 0) "Off" else "${t.highPassHz} Hz", t.highPassHz != 0, if (t.highPassHz == 0) 0f else t.highPassHz.toFloat(), 0f..HIGH_PASS_MAX, 0f, Fill.LEFT,
            { v -> settings.setTune(t.copy(highPassHz = if (v < HIGH_PASS_FLOOR) 0 else v.roundToInt()), false) }, save, hint = "Removes low rumble.")
        TuneSlider("Low-pass", if (t.lowPassHz == 0) "Off" else "${t.lowPassHz} Hz", t.lowPassHz != 0, if (t.lowPassHz == 0) LOW_PASS_TOP else t.lowPassHz.toFloat(), LOW_PASS_MIN..LOW_PASS_TOP, LOW_PASS_TOP, Fill.RIGHT,
            { v -> settings.setTune(t.copy(lowPassHz = if (v >= LOW_PASS_TOP - 100) 0 else (v / 50).roundToInt() * 50), false) }, save, hint = "Softens harsh highs.")
    }
    Group("Night mode") {
        Item("Strength", "Evens out loud and quiet parts, so you can play it low.", below = true) {
            Segments(listOf("Off", "Light", "Strong"), t.night, Modifier.fillMaxWidth(), fill = true) { settings.setTune(t.copy(night = it)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TuneSlider(
    title: String, value: String, changed: Boolean, v: Float, range: ClosedFloatingPointRange<Float>, centre: Float, fill: Fill,
    onChange: (Float) -> Unit, onDone: () -> Unit, hint: String? = null, divider: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (changed) scheme.primary else scheme.onSurfaceVariant, fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Slider(
            v, onChange, Modifier.fillMaxWidth(), valueRange = range, onValueChangeFinished = onDone,
            thumb = { Box(Modifier.size(width = 4.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(if (changed) scheme.primary else scheme.outline)) },
            track = { st ->
                val span = range.endInclusive - range.start
                val at = (st.value - range.start) / span
                val mid = (centre - range.start) / span
                val base = scheme.surfaceContainerHigh; val color = scheme.primary; val tick = scheme.outline
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
    if (divider) androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
}

