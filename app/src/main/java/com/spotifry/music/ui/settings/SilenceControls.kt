package com.spotifry.music.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.Settings
import com.spotifry.music.player.SilenceRules

/** The two Skip silence sliders and Reset, shown under the switch while it is on. */
@Composable
fun SilenceControls(settings: Settings) {
    val db by settings.silenceDb.collectAsStateWithLifecycle()
    val cut by settings.silenceCut.collectAsStateWithLifecycle()
    Item("How quiet counts as silence", "$db dB · ${if (db <= -60) "only true silence" else if (db >= -35) "soft singing may go too" else "real gaps"}", below = true) {
        Slider(db.toFloat(), { settings.setSilence(it.toInt(), cut, false) }, Modifier.fillMaxWidth(), valueRange = SilenceRules.MIN_DB.toFloat()..SilenceRules.MAX_DB.toFloat(),
            onValueChangeFinished = { settings.setSilence(db, cut) })
    }
    Item("How much of the silence is cut", "$cut% · a new value is heard from the next song", below = true) {
        Slider(cut.toFloat(), { settings.setSilence(db, it.toInt(), false) }, Modifier.fillMaxWidth(), valueRange = SilenceRules.MIN_CUT_PERCENT.toFloat()..100f,
            onValueChangeFinished = { settings.setSilence(db, cut) })
    }
    Item("Reset", "Back to ${SilenceRules.DEFAULT_DB} dB and ${SilenceRules.DEFAULT_CUT_PERCENT}%", onClick = settings::resetSilence)
}
