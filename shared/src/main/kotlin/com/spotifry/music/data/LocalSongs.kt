package com.spotifry.music.data

import com.spotifry.music.data.source.Song

/** Up to [max] songs whose name and artist together hold every word of [query], in list order. */
fun localMatches(songs: List<Song>, query: String, max: Int = 3): List<Song> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return songs.asSequence().filter { s -> words.all { w -> s.title.contains(w, true) || s.artist.contains(w, true) } }.take(max).toList()
}

/** What decides whether a MediaStore audio row is music: its length, type, place and the flags Android keeps. */
data class AudioRow(val durationMs: Long, val mime: String?, val path: String?, val system: Boolean, val recording: Boolean)

/** True for clips under 30 seconds, WAV files, recordings and voice notes, and ringtones, notifications and alarms. */
fun AudioRow.isNonMusic(): Boolean {
    val p = path.orEmpty().lowercase()
    val folder = p.substringBeforeLast('/')
    return durationMs < 30_000 || system || recording ||
        mime?.lowercase() in setOf("audio/x-wav", "audio/wav", "audio/wave", "audio/vnd.wave") || p.endsWith(".wav") ||
        listOf("recording", "recorder", "voice").any { folder.contains(it) }
}
