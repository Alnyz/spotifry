package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.cos
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

/**
 * Live picture of the sound for the Equalizer page. Filled by the player only while [listening].
 * The player works through the song in bursts (a quarter second at a time), so pictures are queued and handed
 * out at the pace the music plays instead of all at once.
 */
class SpectrumTap {
    @Volatile var listening = false
    private val frames = ArrayDeque<FloatArray>()
    private var owed = 0f

    /** Audio thread: one picture, levels in dB of each [Spectrum] band, low to high. */
    fun push(f: FloatArray) = synchronized(frames) { frames.addLast(f); while (frames.size > 60) frames.removeFirst() }

    /** Screen, once per drawn frame: the picture due after [elapsedMs], or null if none is due. */
    fun next(elapsedMs: Float): FloatArray? = synchronized(frames) {
        if (frames.isEmpty()) { owed = 0f; return null }
        // 30 pictures per second of music; quicker when a backlog builds (fast playback), slower when it runs low
        owed += elapsedMs / FRAME_MS * (frames.size / QUEUED).coerceIn(.5f, 2f)
        var out: FloatArray? = null
        while (owed >= 1f && frames.isNotEmpty()) { out = frames.removeFirst(); owed -= 1f }
        out
    }

    fun clear() = synchronized(frames) { frames.clear(); owed = 0f }

    private companion object {
        const val FRAME_MS = 1000f / 30
        /** About 0.4 s kept waiting, enough to bridge the gaps between bursts. */
        const val QUEUED = 12f
    }
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
