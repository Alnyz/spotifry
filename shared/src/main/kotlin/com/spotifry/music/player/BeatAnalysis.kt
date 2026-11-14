// The onset measure, tempo search and the idea of a grid fitted over nearby beats are adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.

package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val ANALYSIS_RATE = 22_050
private const val FRAME = 1_024
private const val HOP = 256
private const val FPS = ANALYSIS_RATE.toFloat() / HOP
/** Below this a grid isn't trusted: noise and music without a pulse sit near 0. */
private const val SURE = .35f

/**
 * The beats of a stretch of song: beat n falls at [firstBeatMs] + n * [periodMs]. [soundFromMs] and [soundToMs] are where
 * sound starts and stops in that stretch, so a silent tail isn't counted as music.
 */
data class BeatGrid(val firstBeatMs: Double, val periodMs: Double, val confidence: Float, val soundFromMs: Long, val soundToMs: Long, val whole: Boolean = true,
    /** How loud the last seconds of sound are against the rest of the stretch: near 1 for a song that ends loud, near 0 for one fading out. */
    val endLevel: Float = 1f) {
    val bpm get() = 60_000.0 / periodMs
    val isSure get() = confidence >= SURE
    fun beatAtOrAfter(ms: Double) = firstBeatMs + ceil((ms - firstBeatMs) / periodMs) * periodMs
    fun beatAtOrBefore(ms: Double) = firstBeatMs + floor((ms - firstBeatMs) / periodMs) * periodMs
    /** The same beats counted from somewhere else, such as a trim's start. */
    fun shifted(byMs: Long) = copy(firstBeatMs = firstBeatMs + byMs, soundFromMs = soundFromMs + byMs, soundToMs = soundToMs + byMs)
}

/**
 * Finds tempo and beats in [mono] (at [ANALYSIS_RATE]) that starts at [startMs] of the song. Null when the stretch is
 * too short or silent. Tempos are folded to 78-168 BPM, so a 70 BPM song reads as 140 with its hits on every other beat.
 */
fun analyseBeats(mono: FloatArray, count: Int, startMs: Long): BeatGrid? {
    val frames = (count - FRAME) / HOP
    if (frames < FPS * 8) return null
    val (flux, rms) = measure(mono, frames)
    val loudest = rms.max()
    if (loudest < 1e-3f) return null
    val onset = onsets(flux)
    val (lag, sureness) = tempoLag(onset) ?: return null
    var period = lag
    while (FPS * 60 / period < 78) period /= 2
    while (FPS * 60 / period > 168) period *= 2
    val (first, fitted, steadiness) = fit(onset, period)
    fun ms(f: Double) = (f * HOP + FRAME * .5 + FRAME * ONSET_LAG) * 1_000.0 / ANALYSIS_RATE + startMs
    val quiet = loudest * .0056f
    val from = rms.indexOfFirst { it > quiet }
    val to = rms.indexOfLast { it > quiet }
    val endFrames = (END_WINDOW_MS * ANALYSIS_RATE / 1_000 / HOP).toInt()
    var body = 0.0; var end = 0.0
    for (i in from..to) { body += rms[i]; if (i > to - endFrames) end += rms[i] }
    val endLevel = (end / minOf(endFrames, to - from + 1) / (body / (to - from + 1))).toFloat()
    return BeatGrid(ms(first), fitted * HOP * 1_000.0 / ANALYSIS_RATE, sureness * steadiness, ms(from.toDouble()).toLong(), ms(to + 1.0).toLong(), endLevel = endLevel)
}

/** The last stretch of sound whose level says how the song ends. */
private const val END_WINDOW_MS = 4_000

/** A hit shows most strongly about 3/4 along the window, not at its centre. */
private const val ONSET_LAG = .27

private fun measure(mono: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
    val window = FloatArray(FRAME) { (.5 - .5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
    val re = FloatArray(FRAME); val im = FloatArray(FRAME)
    val last = FloatArray(FRAME / 2 + 1)
    val flux = FloatArray(frames); val rms = FloatArray(frames)
    for (f in 0 until frames) {
        var energy = 0.0
        for (i in 0 until FRAME) { val x = mono[f * HOP + i]; energy += x * x; re[i] = x * window[i]; im[i] = 0f }
        rms[f] = sqrt(energy / FRAME).toFloat()
        Spectrum.fft(re, im)
        var rise = 0f
        for (b in 1..FRAME / 2) {
            // compressed, so a quiet hi-hat still counts next to a loud pad
            val v = ln(1f + 1_000f * sqrt(re[b] * re[b] + im[b] * im[b]))
            if (v > last[b]) rise += (v - last[b]) * bandWeight(b)
            last[b] = v
        }
        flux[f] = rise
    }
    // the first frame rises from nothing: not a hit
    flux[0] = 0f
    return flux to rms
}

/** Kick and bass count most, hi-hats least. */
private fun bandWeight(bin: Int): Float {
    val hz = bin * ANALYSIS_RATE.toFloat() / FRAME
    return if (hz < 160) 1f / 8 else if (hz < 600) .8f / 22 else if (hz < 3_000) .55f / 112 else .2f / 370
}

/** Rises with their local average taken away, so only the jumps remain; 0..1. */
private fun onsets(flux: FloatArray): FloatArray {
    val half = (FPS / 2).roundToInt()
    val sum = DoubleArray(flux.size + 1)
    for (i in flux.indices) sum[i + 1] = sum[i] + flux[i]
    val out = FloatArray(flux.size) { i ->
        val a = (i - half).coerceAtLeast(0); val b = (i + half).coerceAtMost(flux.size)
        (flux[i] - ((sum[b] - sum[a]) / (b - a)).toFloat()).coerceAtLeast(0f)
    }
    val peak = out.max()
    if (peak > 0) for (i in out.indices) out[i] /= peak
    return out
}

/** Beat length in frames from how the onsets repeat, leaning toward tempos around 120, with how clearly it stands out, 0..1. */
private fun tempoLag(onset: FloatArray): Pair<Double, Float>? {
    val minLag = (FPS * 60 / 200).roundToInt()
    val maxLag = minOf((FPS * 60 / 60).roundToInt(), onset.size / 3)
    if (maxLag <= minLag + 2) return null
    val acf = FloatArray(maxLag + 2)
    for (lag in minLag..maxLag + 1) {
        var s = 0.0
        for (i in 0 until onset.size - lag) s += onset[i] * onset[i + lag]
        acf[lag] = (s / (onset.size - lag)).toFloat()
    }
    var best = -1; var bestScore = 0f; var total = 0f
    for (lag in minLag..maxLag) {
        val octaves = ln(FPS * 60 / lag / 120f) / ln(2f) / .9f
        val score = acf[lag] * exp(-.5f * octaves * octaves)
        total += acf[lag]
        if (score > bestScore) { bestScore = score; best = lag }
    }
    if (best <= 0) return null
    val mean = total / (maxLag - minLag + 1)
    if (mean <= 0f) return null
    return (best + peakShift(acf[best - 1], acf[best], acf[best + 1])) to ((acf[best] / mean - 1.2f) / 2.5f).coerceIn(0f, 1f)
}

private fun peakShift(a: Float, b: Float, c: Float): Double {
    val curve = a - 2 * b + c
    return if (curve < 0) (.5 * (a - c) / curve).coerceIn(-.5, .5) else 0.0
}

/**
 * Where the beats fall: the phase that lands on the most onsets, then a straight line through the onsets near each beat,
 * which is far steadier than any one of them. Returns the first beat, the beat length (both in frames) and how well the
 * song keeps to that line, 0..1: a band speeding up scores low.
 */
private fun fit(onset: FloatArray, period0: Double): Triple<Double, Double, Float> {
    var phase = 0.0; var bestSum = -1f
    for (p in 0 until period0.roundToInt().coerceAtLeast(1)) {
        var s = 0f; var t = p.toDouble()
        while (t < onset.size) { s += onset[t.roundToInt().coerceAtMost(onset.size - 1)]; t += period0 }
        if (s > bestSum) { bestSum = s; phase = p.toDouble() }
    }
    var period = period0; var steady = 0f
    repeat(3) {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val reach = (period * .12).coerceAtLeast(2.0)
        var k = 0
        while (phase + k * period < onset.size) {
            val at = phase + k * period
            val from = (at - reach).roundToInt().coerceAtLeast(1); val to = (at + reach).roundToInt().coerceAtMost(onset.size - 2)
            var top = -1
            for (i in from..to) if (onset[i] > .2f && (top < 0 || onset[i] > onset[top])) top = i
            if (top >= 0) { xs += k.toDouble(); ys += top + peakShift(onset[top - 1], onset[top], onset[top + 1]) }
            k++
        }
        if (xs.size < 8) return Triple(phase, period, 0f)
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var sxy = 0.0
        for (i in xs.indices) { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
        val slope = sxy / sxx
        // the line only corrects small errors: a big change means it locked onto something else
        if (abs(slope - period0) > period0 * .03) return Triple(phase, period, 0f)
        period = slope; phase = my - slope * mx
        var miss = 0.0
        for (i in xs.indices) { val e = ys[i] - (phase + xs[i] * period); miss += e * e }
        steady = (1 - sqrt(miss / xs.size) / (period * .08)).toFloat().coerceIn(0f, 1f)
    }
    return Triple(phase, period, steady)
}

/** How one song runs into the next, in each song's own playing time. [startMs] of the old one meets [cueMs] of the new one. */
data class JoinPlan(val startMs: Long, val lengthMs: Long, val cueMs: Long, val onBeat: Boolean, val leadMs: Long,
    /** How much louder (+) or quieter (-) the new song is than the old one, as heard: evened out during the overlap. */
    val matchDb: Float = 0f)

/** How far the two songs' beats may slip apart by the edges of the overlap, since nothing is stretched. */
private const val MAX_SLIP_MS = 70.0
private const val BEAT_JOIN_MIN_MS = 6_000L
private const val BEAT_JOIN_MAX_MS = 16_000L

/**
 * Picks the join, with [out] and [inn] in playing time. The middle of a beat join falls on a beat of both songs, which
 * ends up a whole number of bars from either edge. When unsure, the plain overlap of [fadeMs]; with no fade set, null:
 * the song just runs on as it does without this. [inn] null means the next song isn't ready, also null.
 */
fun planJoin(out: BeatGrid?, inn: BeatGrid?, outMs: Long, inMs: Long, fadeMs: Long, leadMs: Long): JoinPlan? {
    if (inn == null) return null
    val end = minOf(outMs, out?.soundToMs ?: outMs)
    // an old song that already ends quietly needs no long overlap, and no beats to line up
    val fade = fadeFor(out, fadeMs)
    if (fade == fadeMs && out != null && out.isSure && inn.isSure) beatJoin(out, inn, end, inMs, fadeMs, leadMs)?.let { return it }
    return plainJoin(end, outMs, inMs, fade, inn.soundFromMs)
}

private const val QUIET_END = .15f
private const val LOUD_END = .5f
private const val SHORT_FADE_MS = 2_000L

/** [fadeMs] for a song that ends loud, shrinking to a quarter (at least 2 s) as its last seconds get quiet. */
fun fadeFor(out: BeatGrid?, fadeMs: Long): Long {
    if (out == null || fadeMs <= 0 || out.endLevel >= LOUD_END) return fadeMs
    val loud = ((out.endLevel - QUIET_END) / (LOUD_END - QUIET_END)).coerceIn(0f, 1f)
    return (fadeMs * (.25f + .75f * loud)).toLong().coerceAtLeast(SHORT_FADE_MS).coerceAtMost(fadeMs)
}

/** The overlap with no beats to go by, ending at [end] of the old song. Null with no fade set or songs too short for it. */
fun plainJoin(end: Long, outMs: Long, inMs: Long, fadeMs: Long, soundFromMs: Long): JoinPlan? {
    if (fadeMs <= 0 || end - fadeMs < 0 || outMs < 2 * fadeMs || (inMs in 1 until 2 * fadeMs)) return null
    return JoinPlan(end - fadeMs, fadeMs, soundFromMs.takeIf { it <= 10_000 } ?: 0, false, 0)
}

private fun beatJoin(out: BeatGrid, inn: BeatGrid, end: Long, inMs: Long, fadeMs: Long, leadMs: Long): JoinPlan? {
    // the next song's beat as counted against this one: the same, or half or double time
    val inBeat = listOf(1.0, 2.0, .5).map { inn.periodMs / it }.firstOrNull { abs(it / out.periodMs - 1) <= .04 } ?: return null
    val slip = abs(inBeat - out.periodMs)
    val wanted = fadeMs.coerceIn(BEAT_JOIN_MIN_MS, BEAT_JOIN_MAX_MS)
    var bars = (wanted / (out.periodMs * 4)).roundToInt().coerceAtLeast(1)
    // ponytail: nothing is stretched, so tempos a little apart only get a short overlap; a time-stretch would allow long ones
    while (bars > 0 && slip * bars * 2 > MAX_SLIP_MS) bars--
    if (bars == 0) return null
    val length = bars * 4 * out.periodMs
    if (length < wanted / 2) return null
    val start = out.beatAtOrBefore(end - length)
    if (start < out.firstBeatMs) return null
    // the two run on the same clock, so with tempos apart they only meet once: this puts that at the middle
    val shift = bars * 2 * (inBeat - out.periodMs)
    val cue = inn.beatAtOrAfter(maxOf(leadMs, inn.soundFromMs) + abs(shift)) + shift
    if (inMs in 1 until (cue + 2 * length).toLong()) return null
    return JoinPlan(start.roundToInt().toLong(), length.roundToInt().toLong(), cue.roundToInt().toLong(), true, leadMs)
}
