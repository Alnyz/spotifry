package com.spotifry.music.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.Settings
import com.spotifry.music.player.SpatialSetting

/** Light, Natural and Strong, then the three sliders behind them. A slider that doesn't match a preset leaves none picked. */
@Composable
fun SpatialControls(settings: Settings) {
    val t by settings.spatialTune.collectAsStateWithLifecycle()
    Item("Strength", below = true) {
        Segments(SpatialSetting.PRESETS.map { it.first }, SpatialSetting.PRESETS.indexOfFirst { it.second == t }, Modifier.fillMaxWidth(), fill = true) {
            settings.setSpatialTune(SpatialSetting.PRESETS[it].second)
        }
    }
    Slide("Head shadow", "${t.shadowHz} Hz", t.shadowHz.toFloat(), 300f..1500f, { settings.setSpatialTune(t.copy(shadowHz = it.toInt()), false) }) { settings.setSpatialTune(settings.spatialTune.value) }
    Slide("Crossfeed", "−${t.crossDb} dB", t.crossDb.toFloat(), 3f..12f, { settings.setSpatialTune(t.copy(crossDb = it.toInt()), false) }) { settings.setSpatialTune(settings.spatialTune.value) }
    Slide("Amount", "${t.amount}%", t.amount.toFloat(), 0f..100f, { settings.setSpatialTune(t.copy(amount = it.toInt()), false) }) { settings.setSpatialTune(settings.spatialTune.value) }
}

@Composable
private fun Slide(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Item(title, value, below = true) { Slider(v, onChange, Modifier.fillMaxWidth(), valueRange = range, onValueChangeFinished = onDone) }
}
