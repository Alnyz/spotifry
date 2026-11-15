package com.spotifry.desktop

import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.EqDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.spotifry.music.player.FILTER_HIGHPASS
import com.spotifry.music.player.FILTER_LOWPASS
import com.spotifry.music.player.SpatialSetting
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
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

    @Test fun skipQuietDefaultsAreTheOldFixedFilter() =
        assertEquals("lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=0.30:stop_threshold=-50dB]", Sound.skipQuiet())

    @Test fun skipQuietFollowsTheSettings() {
        assertEquals("lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=0.00:stop_threshold=-30dB]", Sound.skipQuiet(-30, 100))
        // a light cut never keeps more than the 1 s that starts a cut
        assertEquals("lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=1.00:stop_threshold=-70dB]", Sound.skipQuiet(-90, 10))
    }

    @Test fun noMixingInMeansNoSpatialFilter() = assertNull(Sound.spatial(SpatialSetting(700, 6, 0)))

    @Test fun spatialGainsFollowThePhonesMaths() {
        val f = Sound.spatial(SpatialSetting.NATURAL)!!
        // g = 10^(-6/20), so each ear keeps 1/(1+g) of itself and takes g/(1+g) of the other
        assertTrue(f, "c0=0.6661*c0" in f && "c0=0.3339*c1" in f && "lowpass=f=700:p=1" in f)
    }

    @Test fun sweepIsTwoFiltersAndNamesItsCommandTarget() {
        assertEquals("lavfi=[highpass=f=300.0:p=2,highpass=f=300.0:p=2]", Sound.sweep(FILTER_HIGHPASS, 300f))
        assertEquals("lowpass", Sound.sweepName(FILTER_LOWPASS))
    }

    /** Every filter string goes through the real mpv: one it rejects fails the run. Skipped without mpv and ffmpeg. */
    @Test fun mpvAcceptsEveryFilterAndTheyDoWhatTheyShould() {
        assumeTrue("mpv or ffmpeg not installed", listOf("mpv", "ffmpeg").all { runCatching { ProcessBuilder(it, "-version").start().waitFor() == 0 }.getOrDefault(false) })
        val dir = java.nio.file.Files.createTempDirectory("sound-test").toFile()
        try {
            fun make(name: String, graph: String) = java.io.File(dir, name).also {
                check(ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=f=5000:r=44100:d=3,volume=0.5", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono:d=3", "-filter_complex", graph, it.path).start().waitFor() == 0)
            }
            val leftOnly = make("left.wav", "[0][1]amerge=inputs=2")
            val both = make("both.wav", "[0]asplit[a][b];[a][b]amerge=inputs=2")
            fun rms(f: java.io.File, ch: Int): Double {
                val p = ProcessBuilder("ffmpeg", "-hide_banner", "-ss", "1", "-i", f.path, "-af", "pan=mono|c0=c$ch,astats", "-f", "null", "-").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText(); p.waitFor()
                return Regex("RMS level dB: (-?[0-9.]+|-inf)").find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: -120.0
            }
            fun heard(input: java.io.File, af: String): java.io.File {
                val out = java.io.File(dir, "out-${af.hashCode()}.wav")
                val r = ProcessBuilder("mpv", "--no-config", "--no-video", "--no-terminal", "--ao=pcm", "--ao-pcm-file=${out.path}", "--af=$af", input.path).start().waitFor()
                assertEquals("mpv rejected $af", 0, r)
                return out
            }
            // the other side of a left-only tone: the phone's gain for the crossing sound, then its one-pole dulling
            val s = SpatialSetting.NATURAL
            val out = heard(leftOnly, "@space:${Sound.spatial(s)}")
            val g = 10.0.pow(-s.crossDb / 20.0)
            val k = 1 - exp(-2 * PI * s.shadowHz / 44_100); val w = 2 * PI * 5000 / 44_100
            val dulled = k / hypot(1 - (1 - k) * cos(w), (1 - k) * sin(w))
            assertEquals("right over left", 20 * log10(g * dulled), rms(out, 1) - rms(out, 0), .5)
            // one ear's sound in the other, equal: mono is the same in both
            val mono = heard(leftOnly, "@mono:${Sound.MONO}")
            assertEquals(rms(mono, 0), rms(mono, 1), .1)
            assertEquals(rms(leftOnly, 0) - 6.0, rms(mono, 0), .3)
            heard(both, "@quiet:${Sound.skipQuiet(-60, 50)}")
            // 5 kHz through a 300 Hz low-pass sweep filter comes out far quieter; through a 300 Hz high-pass it stays
            assertTrue(rms(heard(both, "@xf:${Sound.sweep(FILTER_LOWPASS, 300f)}"), 0) < rms(both, 0) - 30)
            assertEquals(rms(both, 0), rms(heard(both, "@xf:${Sound.sweep(FILTER_HIGHPASS, 300f)}"), 0), .5)
        } finally { dir.deleteRecursively() }
    }

    private fun meanDb(hz: Int, chain: String?): Double {
        val af = listOfNotNull(chain, "volumedetect").joinToString(",")
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "sine=f=$hz:sample_rate=48000:duration=3", "-af", af, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        return Regex("mean_volume: (-?[0-9.]+) dB").find(out)!!.groupValues[1].toDouble()
    }
}
