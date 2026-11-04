package com.spotifry.music.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

/** What the phone's equalizer offers: level range in millibels, each bar's centre in Hz, and the ready-made sounds. */
data class EqInfo(val minMb: Int, val maxMb: Int, val centersHz: List<Int>, val presets: List<Pair<String, List<Int>>>) {
    companion object {
        /** Asks a throwaway equalizer on an unused sound channel. Null on phones without one. */
        fun probe(context: Context): EqInfo? = runCatching {
            val eq = Equalizer(0, context.getSystemService(AudioManager::class.java).generateAudioSessionId())
            try {
                val bands = (0 until eq.numberOfBands).map { it.toShort() }
                val range = eq.bandLevelRange
                val presets = (0 until eq.numberOfPresets).map { p ->
                    eq.usePreset(p.toShort())
                    eq.getPresetName(p.toShort()) to bands.map { eq.getBandLevel(it).toInt() }
                }
                EqInfo(range[0].toInt(), range[1].toInt(), bands.map { eq.getCenterFreq(it) / 1000 }, presets)
            } finally { eq.release() }
        }.getOrNull()
    }
}

/** The equalizer's boost or cut in dB at [hz]: straight lines between the bars on a log scale, flat past the ends. */
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

/** Live picture of the sound for the Equalizer page. Filled by the player only while [listening]. */
class SpectrumTap {
    @Volatile var listening = false
    /** Level in dB of each [Spectrum] band, low to high. */
    val bands = MutableStateFlow(FloatArray(Spectrum.BANDS) { Spectrum.FLOOR_DB })
}

/**
 * Turns the last [SIZE] samples into [BANDS] levels, about 30 times a second.
 * Bands are spread evenly on a log scale from 40 Hz to 16 kHz, the way the ear hears pitch.
 */
class Spectrum {
    private val ring = FloatArray(SIZE)
    private var at = 0
    private var since = 0
    private var rate = 44_100
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    val bands = FloatArray(BANDS) { FLOOR_DB }

    fun configure(sampleRate: Int) { rate = sampleRate }

    /** One sample, both channels mixed. True when [bands] were just refreshed. */
    fun feed(x: Float): Boolean {
        ring[at] = x; at = (at + 1) % SIZE
        if (++since < rate / 30) return false
        since = 0; compute(); return true
    }

    private fun compute() {
        for (i in 0 until SIZE) {
            // Hann window, so a tone shows as one narrow peak instead of smearing over every band
            re[i] = ring[(at + i) % SIZE] * (.5f - .5f * cos(2 * PI * i / SIZE).toFloat()); im[i] = 0f
        }
        fft(re, im)
        for (b in 0 until BANDS) {
            val lo = (bandEdgeHz(b) * SIZE / rate).toInt().coerceIn(1, SIZE / 2 - 1)
            val hi = (bandEdgeHz(b + 1) * SIZE / rate).toInt().coerceIn(lo, SIZE / 2 - 1)
            var peak = 0f
            for (k in lo..hi) peak = maxOf(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            // a full-scale tone reads 0 dB
            bands[b] = (20 * log10(peak / (SIZE / 4f) + 1e-6f)).coerceAtLeast(FLOOR_DB)
        }
    }

    companion object {
        const val SIZE = 1024
        const val BANDS = 32
        const val FLOOR_DB = -80f
        fun bandEdgeHz(b: Int) = 40f * 400f.pow(b.toFloat() / BANDS)
        fun bandCenterHz(b: Int) = 40f * 400f.pow((b + .5f) / BANDS)

        /** In-place radix-2 FFT; size must be a power of two. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                for (s in 0 until n step len) for (k in 0 until len / 2) {
                    val wr = cos(ang * k).toFloat(); val wi = sin(ang * k).toFloat()
                    val a = s + k; val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                }
                len = len shl 1
            }
        }
    }
}
