package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * The playing song's real sound, as a short label for Now playing: "Opus 160 kbps", "Lossless" or "Hi-Res Lossless".
 * Only what the file really is, never "Hi-Res" on a YouTube song. [knownKbps] is YouTube's own figure, used
 * when the file doesn't carry its bitrate (Opus in WebM doesn't). Null when the format is unknown.
 */
fun qualityLabel(f: Format?, knownKbps: Int? = null): String? {
    val mime = f?.sampleMimeType ?: return null
    val name = when (mime) {
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_RAW -> "WAV"
        else -> return null
    }
    if (mime == MimeTypes.AUDIO_FLAC || mime == MimeTypes.AUDIO_ALAC || mime == MimeTypes.AUDIO_RAW) {
        val bits = when (f.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        // FLAC and ALAC get a plain name; "Hi-Res" only when the file really is above CD quality
        if (mime != MimeTypes.AUDIO_RAW) return if ((bits ?: 16) > 16 || f.sampleRate > 48_000) "Hi-Res Lossless" else "Lossless"
        val tail = listOfNotNull(bits?.let { "$it-bit" }, khz(f.sampleRate)).joinToString(" · ")
        return if (tail.isEmpty()) name else "$name $tail"
    }
    val kbps = (f.averageBitrate.takeIf { it > 0 } ?: f.bitrate.takeIf { it > 0 })?.let { (it + 500) / 1000 } ?: knownKbps?.takeIf { it > 0 }
    return if (kbps != null) "$name $kbps kbps" else name
}

private fun khz(hz: Int): String? = when {
    hz <= 0 -> null
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> "%.1f kHz".format(java.util.Locale.ROOT, hz / 1000f)
}
