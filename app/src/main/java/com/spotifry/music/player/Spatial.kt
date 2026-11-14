package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * Headphone crossfeed: a little of each side reaches the other ear, dulled the way a head dulls it, like listening to speakers.
 * [shadowHz] is where the dulling starts, [crossDb] how much quieter the crossing sound is, [amount] 0..1 how much of the
 * effect is mixed in.
 */
data class SpatialSetting(val shadowHz: Int, val crossDb: Int, val amount: Int) {
    companion object {
        val LIGHT = SpatialSetting(700, 9, 60)
        val NATURAL = SpatialSetting(700, 6, 100)
        val STRONG = SpatialSetting(550, 4, 100)
        val PRESETS = listOf("Light" to LIGHT, "Natural" to NATURAL, "Strong" to STRONG)
    }
}

/** Stereo maths on one frame at a time, samples -1..1. Results are left in [outL] and [outR]. */
class Spatial {
    var mono = false
    /** Null is off. */
    var crossfeed: SpatialSetting? = null
    var outL = 0f; private set
    var outR = 0f; private set
    private var rate = 44_100
    private var lpL = 0.0
    private var lpR = 0.0

    fun configure(sampleRate: Int) { rate = sampleRate; lpL = 0.0; lpR = 0.0 }

    fun process(l: Float, r: Float) {
        var a = l; var b = r
        if (mono) { val m = (a + b) / 2; a = m; b = m }
        val c = crossfeed
        if (c != null) {
            val k = 1 - exp(-2 * PI * c.shadowHz / rate)
            lpL += k * (a - lpL); lpR += k * (b - lpR)
            val g = 10.0.pow(-c.crossDb / 20.0)
            val mix = c.amount / 100.0
            // the sum is divided by 1+g so full crossfeed can't be louder than what went in
            a = (a * (1 - mix) + (a + g * lpR) / (1 + g) * mix).toFloat()
            b = (b * (1 - mix) + (b + g * lpL) / (1 + g) * mix).toFloat()
        }
        outL = a; outR = b
    }
}

/** [Spatial] in the sound path. Stays in it while off, as the equalizer does; only two-channel sound is touched. */
@UnstableApi
class SpatialProcessor : BaseAudioProcessor() {
    private val s = Spatial()
    @Volatile private var mono = false
    @Volatile private var crossfeed: SpatialSetting? = null

    fun set(mono: Boolean, crossfeed: SpatialSetting?) { this.mono = mono; this.crossfeed = crossfeed }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (f.encoding == C.ENCODING_PCM_16BIT) f else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        s.mono = mono; s.crossfeed = crossfeed
        val out = replaceOutputBuffer(input.remaining())
        input.order(out.order())
        if ((s.mono || s.crossfeed != null) && inputAudioFormat.channelCount == 2) {
            while (input.remaining() >= 4) {
                s.process(input.getShort() / 32768f, input.getShort() / 32768f)
                out.putShort(Math.round(s.outL * 32768f).coerceIn(-32768, 32767).toShort())
                out.putShort(Math.round(s.outR * 32768f).coerceIn(-32768, 32767).toShort())
            }
        }
        out.put(input)
        out.flip()
    }

    override fun onFlush(metadata: AudioProcessor.StreamMetadata) { if (isActive) s.configure(inputAudioFormat.sampleRate) }
}
