package com.spotifry.desktop

/**
 * The playing song's real sound as a short label: "Opus 160 kbps", "Lossless" or "Hi-Res Lossless", from what mpv reports.
 * Only what the file really is, never "Hi-Res" on a lossy song. Null when the codec is unknown or not yet known.
 */
fun qualityLabel(codec: String?, bitrateBps: Double?, sampleRate: Int?): String? {
    val c = codec?.lowercase() ?: return null
    if (c == "flac" || c == "alac") return if ((sampleRate ?: 0) > 48_000) "Hi-Res Lossless" else "Lossless"
    val name = when (c) { "opus" -> "Opus"; "aac" -> "AAC"; "mp3" -> "MP3"; "vorbis" -> "Vorbis"; else -> return null }
    val kbps = bitrateBps?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() }
    return if (kbps != null) "$name $kbps kbps" else name
}
