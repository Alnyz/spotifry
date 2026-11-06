package com.spotifry.music.player

import com.spotifry.music.data.db.ListenEntity

/** Heard this long, a listen is a play and the song counts as heard; the same mark [PlayCounter] uses. */
const val LISTEN_MS = 30_000L
/** How much of a playing song an app killed mid-song can lose. */
private const val WRITE_EVERY_MS = 10_000L
/** A song this short ends before it could be told from a skip, also when a crossfade takes its last seconds. */
private const val MIN_SKIPPABLE_SEC = 45L

sealed interface ListenWrite {
    data class New(val listen: ListenEntity) : ListenWrite
    data class Heard(val startedAt: Long, val seconds: Int, val skipped: Boolean) : ListenWrite
}

/**
 * Turns what the player does into log lines. A line starts when sound starts, so a song that fails or is passed in under a
 * second leaves none. Fed only from the service's main thread. Nothing here looks at the position: it counts real time.
 */
class ListenTracker(private val now: () -> Long = System::currentTimeMillis) {
    private var id: String? = null
    private var lengthSec = 0L
    private var heardMs = 0L
    private var startedAt = 0L
    private var written = false
    private var writtenMs = 0L

    /** The player moved to [songId]. Replacing the song under itself (a new trim) isn't a move, a repeat ([again]) is. */
    fun moved(songId: String?, durationSec: Long, again: Boolean, byListener: Boolean): ListenWrite? {
        if (songId != null && songId == id && !again) return null
        val skipped = byListener && heardMs < LISTEN_MS && lengthSec > MIN_SKIPPABLE_SEC
        val last = if (written) ListenWrite.Heard(startedAt, seconds(), skipped) else null
        id = songId; lengthSec = durationSec; heardMs = 0; written = false; writtenMs = 0
        return last
    }

    /** About once a second. [elapsedMs] is the time since the last call; it counts if [sounding]. */
    fun tick(songId: String?, sounding: Boolean, elapsedMs: Long, durationSec: () -> Long): ListenWrite? {
        // the service came up with a song already in place
        val missed = if (songId != id) moved(songId, durationSec(), again = false, byListener = false) else null
        if (missed != null) return missed
        if (songId == null) return null
        if (!sounding) return if (written && heardMs != writtenMs) heard() else null
        heardMs += elapsedMs
        if (heardMs < 1_000) return null
        if (!written) {
            written = true; writtenMs = heardMs
            startedAt = now() - heardMs
            return ListenWrite.New(ListenEntity(startedAt, songId, seconds()))
        }
        return if (heardMs - writtenMs >= WRITE_EVERY_MS) heard() else null
    }

    private fun heard(): ListenWrite { writtenMs = heardMs; return ListenWrite.Heard(startedAt, seconds(), false) }
    private fun seconds() = (heardMs / 1000).toInt()
}
