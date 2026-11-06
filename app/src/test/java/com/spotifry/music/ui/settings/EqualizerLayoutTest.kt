package com.spotifry.music.ui.settings

import androidx.compose.ui.unit.dp
import com.spotifry.music.player.EQ_PRESETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerLayoutTest {
    @Test fun theGraphTakesWhatIsLeftAboveTheMiniPlayerWithinLimits() {
        // tall phone: full height; short phone: the minimum; in between: exactly what fits
        assertEquals(300.dp, graphHeight(900.dp, 80.dp))
        assertEquals(180.dp, graphHeight(536.dp, 80.dp))
        assertEquals(250.dp, graphHeight(770.dp, 80.dp))
        assertEquals("no mini player, more room", 300.dp, graphHeight(770.dp, 0.dp))
    }

    @Test fun customIsAlwaysLastAndTheOthersNeverMove() {
        val names = EQ_PRESETS.map { it.first }
        assertEquals(names, presetChips(names.first()))
        assertEquals(names, presetChips(names.last()))
        assertEquals(names + "Custom", presetChips(null))
        assertEquals(names + "Custom", presetChips("AutoEq: Sennheiser HD 600"))
        assertTrue(presetChips(null).take(names.size) == names)
    }
}
