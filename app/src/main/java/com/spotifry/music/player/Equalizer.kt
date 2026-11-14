// Peak filter maths, frequency response and the fade between settings adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt

package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
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
        var peak = 0.0
        for (i in 0 until PEAK_POINTS) peak = maxOf(peak, responseDb(20 * (sampleRate * .49 / 20).pow(i.toDouble() / (PEAK_POINTS - 1))))
        preamp = 10.0.pow(-peak / 20)
    }

    /** The peak filter from the RBJ audio EQ cookbook. */
    private fun build(gains: DoubleArray) {
        for (i in 0 until EQ_BANDS) {
            val w = 2 * PI * EQ_CENTERS_HZ[i].coerceAtMost(sampleRate * .49) / sampleRate
            // the cookbook's bandwidth form: it stays the same width near the top of the range, where the simpler one narrows
            val alpha = sin(w) * sinh(ln(2.0) / 2 * OCTAVES * w / sin(w))
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
        /** Wider than the one octave between bands; narrower leaves dips you can hear between them. */
        const val OCTAVES = 1.39
        const val DESIGN_ROUNDS = 24
        /** Each round fixes only part of the error: the wide filters overlap too much to fix it all in one go. */
        const val CORRECTION = .7
        const val MAX_FILTER_DB = 24.0
        const val PEAK_POINTS = 240
    }
}

/**
 * The equalizer itself, on samples -1..1 in playing order. A new setting fades in over [FADE_MS], so dragging a point or
 * picking a preset never clicks. Off and flat both skip the filters entirely, so the sound passes through untouched.
 */
class Equalizer {
    private class Filters(design: EqDesign, channels: Int) {
        private val c = design.coefficients
        private val gain = design.preamp
        /** x1, x2, y1, y2 of each band on each channel. */
        val state = DoubleArray(EQ_BANDS * channels * 4)

        fun step(input: Double, channel: Int): Double {
            var x = input
            var s = channel * EQ_BANDS * 4
            for (b in 0 until EQ_BANDS) {
                val o = b * 5
                val y = c[o] * x + c[o + 1] * state[s] + c[o + 2] * state[s + 1] - c[o + 3] * state[s + 2] - c[o + 4] * state[s + 3]
                state[s + 1] = state[s]; state[s] = x
                state[s + 3] = state[s + 2]; state[s + 2] = y
                x = y
                s += 4
            }
            return x * gain
        }
    }

    @Volatile private var wanted: List<Int>? = null
    @Volatile private var changed = false
    private var sampleRate = 44_100
    private var channels = 2
    private var channel = 0
    private var now: Filters? = null
    private var old: Filters? = null
    private var fadeLeft = 0
    private var fadeFrames = 1

    /** True when samples have to go through [process]; false means they pass as they are. */
    val active get() = now != null || fadeLeft > 0

    /** Any thread: the band levels in millibels, or null for off. Picked up by [update]. */
    fun set(levelsMb: List<Int>?) { wanted = levelsMb; changed = true }

    /** A new format or a seek: filters start empty, because what they remember belongs to sound left behind. A waiting setting applies at once. */
    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate; this.channels = channels
        fadeFrames = (sampleRate * FADE_MS / 1000).coerceAtLeast(1)
        changed = false
        now = filtersFor(wanted); old = null; fadeLeft = 0; channel = 0
    }

    /** Audio thread, between buffers: starts the fade to a new setting if there is one. */
    fun update() {
        if (!changed) return
        changed = false
        val next = filtersFor(wanted)
        if (now == null && next == null) return
        // carries on from the old filters' memory, so the new ones don't start from silence
        now?.let { n -> next?.let { n.state.copyInto(it.state) } }
        old = now; now = next; fadeLeft = fadeFrames
    }

    fun process(x: Float): Float {
        val dry = x.toDouble()
        val wet = now?.step(dry, channel) ?: dry
        val out = if (fadeLeft > 0) {
            val t = fadeLeft.toDouble() / fadeFrames
            (old?.step(dry, channel) ?: dry) * t + wet * (1 - t)
        } else wet
        if (++channel == channels) {
            channel = 0
            if (fadeLeft > 0 && --fadeLeft == 0) old = null
        }
        return out.toFloat()
    }

    private fun filtersFor(levels: List<Int>?): Filters? =
        if (levels == null || levels.all { it == 0 }) null else Filters(EqDesign(levels, sampleRate), channels)

    private companion object {
        const val FADE_MS = 30
    }
}

/**
 * The order the sound goes through them. Loudness first, so each song's remembered level and the live picture are the
 * song's own and don't move with the curve. The price: a big boost stays quieter overall, because of [EqDesign.preamp].
 */
@UnstableApi
fun soundChain(equalizer: EqualizerProcessor, loudness: LoudnessProcessor, spatial: SpatialProcessor, sweep: SweepProcessor): Array<AudioProcessor> = arrayOf(loudness, equalizer, spatial, sweep)

/**
 * Puts [Equalizer] in the player's sound path. It stays in the path while off, because joining or leaving the path
 * would make the sound skip.
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {
    private val eq = Equalizer()

    fun set(levelsMb: List<Int>?) = eq.set(levelsMb)

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(input: ByteBuffer) {
        // with nothing in it yet, the output buffer is this same empty buffer, and a buffer can't be copied onto itself
        if (!input.hasRemaining()) return
        eq.update()
        val out = replaceOutputBuffer(input.remaining())
        input.order(out.order())
        if (eq.active) {
            while (input.remaining() >= 2) {
                // the first instant of a sound can overshoot even after the turn-down; bent gently, not cut flat
                out.putShort(Math.round(Leveler.soften(eq.process(input.getShort() / 32768f)) * 32768f).coerceIn(-32768, 32767).toShort())
            }
        } else out.put(input)
        out.flip()
    }

    // the new format only takes effect here; until then the end of the old one may still be coming through
    override fun onFlush(metadata: AudioProcessor.StreamMetadata) {
        if (isActive) eq.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }
}
