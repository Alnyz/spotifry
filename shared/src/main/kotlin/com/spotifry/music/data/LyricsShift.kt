package com.spotifry.music.data

import kotlin.math.abs

const val SHIFT_STEP_MS = 100

/** [ms] moved by [by], kept within [com.spotifry.music.data.Settings.LYRICS_SHIFT_MAX] either way. */
fun nudgeShift(ms: Int, by: Int): Int {
    val max = com.spotifry.music.data.Settings.LYRICS_SHIFT_MAX
    return (ms + by).coerceIn(-max, max)
}

/** "Lyrics on time", "Lyrics 0.3 s earlier", "Lyrics 1.2 s later". */
fun shiftLabel(ms: Int): String =
    if (ms == 0) "Lyrics on time" else "Lyrics %.1f s %s".format(java.util.Locale.US, abs(ms) / 1000f, if (ms > 0) "earlier" else "later")
