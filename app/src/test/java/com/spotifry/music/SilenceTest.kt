package com.spotifry.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.spotifry.music.player.SilenceRules
import com.spotifry.music.player.newSilenceSkipper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SilenceTest {
    private val rate = 44_100

    /** Bytes that come out after [samples] (stereo, mono samples repeated) go in as one buffer, then the stream ends. */
    private fun run(samples: ShortArray): Int {
        val p = newSilenceSkipper().apply { setEnabled(true) }
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { b.putShort(it); b.putShort(it) }
        b.flip()
        var out = 0
        fun drain() { val o = p.output; out += o.remaining(); o.position(o.limit()) }
        p.queueInput(b); drain()
        p.queueEndOfStream(); drain()
        return out
    }

    @Test fun softFadingToneIsKept() {
        // 300 ms from 1500 down to 200: soft, but well over the threshold, like the end of a sung word
        val n = rate * 3 / 10
        val tone = ShortArray(n) { (sin(2 * PI * 220 * it / rate) * (1500 - 1300.0 * it / n)).toInt().toShort() }
        // the first few samples of a sine are near zero; losing a few milliseconds there isn't a cut
        assertTrue(run(tone) >= n * 4 - rate * 4 / 100)
    }

    @Test fun aSecondOfZerosIsCut() {
        val bytes = run(ShortArray(rate * 2))
        assertTrue("kept $bytes of ${rate * 8}", bytes < rate * 8 / 2)
    }
}

class SilenceRulesTest {
    @Test fun dbToLevel() {
        assertEquals(10, SilenceRules.level(-70).toInt()) // only true silence
        assertEquals(103, SilenceRules.level(-50).toInt()) // the default
        assertEquals(1036, SilenceRules.level(-30).toInt()) // about the player's own 1024
    }

    @Test fun percentToRetention() {
        assertEquals(.2f, SilenceRules.retention(80), 1e-6f) // the default keeps a fifth
        assertEquals(0f, SilenceRules.retention(100), 0f)
        assertEquals(.9f, SilenceRules.retention(10), 1e-6f)
    }
}
