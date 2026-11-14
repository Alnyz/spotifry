// Peak filter maths, frequency response and the fade between settings adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt

package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

const val EQ_BANDS = 10
/** How far a band goes up or down, in millibels (100 = 1 dB). */
const val EQ_MAX_MB = 1200
/** Each band's centre: one octave apart, 31 Hz to 16 kHz. */
val EQ_CENTERS_HZ = DoubleArray(EQ_BANDS) { 31.25 * 2.0.pow(it) }
/** Each band's width in octaves: wider than the one octave between bands; narrower leaves dips you can hear between them. */
const val EQ_WIDTH_OCTAVES = 1.39

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
class EqDesign(levelsMb: List<Int>, private val sampleRate: Int = 48_000) {
    /** b0, b1, b2, a1, a2 of each band. */
    val coefficients = DoubleArray(EQ_BANDS * 5)
    /** Each band's own filter gain in dB, after the correction for overlap: what an RBJ peak filter [EQ_WIDTH_OCTAVES] wide is set to. */
    val gainsDb: DoubleArray
    /** Turns everything down by the curve's highest boost, so a song already at full level can't clip when boosted. */
    val preamp: Double

    init {
        // a band too close to half the sample rate can't be filtered; it stays at 0
        val usable = BooleanArray(EQ_BANDS) { EQ_CENTERS_HZ[it] < sampleRate * .45 }
        val want = DoubleArray(EQ_BANDS) { if (usable[it]) levelsMb.getOrElse(it) { 0 }.coerceIn(-EQ_MAX_MB, EQ_MAX_MB) / 100.0 else 0.0 }
        val gains = want.copyOf()
        repeat(DESIGN_ROUNDS) {
            build(gains)
            for (i in 0 until EQ_BANDS) if (usable[i]) gains[i] = (gains[i] + CORRECTION * (want[i] - responseDb(EQ_CENTERS_HZ[i]))).coerceIn(-MAX_FILTER_DB, MAX_FILTER_DB)
        }
        build(gains)
        gainsDb = gains
        var peak = 0.0
        for (i in 0 until PEAK_POINTS) peak = maxOf(peak, responseDb(20 * (sampleRate * .49 / 20).pow(i.toDouble() / (PEAK_POINTS - 1))))
        preamp = 10.0.pow(-peak / 20)
    }

    /** The peak filter from the RBJ audio EQ cookbook. */
    private fun build(gains: DoubleArray) {
        for (i in 0 until EQ_BANDS) {
            val w = 2 * PI * EQ_CENTERS_HZ[i].coerceAtMost(sampleRate * .49) / sampleRate
            // the cookbook's bandwidth form: it stays the same width near the top of the range, where the simpler one narrows
            val alpha = sin(w) * sinh(ln(2.0) / 2 * EQ_WIDTH_OCTAVES * w / sin(w))
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
    }
}
