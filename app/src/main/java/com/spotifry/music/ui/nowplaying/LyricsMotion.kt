package com.spotifry.music.ui.nowplaying

import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.LyricWord
import kotlin.math.abs

// All the timing of the full lyrics page, as plain maths so it can be tested and drawn from one clock.
// Numbers are measured from a reference recording (about 60 fps) and tuned by eye.

/** A line starts growing and sharpening this long before it is sung... */
internal const val FOCUS_LEAD_MS = 240L
/** ...and takes this long to get there. */
internal const val FOCUS_MS = 500L
/** The scroll waits this long after the line starts, so the first letter is lit before the page moves. */
internal const val SCROLL_DELAY_MS = 100L
internal const val TRAVEL_MS = 860
/** Each lower line starts moving this long after the one above it. */
internal const val STAGGER_MS = 95L
/** Lines further than this from the one that moves first don't wait any longer. */
internal const val CHAIN_LINES = 6
/** A change of more than this many lines (a seek, a skip) jumps instead of chaining. */
internal const val CHAIN_MAX_JUMP = 3
/** A lit word stays raised this long after it ends, then settles. */
internal const val SETTLE_MS = 500L
internal const val RISE_IN_MS = 250L
/** A line without word times lights over this long. */
internal const val LINE_LIGHT_MS = 300L

internal fun smooth01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/**
 * Where the page's focus is, in lines: -1 before the first line, then moving to 0, 1, 2… as each line's turn comes up.
 * Each line takes its step over [FOCUS_MS] starting [FOCUS_LEAD_MS] before it is sung, so the number never jumps and
 * the next line has grown a little by the time it is lit. [starts] are the lines' start times, ascending.
 */
internal fun focusPosition(starts: LongArray, pos: Long): Float {
    val tail = FOCUS_MS - FOCUS_LEAD_MS
    // first line whose step isn't finished
    var lo = 0; var hi = starts.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (starts[mid] + tail > pos) hi = mid else lo = mid + 1 }
    var c = lo - 1f
    var k = lo
    while (k < starts.size && starts[k] - FOCUS_LEAD_MS < pos) { c += smooth01((pos - (starts[k] - FOCUS_LEAD_MS)).toFloat() / FOCUS_MS); k++ }
    return c
}

/** How far the light has swept through a word: 0 before it starts, 1 at its end. A word with no end time just switches on. */
internal fun sweepFraction(pos: Long, w: LyricWord): Float = when {
    pos < w.startMs -> 0f
    w.endMs <= w.startMs -> 1f
    else -> ((pos - w.startMs).toFloat() / (w.endMs - w.startMs)).coerceIn(0f, 1f)
}

/** 0..1: the lit word lifts as it starts, stays up while sung and settles [SETTLE_MS] after it ends. */
internal fun riseFraction(pos: Long, w: LyricWord): Float {
    if (pos < w.startMs) return 0f
    val up = smooth01((pos - w.startMs).toFloat() / RISE_IN_MS)
    val down = smooth01((pos - maxOf(w.endMs, w.startMs)).toFloat() / SETTLE_MS)
    return up * (1 - down)
}

/**
 * For each word, the range of words that make up the whole word it belongs to: a word the lyrics site split into
 * syllables ("e" + "sok") has no space after its first parts. A lit word lifts as one, so its syllables stay level.
 */
internal fun wholeWords(words: List<LyricWord>): List<IntRange> {
    val out = ArrayList<IntRange>(words.size)
    var from = 0
    words.forEachIndexed { k, w ->
        if (w.text.endsWith(' ') || k == words.lastIndex) { repeat(k - from + 1) { out.add(from..k) }; from = k + 1 }
    }
    return out
}

/** A line with no word times lights as a whole, softly, never word by word. */
internal fun lineLight(pos: Long, line: LyricLine): Float = smooth01((pos - line.atMs).toFloat() / LINE_LIGHT_MS)

/** Whether a change from line [prev] to [now] scrolls as a chain; anything bigger (or the first) just jumps. */
internal fun chains(prev: Int, now: Int): Boolean = prev >= 0 && abs(now - prev) in 1..CHAIN_MAX_JUMP

/** How long line [i] waits before it moves when the page goes from line [prev] to [now]: the leading lines go first. */
internal fun chainDelayMs(i: Int, prev: Int, now: Int): Long {
    val behind = if (now >= prev) i - prev else prev - i
    return behind.coerceIn(0, CHAIN_LINES) * STAGGER_MS
}

private fun table(d: Float, xs: FloatArray, ys: FloatArray): Float {
    if (d <= xs.first()) return ys.first()
    for (k in 1 until xs.size) if (d <= xs[k]) return ys[k - 1] + (ys[k] - ys[k - 1]) * (d - xs[k - 1]) / (xs[k] - xs[k - 1])
    return ys.last()
}

private val AheadAt = floatArrayOf(0f, 1f, 2f, 3f, 4f)
private val AheadBright = floatArrayOf(200 / 255f, 178 / 255f, 140 / 255f, 105 / 255f, 90 / 255f)
private val PastAt = floatArrayOf(-3f, -2f, -1f, 0f)
private val PastUnlit = floatArrayOf(90 / 255f, 105 / 255f, 130 / 255f, 200 / 255f)
private val PastLit = floatArrayOf(90 / 255f, 105 / 255f, 130 / 255f, 1f)

/** Brightness of text not yet sung, [d] lines away from the focus (negative: the line is behind it). */
internal fun unlitAlpha(d: Float): Float = if (d >= 0) table(d, AheadAt, AheadBright) else table(d, PastAt, PastUnlit)

/** Brightness of sung text: full on the focus line, dimming as it falls behind. */
internal fun litAlpha(d: Float): Float = if (d >= 0) 1f else table(d, PastAt, PastLit)

/** The focus line is 10% bigger; it eases back as the focus moves on. */
internal fun focusScale(d: Float): Float = 1f + .1f * (1f - abs(d).coerceAtMost(1f))

/** Blur in dp: none on the focus line, more the further away. */
internal fun blurDp(d: Float): Float = abs(d).coerceAtMost(4f) * .9f

/**
 * Where each word starts in the line's text (one extra entry at the end), or null when the words don't spell the line
 * and the sweep can't be placed on it.
 */
internal fun wordOffsets(line: LyricLine): IntArray? {
    if (line.words.isEmpty()) return null
    val full = line.words.joinToString("") { it.text }
    val lead = full.length - full.trimStart().length
    if (full.trim() != line.text) return null
    val out = IntArray(line.words.size + 1)
    var at = 0
    line.words.forEachIndexed { k, w -> out[k] = (at - lead).coerceIn(0, line.text.length); at += w.text.length }
    out[line.words.size] = line.text.length
    return out
}
