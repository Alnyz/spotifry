package com.spotifry.desktop

/** The most a lossy format (Opus, AAC, MP3, Vorbis) carries; the same 512 the phone uses. */
const val MAX_LOSSY_KBPS = 512

/**
 * The playing song's real sound as a short label: "Opus 160 kbps", "Lossless" or "Hi-Res Lossless", from what mpv reports.
 * Only what the file really is, never "Hi-Res" on a lossy song. Null when the codec is unknown or not yet known.
 */
fun qualityLabel(codec: String?, bitrateBps: Double?, sampleRate: Int?): String? {
    val c = codec?.lowercase() ?: return null
    if (c == "flac" || c == "alac") return if ((sampleRate ?: 0) > 48_000) "Hi-Res Lossless" else "Lossless"
    val name = when (c) { "opus" -> "Opus"; "aac" -> "AAC"; "mp3" -> "MP3"; "vorbis" -> "Vorbis"; else -> return null }
    // a lossy song is never that high: a figure above this is another file's (a lossless copy's), so only the name is shown
    val kbps = bitrateBps?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() }?.takeIf { it <= MAX_LOSSY_KBPS }
    return if (kbps != null) "$name $kbps kbps" else name
}
