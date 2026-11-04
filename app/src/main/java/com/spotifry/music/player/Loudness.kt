package com.spotifry.music.player

import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Same loudness for every song: measures how loud the song is as it plays (the average level over 100 ms blocks,
 * silence left out) and turns it up or down toward [TARGET_DB]. A song heard before starts at its remembered level.
 * ponytail: plain average level, not the ear-weighted LUFS meters use; close enough to even out songs.
 */
class Leveler {
    var enabled = true
    private var channels = 2
    private var blockFrames = 4_410
    private var blockEnergy = 0.0
    private var blockCount = 0
    private var songEnergy = 0.0
    private var songBlocks = 0
    private var gainDb = 0f
    private var targetDb = 0f

    fun configure(sampleRate: Int, channels: Int) { this.channels = channels; blockFrames = sampleRate / 10 }

    /** A new song: forget the old measurement, start from what's remembered for this one if anything. */
    fun startSong(knownDb: Float?) {
        songEnergy = 0.0; songBlocks = 0; blockEnergy = 0.0; blockCount = 0
        if (knownDb != null) {
            // counts as 30 s already heard, so the level holds steady instead of drifting while it re-measures
            songBlocks = SEED_BLOCKS; songEnergy = SEED_BLOCKS * 10.0.pow(knownDb / 10.0)
            targetDb = gainFor(knownDb); gainDb = targetDb
        }
    }

    /** The song's level once at least 10 s of sound were heard, else null. */
    fun measuredDb(): Float? = if (songBlocks >= 100) (10 * log10(songEnergy / songBlocks)).toFloat() else null

    /** One sample of one channel, -1..1, in playing order. Returns it at the new level. */
    fun process(x: Float): Float {
        blockEnergy += x * x
        if (++blockCount == blockFrames * channels) endBlock()
        val g = if (enabled) 10f.pow(gainDb / 20f) else 1f
        return soften(x * g)
    }

    private fun endBlock() {
        val db = 10 * log10(blockEnergy / blockCount + 1e-12)
        blockEnergy = 0.0; blockCount = 0
        if (db > GATE_DB) {
            songEnergy += 10.0.pow(db / 10.0); songBlocks++
            // from 2 s of sound on, aim for the song's level so far; before that, keep the last song's level
            if (songBlocks >= 20) targetDb = gainFor((10 * log10(songEnergy / songBlocks)).toFloat())
        }
        // at most 0.3 dB per 100 ms, so the change is never heard as a jump
        gainDb += (targetDb - gainDb).coerceIn(-STEP_DB, STEP_DB)
    }

    companion object {
        const val TARGET_DB = -16f
        const val GATE_DB = -50.0
        const val STEP_DB = .3f
        const val SEED_BLOCKS = 300
        fun gainFor(levelDb: Float) = (TARGET_DB - levelDb).coerceIn(-12f, 6f)

        /** Loud peaks bend gently under full scale instead of clipping when a quiet song is turned up. */
        fun soften(x: Float): Float {
            val a = abs(x)
            if (a <= .9f) return x
            return Math.copySign(.9f + .1f * tanh((a - .9f) / .1f), x)
        }
    }
}

/** Puts [Leveler] in the player's sound path. Accepts the two sample formats ExoPlayer hands to processors. */
@UnstableApi
class LoudnessProcessor(private val tap: SpectrumTap? = null) : BaseAudioProcessor() {
    val leveler = Leveler()
    private val spectrum = Spectrum()
    private var mix = 0f
    private var channel = 0
    @Volatile var enabled = true
    @Volatile private var pending: Float? = null
    @Volatile private var pendingSet = false
    @Volatile var lastMeasuredDb: Float? = null; private set

    /** Called on the main thread when a song starts; applied by the audio thread before its next samples. */
    fun startSong(knownDb: Float?) { pending = knownDb; pendingSet = true }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(f)
        leveler.configure(f.sampleRate, f.channelCount)
        spectrum.configure(f.sampleRate)
        channel = 0; mix = 0f
        return f
    }

    override fun queueInput(input: ByteBuffer) {
        if (pendingSet) { pendingSet = false; leveler.startSong(pending) }
        leveler.enabled = enabled
        val out = replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder())
        input.order(ByteOrder.nativeOrder())
        val listening = tap?.listening == true
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (input.remaining() >= 4) {
                val x = input.getFloat()
                if (listening) see(x)
                out.putFloat(leveler.process(x))
            }
        } else {
            while (input.remaining() >= 2) {
                val x = input.getShort() / 32768f
                if (listening) see(x)
                out.putShort((leveler.process(x) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
        lastMeasuredDb = leveler.measuredDb()
    }

    /** Feeds the live picture with the song as it is, before the leveler, so its loudness moves don't show. */
    private fun see(x: Float) {
        mix += x
        if (++channel < inputAudioFormat.channelCount) return
        if (spectrum.feed(mix / channel)) tap!!.bands.value = spectrum.bands.copyOf()
        channel = 0; mix = 0f
    }
}

/** Each song's measured level, so a replay starts at the right loudness. ponytail: one prefs entry per song played 10 s+. */
class LoudnessMemory(private val prefs: SharedPreferences) {
    fun get(id: String): Float? = if (prefs.contains(id)) prefs.getFloat(id, 0f) else null
    fun put(id: String, db: Float) = prefs.edit().putFloat(id, db).apply()
}
