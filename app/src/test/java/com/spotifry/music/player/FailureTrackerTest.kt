package com.spotifry.music.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureTrackerTest {
    @Test fun keepsSkippingUntilEverySongHasFailed() {
        val t = FailureTracker()
        assertTrue(t.onFailure(queueSize = 3))
        assertTrue(t.onFailure(queueSize = 3))
        assertFalse(t.onFailure(queueSize = 3)) // all 3 failed in a row: stop, no endless loop
    }

    @Test fun successResetsTheCount() {
        val t = FailureTracker()
        t.onFailure(3); t.onFailure(3)
        t.onSuccess()
        assertTrue(t.onFailure(3))
    }

    @Test fun singleSongThatFailsStopsImmediately() = assertFalse(FailureTracker().onFailure(1))
}
