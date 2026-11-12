package com.spotifry.desktop

import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.EqDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.log10

class SoundTest {
    @Test fun flatIsNoFilter() = assertNull(Sound.equalizer(List(10) { 0 }))

    /** Tones through ffmpeg's filters land where the phone's maths says they do. Skipped without ffmpeg. */
    @Test fun theFilterSoundsLikeThePhonesCurve() {
        assumeTrue("ffmpeg not installed", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val levels = EQ_PRESETS.first { it.first == "Rock" }.second
        val chain = Sound.equalizer(levels)!!.removePrefix("lavfi=[").removeSuffix("]")
        val design = EqDesign(levels)
        for (hz in listOf(60, 440, 1000, 3000, 12000)) {
            val heard = meanDb(hz, chain) - meanDb(hz, null)
            val expected = design.responseDb(hz.toDouble()) + 20 * log10(design.preamp)
            assertEquals("at $hz Hz", expected, heard, .3)
        }
    }

    private fun meanDb(hz: Int, chain: String?): Double {
        val af = listOfNotNull(chain, "volumedetect").joinToString(",")
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "sine=f=$hz:sample_rate=48000:duration=3", "-af", af, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        return Regex("mean_volume: (-?[0-9.]+) dB").find(out)!!.groupValues[1].toDouble()
    }
}
