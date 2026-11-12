package com.spotifry.desktop

import com.spotifry.music.player.ANALYSIS_RATE
import com.spotifry.music.player.BeatGrid
import com.spotifry.music.player.analyseBeats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The beats of [fromMs] to [toMs] of a song at [url] (a link or a file): mpv decodes that stretch to mono at the analysis
 * rate, as fast as it can (30 s take under half a second), and the phone's analysis reads it. Null when it can't be read
 * in [timeoutMs]. A grid that isn't [BeatGrid.whole] came from a read cut short.
 */
fun readBeats(url: String, fromMs: Long, toMs: Long, binary: String = "mpv", timeoutMs: Long = 15_000): BeatGrid? {
    val file = Files.createTempFile("spotifry-beats", ".raw")
    try {
        val p = ProcessBuilder(
            binary, "--no-config", "--no-video", "--no-terminal", "--ytdl=no", "--ao=pcm", "--ao-pcm-file=$file", "--ao-pcm-waveheader=no",
            "--audio-format=float", "--audio-channels=mono", "--audio-samplerate=$ANALYSIS_RATE",
            "--start=${fromMs / 1000.0}", "--end=${toMs / 1000.0}", url,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null }
        val bytes = Files.readAllBytes(file)
        val mono = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(mono)
        return analyseBeats(mono, mono.size, fromMs)?.copy(whole = p.exitValue() == 0)
    } finally { Files.deleteIfExists(file) }
}
