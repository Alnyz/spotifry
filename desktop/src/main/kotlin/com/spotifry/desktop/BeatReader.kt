package com.spotifry.desktop

import com.spotifry.music.player.ANALYSIS_RATE
import com.spotifry.music.player.BeatGrid
import com.spotifry.music.player.Spectrum
import com.spotifry.music.player.analyseBeats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A song at [url] (a link or a file) from [fromMs] to [toMs] (null: the end), as mono samples at [rate]: mpv decodes as
 * fast as it can (30 s take under half a second). The flag says the read went to the end, not cut short. Null when it
 * can't be read in [timeoutMs].
 */
fun decodeMono(url: String, fromMs: Long, toMs: Long?, rate: Int, binary: String = "mpv", timeoutMs: Long = 15_000): Pair<FloatArray, Boolean>? {
    val file = Files.createTempFile("spotifry-decode", ".raw")
    try {
        val p = ProcessBuilder(
            listOfNotNull(
                binary, "--no-config", "--no-video", "--no-terminal", "--ytdl=no", "--ao=pcm", "--ao-pcm-file=$file", "--ao-pcm-waveheader=no",
                "--audio-format=float", "--audio-channels=mono", "--audio-samplerate=$rate",
                "--start=${fromMs / 1000.0}", toMs?.let { "--end=${it / 1000.0}" }, url,
            ),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null }
        val bytes = Files.readAllBytes(file)
        val mono = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(mono)
        return mono to (p.exitValue() == 0)
    } finally { Files.deleteIfExists(file) }
}

/** The beats of [fromMs] to [toMs] of a song, read by the phone's analysis. A grid that isn't [BeatGrid.whole] came from a read cut short. */
fun readBeats(url: String, fromMs: Long, toMs: Long, binary: String = "mpv"): BeatGrid? {
    val (mono, whole) = decodeMono(url, fromMs, toMs, ANALYSIS_RATE, binary) ?: return null
    return analyseBeats(mono, mono.size, fromMs)?.copy(whole = whole)
}

/** High enough that the top band, up to 16 kHz, is in it. */
const val PICTURE_RATE = 32_000

/** The whole song heard, decoded once, so the Equalizer can show its sound at any moment, in step with the music. */
class SongPicture(val url: String) {
    @Volatile var samples: FloatArray? = null; private set
    private val re = FloatArray(Spectrum.SIZE)
    private val im = FloatArray(Spectrum.SIZE)
    private val window = FloatArray(Spectrum.SIZE) { (.5 - .5 * cos(2 * PI * it / Spectrum.SIZE)).toFloat() }

    /** Takes a few seconds for a whole song; false if it can't be read. */
    fun load(): Boolean { samples = decodeMono(url, 0, null, PICTURE_RATE, timeoutMs = 60_000)?.first; return samples != null }

    /** Levels in dB of each [Spectrum] band just before [ms] of the song, the same measure as the phone's; false until loaded. */
    fun bandsAt(ms: Long, out: FloatArray): Boolean {
        val s = samples ?: return false
        val end = (ms * PICTURE_RATE / 1000).toInt()
        for (i in 0 until Spectrum.SIZE) { re[i] = s.getOrElse(end - Spectrum.SIZE + i) { 0f } * window[i]; im[i] = 0f }
        Spectrum.fft(re, im)
        for (b in 0 until Spectrum.BANDS) {
            val lo = (Spectrum.bandEdgeHz(b) * Spectrum.SIZE / PICTURE_RATE).toInt().coerceIn(1, Spectrum.SIZE / 2 - 1)
            val hi = (Spectrum.bandEdgeHz(b + 1) * Spectrum.SIZE / PICTURE_RATE).toInt().coerceIn(lo, Spectrum.SIZE / 2 - 1)
            var peak = 0f
            for (k in lo..hi) peak = maxOf(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            out[b] = (20 * log10(peak / (Spectrum.SIZE / 4f) + 1e-6f)).coerceAtLeast(Spectrum.FLOOR_DB)
        }
        return true
    }
}
