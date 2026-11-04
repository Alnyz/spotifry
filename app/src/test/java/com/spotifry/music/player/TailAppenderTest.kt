package com.spotifry.music.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailAppenderTest {
    private val off = Player.REPEAT_MODE_OFF

    @Test fun falseInRepeatAll() = assertFalse(shouldAppend(8, 10, Player.REPEAT_MODE_ALL, null, "z"))
    @Test fun falseInRepeatOne() = assertFalse(shouldAppend(8, 10, Player.REPEAT_MODE_ONE, null, "z"))
    @Test fun falseAtStartOfLongQueue() = assertFalse(shouldAppend(0, 10, off, null, "z"))
    @Test fun trueNearTailWithRepeatOff() = assertTrue(shouldAppend(8, 10, off, null, "z"))
    @Test fun falseWhenAlreadyRequestedForThisTail() = assertFalse(shouldAppend(8, 10, off, "z", "z"))
    @Test fun falseOnEmptyQueue() = assertFalse(shouldAppend(0, 0, off, null, ""))
}
