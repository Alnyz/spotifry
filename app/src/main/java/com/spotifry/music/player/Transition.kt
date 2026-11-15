package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Two cascaded 12 dB filters (24 dB per octave) on samples -1..1; the cutoff may change at any time. */
class Sweep {
    private var mode = FILTER_OFF
    private var hz = 0f
    private var rate = 44_100
    private val c = DoubleArray(5)
    private val state = DoubleArray(2 * 2 * 4) // 2 channels x 2 stages x (x1, x2, y1, y2)

    fun configure(sampleRate: Int) { rate = sampleRate; state.fill(0.0); design() }
    fun set(mode: Int, hz: Float) {
        // 1% is inaudible, and a new design every tick isn't free
        if (mode == this.mode && (hz > this.hz * .99f && hz < this.hz * 1.01f)) return
        this.mode = mode; this.hz = hz; design()
    }

    /** False means samples pass as they are: no filter, or one so wide open that it does nothing. */
    val active get() = mode != FILTER_OFF && !(mode == FILTER_HIGHPASS && hz <= 25f) && !(mode == FILTER_LOWPASS && hz >= 17_000f)

    private fun design() {
        if (mode == FILTER_OFF) return
        val w = 2 * PI * hz.coerceIn(10f, rate * .45f) / rate
        val alpha = sin(w) / (2 * .7071); val cw = cos(w)
        val a0 = 1 + alpha
        val hp = mode == FILTER_HIGHPASS
        val b0 = (if (hp) (1 + cw) / 2 else (1 - cw) / 2) / a0
        c[0] = b0; c[1] = (if (hp) -(1 + cw) else 1 - cw) / a0; c[2] = b0; c[3] = -2 * cw / a0; c[4] = (1 - alpha) / a0
    }

    fun process(x: Float, channel: Int): Float {
        if (!active) return x
        var v = x.toDouble()
        for (st in 0..1) {
            val s = (channel.coerceAtMost(1) * 2 + st) * 4
            val y = c[0] * v + c[1] * state[s] + c[2] * state[s + 1] - c[3] * state[s + 2] - c[4] * state[s + 3]
            state[s + 1] = state[s]; state[s] = v; state[s + 3] = state[s + 2]; state[s + 2] = y
            v = y
        }
        return v.toFloat()
    }
}

/** [Sweep] in the sound path. Stays in it while off, as the equalizer does. */
@UnstableApi
class SweepProcessor : BaseAudioProcessor() {
    private val sweep = Sweep()
    @Volatile private var mode = FILTER_OFF
    @Volatile private var hz = 0f

    fun set(mode: Int, hz: Float) { this.mode = mode; this.hz = hz }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        sweep.set(mode, hz)
        val out = replaceOutputBuffer(input.remaining())
        input.order(out.order())
        if (sweep.active) {
            var ch = 0
            val channels = inputAudioFormat.channelCount
            while (input.remaining() >= 2) {
                out.putShort(Math.round(Leveler.soften(sweep.process(input.getShort() / 32768f, ch)) * 32768f).coerceIn(-32768, 32767).toShort())
                if (++ch == channels) ch = 0
            }
        } else out.put(input)
        out.flip()
    }

    override fun onFlush(metadata: AudioProcessor.StreamMetadata) { if (isActive) sweep.configure(inputAudioFormat.sampleRate) }
}
