package com.spotifry.music.data

import java.text.BreakIterator

const val NAME_MAX = 20

/**
 * A name as it is kept: line breaks and tabs become spaces, other invisible control characters go, runs of spaces become one,
 * the ends are trimmed, and it is cut at [NAME_MAX] characters as a person counts them (an emoji or a letter with its accent is one).
 */
fun cleanName(raw: String): String {
    val spaced = raw.replace(Regex("[\\r\\n\\t]"), " ").filter { it.category != CharCategory.CONTROL }
    return cutName(spaced.replace(Regex(" {2,}"), " ").trim()).trim()
}

/** [s] cut to [NAME_MAX] characters, never in the middle of one. */
fun cutName(s: String): String {
    val marks = BreakIterator.getCharacterInstance().apply { setText(s) }
    var end = marks.first()
    repeat(NAME_MAX) { val n = marks.next(); if (n == BreakIterator.DONE) return s; end = n }
    return s.substring(0, end)
}
