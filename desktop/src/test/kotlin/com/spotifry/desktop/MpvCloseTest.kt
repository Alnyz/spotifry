package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvCloseTest {
    /** Closing a player (the second one, after a crossfade) breaks its socket on purpose: nothing goes to the log. */
    @Test fun closingAPlayerIsQuiet() {
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        try {
            repeat(5) { Mpv(listOf("--ao=null")) { _, _ -> }.apply { repeat(50) { command("get_property", "volume") }; observe(1, "volume") }.close() }
            Thread.sleep(1000)
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
        assertEquals(emptyList<Throwable>(), errors.toList())
    }
}
