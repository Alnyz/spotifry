package com.spotifry.music.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    @Test fun rejectedLinkIsRetriedOncePerSong() {
        val r = OneRetryPerSong()
        assertTrue(r.allow("a"))
        assertFalse(r.allow("a"))
        assertTrue(r.allow("b"))
    }

    @Test fun successGivesTheSongAnotherRetry() {
        val r = OneRetryPerSong()
        r.allow("a")
        r.reset("a")
        assertTrue(r.allow("a"))
    }

    @Test fun idlePlayerMustBePreparedBeforePlay() {
        assertTrue(needsPrepare(Player.STATE_IDLE))
        assertFalse(needsPrepare(Player.STATE_READY))
        assertFalse(needsPrepare(Player.STATE_BUFFERING))
    }
}
