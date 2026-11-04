package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal

/** The words used when a song or playlist is sent as text, and reading them back in Spotifry. */
object ShareText {
    private const val NO_LINK = "(a file on my phone, no link)"
    private val header = Regex("^(.+) · \\d+ songs?, shared from Spotifry$")
    private val idIn = Regex("(?:youtu\\.be/|[?&]v=|/shorts/)([A-Za-z0-9_-]{11})")

    fun link(song: Song) = "https://youtu.be/${song.id}"

    fun song(song: Song) = "${line(song)}\n${link(song)}"

    fun playlist(name: String, songs: List<Song>) = buildString {
        append("$name · ${songs.size} ${if (songs.size == 1) "song" else "songs"}, shared from Spotifry\n")
        songs.forEachIndexed { i, s -> append("\n${i + 1}. ${line(s)}\n${if (s.isLocal) NO_LINK else link(s)}") }
    }

    private fun line(s: Song) = if (s.artist.isBlank()) s.title else "${s.title} – ${s.artist}"

    /** What a shared message holds: the playlist name if it had one, the songs, and how many were files on their phone. */
    data class Shared(val name: String?, val songs: List<Song>, val phoneFiles: Int) {
        /** The name the import starts with: the playlist's, else the song's when it is one song. */
        val title get() = name ?: songs.singleOrNull()?.title ?: "Shared songs"
    }

    /**
     * Reads a message made by [song] or [playlist]: each link with the song name on the line above it.
     * Links with no name above them (a bare YouTube link) are skipped; null when nothing was found.
     */
    fun read(text: String): Shared? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.firstOrNull()?.let { header.find(it)?.groupValues?.get(1) }
        val songs = mutableListOf<Song>()
        var files = 0
        lines.forEachIndexed { i, l ->
            val above = lines.getOrNull(i - 1)?.takeIf { i > 0 && idIn.find(it) == null && it != NO_LINK && header.find(it) == null }
            if (l == NO_LINK) { if (above != null) files++; return@forEachIndexed }
            val id = idIn.find(l)?.groupValues?.get(1) ?: return@forEachIndexed
            if (above == null || songs.any { it.id == id }) return@forEachIndexed
            val named = above.replace(Regex("^\\d+\\.\\s*"), "")
            val cut = named.lastIndexOf(" – ")
            songs += Song(
                id, if (cut > 0) named.substring(0, cut) else named, if (cut > 0) named.substring(cut + 3) else "", 0,
                "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            )
        }
        return if (songs.isEmpty()) null else Shared(name, songs, files)
    }

    /** [name], or "name (2)", "name (3)"… if a playlist already has it. */
    fun freeName(name: String, taken: Collection<String>): String {
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }
}
