package com.spotifry.desktop

import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.LyricWord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMotionTest {
    private val word = LyricWord(1_000, 1_400, "hello ")

    @Test fun sweepIsZeroAtStartHalfInTheMiddleAndFullAtEnd() {
        assertEquals(0f, sweepFraction(1_000, word), 0f)
        assertEquals(0f, sweepFraction(900, word), 0f)
        assertEquals(.5f, sweepFraction(1_200, word), 1e-6f)
        assertEquals(1f, sweepFraction(1_400, word), 0f)
        assertEquals(1f, sweepFraction(2_000, word), 0f)
    }

    @Test fun aWordWithoutAnEndTimeSwitchesOnAtItsStartInsteadOfGuessing() {
        val w = LyricWord(1_000, 1_000, "x ")
        assertEquals(0f, sweepFraction(999, w), 0f)
        assertEquals(1f, sweepFraction(1_000, w), 0f)
    }

    @Test fun aLineWithoutWordTimesHasNoWordOffsetsToSweepOn() {
        assertNull(wordOffsets(LyricLine(0, "just a line")))
        val timed = LyricLine(0, "hello world", listOf(LyricWord(0, 1, "hello "), LyricWord(1, 2, "world")))
        assertArrayEquals(intArrayOf(0, 6, 11), wordOffsets(timed))
    }

    @Test fun aLineWithoutWordTimesLightsWholeAndSoftly() {
        val line = LyricLine(5_000, "no words timed")
        assertEquals(0f, lineLight(4_999, line), 0f)
        val mid = lineLight(5_000 + LINE_LIGHT_MS / 2, line)
        assertTrue(mid > 0f && mid < 1f)
        assertEquals(1f, lineLight(5_000 + LINE_LIGHT_MS, line), 0f)
    }

    @Test fun riseIsUpWhileSungAndSettlesAfter() {
        assertEquals(0f, riseFraction(900, word), 0f)
        assertEquals(1f, riseFraction(1_300 + 100, word), 0f)
        assertEquals(0f, riseFraction(1_400 + SETTLE_MS, word), 0f)
        assertTrue(riseFraction(1_400 + SETTLE_MS / 2, word) in 0.01f..0.99f)
    }

    @Test fun nearbyChangesChainButFarOnesAndTheFirstJump() {
        assertTrue(chains(4, 5))
        assertTrue(chains(5, 4))
        assertTrue(chains(4, 7))
        assertFalse(chains(4, 8))
        assertFalse(chains(4, 44))
        assertFalse(chains(-1, 0))
        assertFalse(chains(4, 4))
    }

    @Test fun lowerLinesWaitLongerGoingForwardAndUpperLinesWaitLongerGoingBack() {
        val fwd = (3..9).map { chainDelayMs(it, 4, 5) }
        assertEquals(0L, fwd[0]); assertEquals(0L, fwd[1])
        assertEquals(listOf(STAGGER_MS, 2 * STAGGER_MS, 3 * STAGGER_MS), fwd.subList(2, 5))
        assertEquals(CHAIN_LINES * STAGGER_MS, chainDelayMs(40, 4, 5))
        val back = (1..6).map { chainDelayMs(it, 5, 4) }
        assertTrue(back[0] >= back[1] && back[4] == 0L && back[5] == 0L)
        assertEquals(0L, chainDelayMs(5, 5, 4))
    }

    @Test fun focusMovesSmoothlyAndNeverJumpsBetweenLines() {
        val starts = longArrayOf(1_000, 3_000, 5_000)
        assertEquals(-1f, focusPosition(starts, 0), 0f)
        assertEquals(-1f, focusPosition(starts, 1_000 - FOCUS_LEAD_MS), 0f)
        assertEquals(0f, focusPosition(starts, 1_000 + FOCUS_MS - FOCUS_LEAD_MS), 1e-6f)
        assertEquals(0f, focusPosition(starts, 2_000), 0f)
        assertEquals(2f, focusPosition(starts, 99_000), 0f)
        var last = focusPosition(starts, 0)
        for (p in 0L..6_000L step 5) { val c = focusPosition(starts, p); assertTrue("p=$p", c >= last && c - last < .05f); last = c }
    }

    @Test fun closeLinesDoNotMakeTheFocusJump() {
        val starts = longArrayOf(1_000, 1_100, 1_200)
        var last = focusPosition(starts, 0)
        for (p in 0L..3_000L) { val c = focusPosition(starts, p); assertTrue(c >= last && c - last < .02f); last = c }
        assertEquals(2f, last, 0f)
    }

    @Test fun depthFollowsTheMeasuredBrightness() {
        assertEquals(200 / 255f, unlitAlpha(0f), 1e-6f)
        assertEquals(178 / 255f, unlitAlpha(1f), 1e-6f)
        assertEquals(140 / 255f, unlitAlpha(2f), 1e-6f)
        assertEquals(105 / 255f, unlitAlpha(3f), 1e-6f)
        assertEquals(130 / 255f, unlitAlpha(-1f), 1e-6f)
        assertEquals(1f, litAlpha(0f), 0f)
        assertEquals(1.1f, focusScale(0f), 1e-6f)
        assertEquals(1f, focusScale(2f), 0f)
        assertEquals(0f, blurDp(0f), 0f)
        assertTrue(blurDp(3f) > blurDp(1f))
    }

    @Test fun syllablesOfOneWordLiftTogether() {
        val ws = listOf(LyricWord(0, 1, "e"), LyricWord(1, 2, "sok "), LyricWord(2, 3, "pagi"))
        assertEquals(listOf(0..1, 0..1, 2..2), wholeWords(ws))
    }
}
