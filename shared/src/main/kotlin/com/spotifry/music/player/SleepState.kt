package com.spotifry.music.player

sealed interface SleepState {
    data object Off : SleepState
    /** [endsAt] is on the timer's own clock (on the phone one that keeps counting while it sleeps). */
    data class Minutes(val minutes: Int, val endsAt: Long) : SleepState
    data object EndOfSong : SleepState
}

/** Volume over the last [FADE_MS] before the music stops: full until then, down to silent at the end. */
fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

/** "28:41", or "1:00:00" for an hour or more. */
fun clockText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

const val FADE_MS = 10_000L
