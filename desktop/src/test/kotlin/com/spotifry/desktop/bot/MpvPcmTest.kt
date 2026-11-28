package com.spotifry.desktop.bot

import com.spotifry.desktop.Mpv
import com.spotifry.desktop.Platform
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A real mpv hands its sound over instead of playing it, at the pace it is taken. Skipped when mpv isn't installed. */
class MpvPcmTest {
    /** [seconds] of a 440 Hz tone, 48 kHz stereo 16-bit, as a WAV file. */
    private fun tone(seconds: Int): File {
        val n = 48_000 * seconds
        val b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(48_000).putInt(48_000 * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 4)
        repeat(n) { val s = (Math.sin(2 * Math.PI * 440 * it / 48_000) * 8000).toInt().toShort(); b.putShort(s).putShort(s) }
        return File.createTempFile("tone", ".wav").also { it.writeBytes(b.array()); it.deleteOnExit() }
    }

    @Test fun realMpvKeepsPace() {
        assumeTrue("mpv not installed", runCatching { ProcessBuilder(Platform.tool("mpv"), "--version").start().waitFor() == 0 }.getOrDefault(false))
        assumeFalse("Windows: Task 3", Platform.isWindows)
        val mpv = Mpv(pcmPath = "/dev/stdout") { _, _ -> }
        mpv.use {
            val stream = PcmStream(it.pcm!!)
            it.command("loadfile", tone(30).path)
            val mixer = PcmMixer().apply { add(stream) }
            // wait for the first sound, then take a frame every 20 ms for 3 s, as JDA does
            val until = System.currentTimeMillis() + 5_000
            var first: ByteArray? = null
            while (first == null && System.currentTimeMillis() < until) { first = mixer.next(); if (first == null) Thread.sleep(5) }
            assertTrue("no sound came", first != null)
            var frames = 1
            var loud = first!!.any { b -> b.toInt() != 0 }
            val start = System.nanoTime()
            var due = start
            while (System.nanoTime() - start < 3_000_000_000L) {
                due += 20_000_000L
                Thread.sleep(((due - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
                mixer.next()?.let { f -> frames++; if (!loud) loud = f.any { b -> b.toInt() != 0 } }
            }
            val wall = (System.nanoTime() - start) / 1e9
            assertTrue("only $frames frames", frames >= 140)
            assertTrue("all silent", loud)
            assertTrue("took $wall s", wall in 2.8..3.6)
        }
    }
}
