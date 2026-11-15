package com.spotifry.desktop

import java.io.File

/** What Spotifry keeps on the disk, in bytes. The music folder's own songs are not Spotifry's and not counted. */
data class StorageUse(val songs: Long, val covers: Long, val replay: Long, val database: Long) {
    val total get() = songs + covers + replay + database
}

/**
 * Measures the app's data folder: saved songs and their covers (both live in songs/), the replay cache (replay/), and the
 * database with the small settings files beside it. Reads the disk, so call it off the UI thread.
 */
fun measureStorage(dir: File): StorageUse {
    fun size(f: File) = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val (covers, songs) = File(dir, "songs").walkTopDown().filter { it.isFile }.partition { it.extension == "jpg" }
    val database = dir.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("music.db") || it.extension == "json") }.sumOf { it.length() }
    return StorageUse(songs.sumOf { it.length() }, covers.sumOf { it.length() }, size(File(dir, "replay")), database)
}

internal fun sizeText(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ENGLISH, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${bytes shr 10} KB"
}
