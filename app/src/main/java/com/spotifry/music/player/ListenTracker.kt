package com.spotifry.music.player

import com.spotifry.music.data.db.ListenEntity

/** Heard this long, a listen is a play and the song counts as heard; the same mark [PlayCounter] uses. */
const val LISTEN_MS = 30_000L
/** How much of a playing song an app killed mid-song can lose. */
private const val WRITE_EVERY_MS = 10_000L
/** A gap between ticks while sound never stopped is believed up to this: a longer one isn't music heard. */
private const val LONGEST_GAP_MS = 5_000L
/** Sound that only started since the last tick played for at most the time between two ticks. */
private const val ONE_TICK_MS = 1_000L

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
    private var movedAt = 0L
    private var heardMs = 0L
    private var startedAt = 0L
    private var written = false
    private var writtenMs = 0L
    private var wasSounding = false

    /**
     * The player moved to [songId], also when that's the same song again: the service leaves out a song being replaced
     * under itself. [byListener]: moved by a hand, not by the song ending. Only that, before [LISTEN_MS], is a skip.
     */
    fun moved(songId: String?, byListener: Boolean): ListenWrite? {
        val skipped = byListener && songId != id && heardMs < LISTEN_MS
        val last = if (written) ListenWrite.Heard(startedAt, seconds(), skipped) else null
        id = songId; movedAt = now(); heardMs = 0; written = false; writtenMs = 0
        return last
    }

    /**
     * About once a second. [songId] is the song being heard, which through a crossfade is still the old one. [elapsedMs]
     * is the time since the last call. When it changes here, nothing was skipped: the old song played out.
     */
    fun tick(songId: String?, sounding: Boolean, elapsedMs: Long): List<ListenWrite> {
        val credit = if (!sounding) 0 else minOf(elapsedMs, if (wasSounding) LONGEST_GAP_MS else ONE_TICK_MS)
        wasSounding = sounding
        if (songId != id) {
            // what passed since the last tick belonged to the old song
            val last = id?.let { add(it, credit) }
            return listOfNotNull(last, moved(songId, byListener = false))
        }
        if (songId == null) return emptyList()
        if (!sounding) return if (written && heardMs != writtenMs) listOf(heard()) else emptyList()
        // the phone's clock can be set back mid-song: then the time since the move says nothing, and must never subtract
        val sinceMove = now() - movedAt
        return listOfNotNull(add(songId, if (sinceMove < 0) credit else minOf(credit, sinceMove)))
    }

    private fun add(song: String, ms: Long): ListenWrite? {
        heardMs += ms
        if (heardMs < 1_000) return null
        if (!written) {
            written = true; writtenMs = heardMs
            startedAt = now() - heardMs
            return ListenWrite.New(ListenEntity(startedAt, song, seconds()))
        }
        return if (heardMs - writtenMs >= WRITE_EVERY_MS) heard() else null
    }

    private fun heard(): ListenWrite { writtenMs = heardMs; return ListenWrite.Heard(startedAt, seconds(), false) }
    private fun seconds() = (heardMs / 1000).toInt()
}

/** Whether a move to another song came from the listener: not a song ending or repeating, and not stepping past a failure. */
fun movedByListener(reason: Int, afterFailure: Boolean) =
    !afterFailure && reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
