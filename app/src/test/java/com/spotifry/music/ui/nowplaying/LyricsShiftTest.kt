package com.spotifry.music.ui.nowplaying

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.SHIFT_STEP_MS
import com.spotifry.music.data.Settings
import com.spotifry.music.data.nudgeShift
import com.spotifry.music.data.shiftLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsShiftTest {
    @Test fun nudgesByATenthAndStopsAtFiveSecondsEitherWay() {
        assertEquals(100, nudgeShift(0, SHIFT_STEP_MS))
        assertEquals(-100, nudgeShift(0, -SHIFT_STEP_MS))
        assertEquals(5_000, nudgeShift(4_950, SHIFT_STEP_MS))
        assertEquals(-5_000, nudgeShift(-5_000, -SHIFT_STEP_MS))
    }

    @Test fun labelSaysWhichWayAndHowFar() {
        assertEquals("Lyrics on time", shiftLabel(0))
        assertEquals("Lyrics 0.3 s earlier", shiftLabel(300))
        assertEquals("Lyrics 1.2 s later", shiftLabel(-1_200))
    }

    @Test fun keptPerSongAcrossARestartAndResetForgetsIt() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lyrics-shift", 0)
        prefs.edit().putBoolean(Settings.DATA_SAVER, true).commit()
        Settings(prefs).apply { setLyricsShift("a", 400); setLyricsShift("b", -9_000) }
        val again = Settings(prefs)
        assertEquals(mapOf("a" to 400, "b" to -5_000), again.lyricsShift.value)
        again.setLyricsShift("a", 0)
        assertEquals(mapOf("b" to -5_000), Settings(prefs).lyricsShift.value)
        assertFalse(prefs.contains(Settings.LYRICS_SHIFT + "a"))
        // other choices are left alone
        assertEquals(true, Settings(prefs).dataSaver.value)
    }
}
