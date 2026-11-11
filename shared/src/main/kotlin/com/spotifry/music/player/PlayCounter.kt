package com.spotifry.music.player

/**
 * Counts a play once a song has really been listened to: [thresholdMs] of playing time, paused time not included.
 * Fed a tick every second by the service; a new song or the same song starting over resets it.
 */
class PlayCounter(private val thresholdMs: Long = 30_000) {
    private var id: String? = null
    private var listenedMs = 0L
    private var counted = false

    fun restart(songId: String?) { id = songId; listenedMs = 0; counted = false }

    /** True exactly once per play, at the tick that crosses the threshold. */
    fun tick(songId: String?, playing: Boolean, elapsedMs: Long): Boolean {
        if (songId != id) restart(songId)
        if (songId == null || !playing || counted) return false
        listenedMs += elapsedMs
        if (listenedMs < thresholdMs) return false
        counted = true
        return true
    }
}
