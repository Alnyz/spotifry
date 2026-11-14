package com.spotifry.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

class MpvMissingTest {
    @Test fun aMissingMpvIsSaidClearly() {
        val e = runCatching { Mpv(binary = "no-such-player-installed") { _, _ -> } }.exceptionOrNull()
        assertTrue(e.toString(), e is MpvMissing)
    }
}
