package com.spotifry.desktop

import java.io.File

/** "Artist - Title.ext", safe as a file name on Linux and Windows. */
fun exportName(title: String, artist: String, ext: String): String {
    val by = artist.removeSuffix(" - Topic").trim()
    return (if (by.isEmpty()) title else "$by - $title").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim() + ".$ext"
}

/**
 * Copies a saved song into [music]/Spotifry, where other players and file managers see it. An earlier copy is never
 * overwritten: a second one is named "Name (1).ext". Null when the copy could not be made. Run off the window's thread.
 */
fun exportToMusic(music: File, saved: File, title: String, artist: String): File? {
    if (!saved.isFile) return null
    val dir = File(music, "Spotifry")
    return try {
        dir.mkdirs()
        val name = exportName(title, artist, saved.extension)
        val stem = name.removeSuffix("." + saved.extension)
        val target = generateSequence(0) { it + 1 }.map { n -> File(dir, if (n == 0) name else "$stem ($n).${saved.extension}") }.first { !it.exists() }
        // written under another name first, so a copy cut short never looks like a finished song
        val part = File(dir, "${target.name}.part")
        saved.copyTo(part, overwrite = true)
        if (part.renameTo(target)) target else { part.delete(); null }
    } catch (_: java.io.IOException) { null }
}
