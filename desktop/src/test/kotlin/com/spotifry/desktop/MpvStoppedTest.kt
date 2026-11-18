package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvStoppedTest {
    @Test fun aWindowsCrashSaysTheCodeTheCausesThePathAndMpvsWords() {
        val text = mpvStopped("C:\\Program Files\\Spotifry\\app\\resources\\mpv.exe", 0xC000001D.toInt(), listOf("line 1", "line 2"), windows = true)
        assertTrue(text, text.startsWith("mpv stopped at start (exit code -1073741795, 0xC000001D). This PC's processor can't run this mpv."))
        assertTrue(text, "antivirus" in text && "C:\\Program Files\\Spotifry\\app\\resources\\mpv.exe" in text)
        assertTrue(text, text.endsWith("mpv said:\nline 1\nline 2"))
    }

    @Test fun elsewhereItIsJustTheCodeAndWhatMpvSaid() {
        assertEquals("mpv stopped at start (exit code 1).\n\nmpv said:\nError parsing option x", mpvStopped("mpv", 1, listOf("Error parsing option x"), windows = false))
    }

    /** A real mpv refusing to start (an option it doesn't know): its own words reach the error, after one more try. */
    @Test fun aRealMpvThatStopsAtStartSaysWhy() {
        val e = runCatching { Mpv(listOf("--no-such-option-here")) { _, _ -> } }.exceptionOrNull()
        assertTrue(e.toString(), e is IllegalStateException && "no-such-option-here" in e.message!!)
    }
}
