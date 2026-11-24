package com.spotifry.music.data

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogTest {
    private fun log(maxBytes: Int = 64 * 1024): ErrorLog {
        val file = File(Files.createTempDirectory("errorlog").toFile(), "error_log.txt")
        return ErrorLog(file, maxBytes, now = { 1_763_985_600_000 }, zone = ZoneOffset.UTC)
    }

    @Test fun keepsEntriesInOrderWithTheTime() {
        val l = log()
        l.add("player", "Source error")
        l.add("crash", "java.lang.IllegalStateException: boom\n\tat x.y(Z.kt:1)\n")
        assertEquals(
            "2025-11-24 12:00:00 player: Source error\n2025-11-24 12:00:00 crash: java.lang.IllegalStateException: boom\n\tat x.y(Z.kt:1)\n",
            l.text(),
        )
    }

    @Test fun staysSmallAndDropsTheOldestFirst() {
        val l = log(maxBytes = 1_000)
        repeat(100) { l.add("tag", "entry $it") }
        val text = l.text()
        assertTrue(text.length <= 1_000)
        assertTrue(text.endsWith("tag: entry 99\n"))
        assertTrue("entry 0\n" !in text)
        // it starts on a whole line, never half way through one
        assertTrue(text.startsWith("2025-11-24 12:00:00 tag: entry "))
    }

    @Test fun reportStartsWithTheVersions() {
        val l = log()
        assertEquals("Spotifry 1.3.0 · NewPipe Extractor v0.24 · Pixel 7, Android 15\n\nNothing logged yet.\n", l.report("1.3.0", "v0.24", "Pixel 7, Android 15"))
        l.add("player", "failed")
        assertTrue(l.report("1.3.0", "v0.24", "Pixel 7, Android 15").endsWith("player: failed\n"))
    }

    @Test fun clearEmptiesIt() {
        val l = log()
        l.add("player", "failed")
        l.clear()
        assertEquals("", l.text())
    }
}
