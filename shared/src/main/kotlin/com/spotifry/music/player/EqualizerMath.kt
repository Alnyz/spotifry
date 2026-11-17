// Peak filter maths, frequency response and the fade between settings adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt

package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

const val EQ_BANDS = 10
/** How far a band goes up or down, in millibels (100 = 1 dB). */
const val EQ_MAX_MB = 1200
/** Each band's centre: one octave apart, 31 Hz to 16 kHz. */
val EQ_CENTERS_HZ = DoubleArray(EQ_BANDS) { 31.25 * 2.0.pow(it) }
/** Each band's width in octaves: wider than the one octave between bands; narrower leaves dips you can hear between them. */
const val EQ_WIDTH_OCTAVES = 1.39

/** Q from a width in octaves, and back: the two ways of saying how wide a band is. */
fun qOf(octaves: Double): Double = sqrt(2.0.pow(octaves)) / (2.0.pow(octaves) - 1)
fun octavesOf(q: Double): Double = 2 / ln(2.0) * kotlin.math.asinh(1 / (2 * q))

/** The band's frequency and Q the user can set, within these. */
const val EQ_MIN_HZ = 20.0
const val EQ_MAX_HZ = 20_000.0
const val EQ_MIN_Q = .3
const val EQ_MAX_Q = 10.0

/**
 * Where each band sits ([hz]) and how wide it is ([q]). [DEFAULT] is the ten bands one octave apart; the presets and the
 * headphone corrections are levels for that one. Saved as "hz:q" per band; anything unreadable is [DEFAULT].
 */
data class EqShape(val hz: List<Double>, val q: List<Double>) {
    val isDefault get() = this == DEFAULT
    /** Whether band [b] sits or spreads differently from the default. */
    fun moved(b: Int) = hz[b] != DEFAULT.hz[b] || q[b] != DEFAULT.q[b]
    fun with(b: Int, hz: Double = this.hz[b], q: Double = this.q[b]) = EqShape(
        this.hz.toMutableList().also { it[b] = hz.coerceIn(EQ_MIN_HZ, EQ_MAX_HZ) },
        this.q.toMutableList().also { it[b] = q.coerceIn(EQ_MIN_Q, EQ_MAX_Q) },
    )
    fun encode() = hz.indices.joinToString(",") { "${hz[it]}:${q[it]}" }

    companion object {
        val DEFAULT = EqShape(EQ_CENTERS_HZ.toList(), List(EQ_BANDS) { qOf(EQ_WIDTH_OCTAVES) })
        fun decode(s: String?): EqShape {
            val pairs = s?.split(',')?.map { it.split(':').mapNotNull(String::toDoubleOrNull) } ?: return DEFAULT
            if (pairs.size != EQ_BANDS || pairs.any { it.size != 2 || it[0] !in EQ_MIN_HZ..EQ_MAX_HZ || it[1] !in EQ_MIN_Q..EQ_MAX_Q }) return DEFAULT
            return EqShape(pairs.map { it[0] }, pairs.map { it[1] })
        }
    }
}

/** Android's own equalizer values, so a preset saved from it keeps its name and its sound. */
val EQ_PRESETS: List<Pair<String, List<Int>>> = listOf(
    "Normal" to listOf(300, 0, 0, 0, 300),
    "Classical" to listOf(500, 300, -200, 400, 400),
    "Dance" to listOf(600, 0, 200, 400, 100),
    "Flat" to listOf(0, 0, 0, 0, 0),
    "Folk" to listOf(300, 0, 0, 200, -100),
    "Heavy Metal" to listOf(400, 100, 900, 300, 0),
    "Hip Hop" to listOf(500, 300, 0, 100, 300),
    "Jazz" to listOf(400, 200, -200, 200, 500),
    "Pop" to listOf(-100, 200, 500, 100, -200),
    "Rock" to listOf(500, 300, -100, 300, 500),
).map { (name, old) -> name to eqFromOldBands(old) }

/** The boost or cut in dB at [hz]: straight lines between the bars on a log scale, flat past the ends. */
fun eqDbAt(hz: Float, centersHz: List<Int>, levelsMb: List<Int>): Float {
    val n = minOf(centersHz.size, levelsMb.size)
    if (n == 0) return 0f
    if (hz <= centersHz[0]) return levelsMb[0] / 100f
    for (i in 1 until n) if (hz <= centersHz[i]) {
        val t = (ln(hz) - ln(centersHz[i - 1].toFloat())) / (ln(centersHz[i].toFloat()) - ln(centersHz[i - 1].toFloat()))
        return (levelsMb[i - 1] + t * (levelsMb[i] - levelsMb[i - 1])) / 100f
    }
    return levelsMb[n - 1] / 100f
}

/**
 * Levels saved from the phone's equalizer, turned into the ten bands. That one didn't save its bars' frequencies; it
 * usually had five (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz), which this spacing matches for any bar count.
 */
fun eqFromOldBands(levelsMb: List<Int>): List<Int> {
    if (levelsMb.isEmpty()) return List(EQ_BANDS) { 0 }
    val last = (levelsMb.size - 1).coerceAtLeast(1)
    val centers = List(levelsMb.size) { (60 * (14_000 / 60.0).pow(it.toDouble() / last)).toInt() }
    return List(EQ_BANDS) { (eqDbAt(EQ_CENTERS_HZ[it].toFloat(), centers, levelsMb) * 100).toInt().coerceIn(-EQ_MAX_MB, EQ_MAX_MB) }
}

/**
 * The filters for one set of band levels: what the sound goes through and what the page draws, so the curve is the
 * real sound. Neighbouring filters overlap, so with plain gains six bands at +6 dB come out near +10. [DESIGN_ROUNDS]
 * of correction make each band's own frequency land on the level asked for.
 */
class EqDesign(levelsMb: List<Int>, private val sampleRate: Int = 48_000, private val shape: EqShape = EqShape.DEFAULT) {
    /** b0, b1, b2, a1, a2 of each band. */
    val coefficients = DoubleArray(EQ_BANDS * 5)
    /** Each band's own filter gain in dB, after the correction for overlap: what an RBJ peak filter [EQ_WIDTH_OCTAVES] wide is set to. */
    val gainsDb: DoubleArray
    /** Turns everything down by the curve's highest boost, so a song already at full level can't clip when boosted. */
    val preamp: Double

    init {
        // a band too close to half the sample rate can't be filtered; it stays at 0
        val usable = BooleanArray(EQ_BANDS) { shape.hz[it] < sampleRate * .45 }
        val want = DoubleArray(EQ_BANDS) { if (usable[it]) levelsMb.getOrElse(it) { 0 }.coerceIn(-EQ_MAX_MB, EQ_MAX_MB) / 100.0 else 0.0 }
        val gains = want.copyOf()
        repeat(DESIGN_ROUNDS) {
            build(gains)
            for (i in 0 until EQ_BANDS) if (usable[i]) gains[i] = (gains[i] + CORRECTION * (want[i] - responseDb(shape.hz[i]))).coerceIn(-MAX_FILTER_DB, MAX_FILTER_DB)
        }
        // two bands moved onto one spot with different levels can't both be met: the correction runs into its limits, so the
        // levels are used as they are, like a plain parametric equalizer
        if (!shape.isDefault && (0 until EQ_BANDS).any { abs(gains[it]) >= MAX_FILTER_DB - .01 || abs(gains[it] - want[it]) > RUNAWAY_DB }) want.copyInto(gains)
        build(gains)
        gainsDb = gains
        var peak = 0.0
        for (i in 0 until PEAK_POINTS) peak = maxOf(peak, responseDb(20 * (sampleRate * .49 / 20).pow(i.toDouble() / (PEAK_POINTS - 1))))
        preamp = 10.0.pow(-peak / 20)
    }

    /** The peak filter from the RBJ audio EQ cookbook. */
    private fun build(gains: DoubleArray) {
        for (i in 0 until EQ_BANDS) {
            val w = 2 * PI * shape.hz[i].coerceAtMost(sampleRate * .49) / sampleRate
            // the cookbook's bandwidth form: it stays the same width near the top of the range, where the simpler one narrows
            val alpha = sin(w) * sinh(ln(2.0) / 2 * octavesOf(shape.q[i]) * w / sin(w))
            val a = 10.0.pow(gains[i] / 40)
            val a0 = 1 + alpha / a
            coefficients[i * 5] = (1 + alpha * a) / a0
            coefficients[i * 5 + 1] = -2 * cos(w) / a0
            coefficients[i * 5 + 2] = (1 - alpha * a) / a0
            coefficients[i * 5 + 3] = -2 * cos(w) / a0
            coefficients[i * 5 + 4] = (1 - alpha / a) / a0
        }
    }

    /** What the bands do to [hz] in dB, before [preamp]. */
    fun responseDb(hz: Double): Double {
        val c1 = cos(2 * PI * hz / sampleRate)
        val c2 = cos(4 * PI * hz / sampleRate)
        var db = 0.0
        for (i in 0 until EQ_BANDS) db += bandDb(i, c1, c2)
        return db
    }

    /** What band [band] alone does to [hz] in dB: its own hill on the graph. */
    fun bandDb(band: Int, hz: Double) = bandDb(band, cos(2 * PI * hz / sampleRate), cos(4 * PI * hz / sampleRate))

    private fun bandDb(i: Int, c1: Double, c2: Double): Double {
        val b0 = coefficients[i * 5]; val b1 = coefficients[i * 5 + 1]; val b2 = coefficients[i * 5 + 2]
        val a1 = coefficients[i * 5 + 3]; val a2 = coefficients[i * 5 + 4]
        val top = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val bottom = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2
        return 10 * log10(maxOf(top, 1e-20) / maxOf(bottom, 1e-20))
    }

    private companion object {
        const val DESIGN_ROUNDS = 24
        /** Each round fixes only part of the error: the wide filters overlap too much to fix it all in one go. */
        const val CORRECTION = .7
        const val MAX_FILTER_DB = 24.0
        const val PEAK_POINTS = 240
        /** Further than this from the level asked for, a corrected gain is a correction that ran away. */
        const val RUNAWAY_DB = 12.0
    }
}

/** A frequency slider's position (0 to 1, on a log scale) to Hz, rounded the way a person reads it: 47, 125, 3150, 12500. */
fun eqHzAt(t: Float): Double {
    val hz = EQ_MIN_HZ * (EQ_MAX_HZ / EQ_MIN_HZ).pow(t.coerceIn(0f, 1f).toDouble())
    val step = when { hz < 100 -> 1.0; hz < 1000 -> 5.0; hz < 10_000 -> 50.0; else -> 100.0 }
    return (Math.round(hz / step) * step).coerceIn(EQ_MIN_HZ, EQ_MAX_HZ)
}
fun eqHzPos(hz: Double): Float = (ln(hz / EQ_MIN_HZ) / ln(EQ_MAX_HZ / EQ_MIN_HZ)).toFloat()

/** A width slider's position (0 wide to 1 narrow, on a log scale) to Q, to two places below 1 and one above. */
fun eqQAt(t: Float): Double {
    val q = EQ_MIN_Q * (EQ_MAX_Q / EQ_MIN_Q).pow(t.coerceIn(0f, 1f).toDouble())
    val step = if (q < 1) .01 else .1
    return (Math.round(q / step) * step).coerceIn(EQ_MIN_Q, EQ_MAX_Q)
}
fun eqQPos(q: Double): Float = (ln(q / EQ_MIN_Q) / ln(EQ_MAX_Q / EQ_MIN_Q)).toFloat()

/** "Q 1.0 · medium": the number for those who know it, a word for everyone else. */
fun eqQText(q: Double): String {
    val n = if (q < 1) "%.2f".format(java.util.Locale.US, q) else "%.1f".format(java.util.Locale.US, q)
    return "Q $n · " + when { q < .8 -> "wide"; q > 2.5 -> "narrow"; else -> "medium" }
}
