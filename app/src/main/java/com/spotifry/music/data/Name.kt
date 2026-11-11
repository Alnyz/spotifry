package com.spotifry.music.data

import java.text.BreakIterator

const val NAME_MAX = 20

/** Zero-width (but not joiners U+200C and U+200D, which emoji and Persian names need), bidi-control and byte-order-mark characters: they show nothing, so a name made of them is empty. */
private val INVISIBLE = ('\u200B'..'\u200F').toSet() - '\u200C' - '\u200D' + ('\u202A'..'\u202E') + ('\u2060'..'\u2064') + ('\u2066'..'\u2069') + '\uFEFF'

/**
 * A name as it is kept: line breaks and tabs become spaces, other invisible control characters go, runs of spaces become one,
 * the ends are trimmed, and it is cut at [NAME_MAX] characters as a person counts them (an emoji or a letter with its accent is one).
 */
fun cleanName(raw: String): String {
    val spaced = raw.replace(Regex("[\\r\\n\\t]"), " ").filter { it.category != CharCategory.CONTROL && it !in INVISIBLE }
    return cutName(spaced.replace(Regex(" {2,}"), " ").trim()).trim()
}

/** [s] cut to [NAME_MAX] characters, never in the middle of one. */
fun cutName(s: String): String {
    val marks = BreakIterator.getCharacterInstance().apply { setText(s) }
    var end = marks.first()
    repeat(NAME_MAX) { val n = marks.next(); if (n == BreakIterator.DONE) return s; end = n }
    return s.substring(0, end)
}
