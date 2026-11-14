package com.spotifry.music.data

/** "Artist - Title", safe as a file name: what a copy is called before its extension. */
fun exportStem(title: String, artist: String): String {
    val by = artist.removeSuffix(" - Topic").trim()
    return (if (by.isEmpty()) title else "$by - $title").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}

/** "Artist - Title.ext", safe as a file name. */
fun exportName(title: String, artist: String, ext: String): String = exportStem(title, artist) + ".$ext"
