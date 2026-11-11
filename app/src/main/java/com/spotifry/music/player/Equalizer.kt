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
fun soundChain(equalizer: EqualizerProcessor, loudness: LoudnessProcessor): Array<AudioProcessor> = arrayOf(loudness, equalizer)

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
