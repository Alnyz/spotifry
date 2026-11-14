package com.spotifry.music.player

/**
 * Volume for the fade between songs: the last [fadeMs] of a song go down to silent and, when the song began by
 * running on from the one before ([fadeIn]), its first [fadeMs] come up from silent. 1 everywhere else.
 */
fun fadeGain(positionMs: Long, durationMs: Long, fadeMs: Long, fadeIn: Boolean): Float {
    if (fadeMs <= 0) return 1f
    val out = if (durationMs > 0) (durationMs - positionMs).toFloat() / fadeMs else 1f
    val inn = if (fadeIn) positionMs.toFloat() / fadeMs else 1f
    return minOf(out, inn).coerceIn(0f, 1f)
}
