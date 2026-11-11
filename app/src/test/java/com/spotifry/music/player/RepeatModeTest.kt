package com.spotifry.music.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatModeTest {
    /** The shared numbers are handed straight to Media3, so they must be its numbers. */
    @Test fun matchMedia3() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.OFF)
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.ONE)
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.ALL)
    }
}
