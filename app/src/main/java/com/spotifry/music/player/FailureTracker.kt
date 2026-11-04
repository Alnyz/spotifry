package com.spotifry.music.player

/** Stops the player from skipping forever when every song in the queue fails (for example offline). */
class FailureTracker {
    private var consecutive = 0

    /** Returns true if the player should try the next song, false if it should stop. */
    fun onFailure(queueSize: Int): Boolean {
        consecutive++
        return consecutive < queueSize
    }

    fun onSuccess() { consecutive = 0 }
}
