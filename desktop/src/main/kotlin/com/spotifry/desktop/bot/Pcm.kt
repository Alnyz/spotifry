package com.spotifry.desktop.bot

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

/** What Discord takes every 20 ms: 48 kHz, 16-bit, stereo. */
const val FRAME_BYTES = 3840
const val FRAME_SAMPLES = FRAME_BYTES / 2

/** mpv writes little-endian samples, JDA wants big-endian: swaps each 16-bit sample in place. */
fun toBigEndian(frame: ByteArray) {
    var i = 0
    while (i + 1 < frame.size) { val t = frame[i]; frame[i] = frame[i + 1]; frame[i + 1] = t; i += 2 }
}

/**
 * One mpv's raw sound, cut into frames by a reader thread. The queue holds at most [bufferFrames]; when it is full the
 * reader waits, so mpv waits too: whoever takes the frames sets the pace.
 */
class PcmStream(private val input: InputStream, bufferFrames: Int = 10) : AutoCloseable {
    private val frames = ArrayBlockingQueue<ShortArray>(bufferFrames)
    /** The input has closed; whatever is still queued can be taken. */
    @Volatile var ended = false
        private set
    val drained: Boolean get() = ended && frames.isEmpty()

    private val reader = Thread({
        val buf = ByteArray(FRAME_BYTES)
        try {
            while (true) {
                var got = 0
                while (got < FRAME_BYTES) { val n = input.read(buf, got, FRAME_BYTES - got); if (n < 0) break; got += n }
                if (got > 0) {
                    // a last half frame is padded with silence
                    if (got < FRAME_BYTES) buf.fill(0, got)
                    val le = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    frames.put(ShortArray(FRAME_SAMPLES) { le.getShort() })
                }
                if (got < FRAME_BYTES) break
            }
        } catch (_: InterruptedException) {
        } catch (_: java.io.IOException) {
        }
        ended = true
    }, "pcm-reader").apply { isDaemon = true; start() }

    /** One frame, or null when none is ready. */
    fun poll(): ShortArray? = frames.poll()

    override fun close() {
        reader.interrupt()
        runCatching { input.close() }
    }
}

/** Mixes every stream into one frame: two during a crossfade, one otherwise. */
class PcmMixer {
    private val streams = CopyOnWriteArrayList<PcmStream>()
    val size: Int get() = streams.size

    fun add(s: PcmStream) { streams.add(s) }
    fun remove(s: PcmStream) { streams.remove(s) }

    /** The next 20 ms as big-endian bytes, or null when no stream had sound ready. */
    fun next(): ByteArray? {
        var sum: IntArray? = null
        for (s in streams) {
            val f = s.poll()
            if (f != null) {
                val acc = sum ?: IntArray(FRAME_SAMPLES).also { sum = it }
                for (i in 0 until FRAME_SAMPLES) acc[i] += f[i]
            }
            if (s.drained) streams.remove(s)
        }
        val acc = sum ?: return null
        val out = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.BIG_ENDIAN)
        for (v in acc) out.putShort(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        return out.array()
    }
}
