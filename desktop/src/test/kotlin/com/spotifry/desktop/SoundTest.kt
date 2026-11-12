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

    @Test fun thePictureShowsAToneWhereItIs() {
        assumeTrue("ffmpeg not installed", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val f = java.io.File.createTempFile("tone", ".wav")
        ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=f=1000:sample_rate=44100:duration=4", f.path).start().waitFor()
        val pic = SongPicture(f.path)
        org.junit.Assert.assertTrue(pic.load())
        val bands = FloatArray(com.spotifry.music.player.Spectrum.BANDS)
        org.junit.Assert.assertTrue(pic.bandsAt(2_000, bands))
        val loudest = bands.indices.maxBy { bands[it] }
        val hz = com.spotifry.music.player.Spectrum.bandCenterHz(loudest)
        org.junit.Assert.assertTrue("loudest band at $hz Hz", hz in 850f..1200f)
        f.delete()
    }

    private fun meanDb(hz: Int, chain: String?): Double {
        val af = listOfNotNull(chain, "volumedetect").joinToString(",")
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "sine=f=$hz:sample_rate=48000:duration=3", "-af", af, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        return Regex("mean_volume: (-?[0-9.]+) dB").find(out)!!.groupValues[1].toDouble()
    }
}
