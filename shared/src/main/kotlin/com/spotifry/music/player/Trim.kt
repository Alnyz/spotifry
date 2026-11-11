package com.spotifry.music.player

import com.spotifry.music.data.Prefs


/** Where a song plays from and to, in ms of the whole song; [endMs] null means to its natural end. */
data class Trim(val startMs: Long = 0, val endMs: Long? = null) {
    /** Nothing left out: the same as no trim. */
    val isWhole get() = startMs <= 0 && endMs == null

    /** Length of the part that plays, for a song [lengthMs] long. */
    fun keptMs(lengthMs: Long) = (endMs ?: lengthMs) - startMs

    /** Start moved to [ms]; always at least [MIN_KEPT_MS] before the end. */
    fun withStart(ms: Long, lengthMs: Long) = copy(startMs = ms.coerceIn(0, maxOf(0, (endMs ?: lengthMs) - MIN_KEPT_MS)))

    /** End moved to [ms]; at least [MIN_KEPT_MS] after the start, and at the song's own end it is no end at all. */
    fun withEnd(ms: Long, lengthMs: Long): Trim {
        val end = ms.coerceAtLeast(startMs + MIN_KEPT_MS)
        return copy(endMs = if (lengthMs > 0 && end >= lengthMs) null else end)
    }

    /** "plays 0:15 to 3:40" for the menu. */
    fun note(lengthMs: Long) = "plays ${clipText(startMs)} to ${clipText(endMs ?: lengthMs)}"

    companion object {
        /** Shorter than this and a stray tap could make a song all but vanish. */
        const val MIN_KEPT_MS = 10_000L
    }
}

/** "Start this song here" at [atMs] is only offered while it leaves at least [Trim.MIN_KEPT_MS] to play. */
fun canStartAt(atMs: Long, lengthMs: Long) = lengthMs <= 0 || lengthMs - atMs >= Trim.MIN_KEPT_MS

/** "End this song here" at [atMs] likewise needs [Trim.MIN_KEPT_MS] before it. */
fun canEndAt(atMs: Long) = atMs >= Trim.MIN_KEPT_MS

/** 0:15, 3:40, 1:02:05. */
fun clipText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Each song's trim, kept on this phone by song id. Nothing is cut from the files; sharing never carries it. */
class Trims(private val prefs: Prefs) {
    operator fun get(id: String): Trim? {
        val parts = prefs.getString(id, null)?.split(',') ?: return null
        val start = parts[0].toLongOrNull() ?: return null
        return Trim(start, parts.getOrNull(1)?.toLongOrNull()).takeUnless { it.isWhole }
    }

    operator fun set(id: String, trim: Trim?) {
        if (trim == null || trim.isWhole) prefs.edit().remove(id).apply()
        else prefs.edit().putString(id, "${trim.startMs},${trim.endMs ?: ""}").apply()
    }
}
