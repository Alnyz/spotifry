package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayCounterTest {
    @Test fun countsOnceAfterThirtySecondsOfPlaying() {
        val c = PlayCounter()
        val hits = (1..40).count { c.tick("a", playing = it !in 10..19, elapsedMs = 1_000) } // 10 s paused in the middle
        assertEquals(1, hits)
        assertEquals(false, (1..29).any { PlayCounter().let { p -> (1..it).map { _ -> p.tick("b", true, 1_000) }.last() } })
    }

    @Test fun newSongOrRestartStartsOver() {
        val c = PlayCounter()
        repeat(29) { c.tick("a", true, 1_000) }
        assertEquals(false, c.tick("b", true, 1_000)) // skipped at 29 s: no count for a
        c.restart("b")
        assertEquals(1, (1..30).count { c.tick("b", true, 1_000) })
    }
}
