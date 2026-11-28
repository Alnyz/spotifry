package com.spotifry.desktop.bot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    /** One frame of little-endian samples, as mpv writes them. */
    private fun frameLe(sample: (Int) -> Int): ByteArray {
        val b = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(FRAME_SAMPLES) { b.putShort(sample(it).toShort()) }
        return b.array()
    }

    private fun samplesBe(frame: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        return ShortArray(FRAME_SAMPLES) { b.getShort() }
    }

    /** A stream that hands out [frames] frames and then stays open with nothing more, like a paused mpv. */
    private fun stream(vararg frames: ByteArray): PcmStream {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, FRAME_BYTES * (frames.size + 1))
        frames.forEach(out::write)
        out.flush()
        return PcmStream(input)
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!what()) { check(System.currentTimeMillis() < until) { "timed out" }; Thread.sleep(5) }
    }

    @Test fun bytesSwapToBigEndian() {
        val b = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        toBigEndian(b)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), b)
    }

    @Test fun mixerSumsAndClamps() {
        val loud = PcmMixer().apply { add(stream(frameLe { 20000 })); add(stream(frameLe { 20000 })) }
        var f: ByteArray? = null
        waitFor { loud.next()?.also { f = it } != null }
        assertTrue(samplesBe(f!!).all { it == Short.MAX_VALUE })

        val mixed = PcmMixer().apply { add(stream(frameLe { 1000 })); add(stream(frameLe { -400 })) }
        f = null
        waitFor { mixed.next()?.also { f = it } != null }
        assertTrue(samplesBe(f!!).all { it.toInt() == 600 })
    }

    @Test fun oneStreamPassesThroughUnchanged() {
        val ramp = { i: Int -> i * 17 - 16000 }
        val m = PcmMixer().apply { add(stream(frameLe(ramp))) }
        var f: ByteArray? = null
        waitFor { m.next()?.also { f = it } != null }
        assertArrayEquals(ShortArray(FRAME_SAMPLES) { ramp(it).toShort() }, samplesBe(f!!))
    }

    @Test fun noFrameReadyGivesNull() {
        val m = PcmMixer().apply { add(stream()) }
        Thread.sleep(50)
        assertNull(m.next())
    }

    @Test fun endedStreamIsDropped() {
        val half = PcmStream(ByteArrayInputStream(frameLe { 500 }.copyOf(FRAME_BYTES / 2)))
        val other = stream(frameLe { 100 }, frameLe { 100 })
        val m = PcmMixer().apply { add(half); add(other) }
        var first: ByteArray? = null
        waitFor { half.ended }
        waitFor { m.next()?.also { first = it } != null }
        val s = samplesBe(first!!)
        assertEquals(600, s[0].toInt()) // the half that came: both streams
        assertEquals(100, s[FRAME_SAMPLES - 1].toInt()) // the rest padded with silence
        var second: ByteArray? = null
        waitFor { m.next()?.also { second = it } != null }
        assertTrue(samplesBe(second!!).all { it.toInt() == 100 }) // the ended one is gone, the other flows on
        assertEquals(1, m.size)
    }

    @Test fun fullBufferBlocksTheReader() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read() = 0.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        PcmStream(endless, bufferFrames = 2).use {
            Thread.sleep(200)
            assertTrue("read ${read / FRAME_BYTES} frames", read <= 4L * FRAME_BYTES)
        }
    }
}
