package com.spotifry.music.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The last warnings and crashes, kept in one small file on this device so they can be pasted into a bug report
 * (About > Copy error log). Nothing here is ever sent anywhere: the person copies it themselves.
 */
class ErrorLog(
    private val file: File,
    private val maxBytes: Int = 64 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Adds one entry. When the file outgrows [maxBytes], the older half goes, cut at a line, so it stays small. */
    @Synchronized fun add(tag: String, message: String) {
        runCatching {
            val stamp = STAMP.format(Instant.ofEpochMilli(now()).atZone(zone))
            file.parentFile?.mkdirs()
            file.appendText("$stamp $tag: ${message.trimEnd()}\n")
            if (file.length() > maxBytes) {
                val text = file.readText()
                val keep = text.substring(text.length - maxBytes / 2)
                file.writeText(keep.substringAfter('\n'))
            }
        }
    }

    /** Everything kept, oldest first; empty when nothing went wrong yet. */
    @Synchronized fun text(): String = runCatching { file.readText() }.getOrDefault("")

    @Synchronized fun clear() { file.delete() }

    /**
     * What gets copied: the versions a bug report needs, then the log. [system] is the phone or computer and its
     * Android version or operating system.
     */
    fun report(app: String, newPipe: String, system: String): String =
        "Spotifry $app · NewPipe Extractor $newPipe · $system\n\n" + text().ifEmpty { "Nothing logged yet.\n" }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
