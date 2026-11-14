package com.spotifry.music.data.source

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    /** Cover file on the phone, set only for saved songs, so art still shows offline. */
    val localThumbPath: String? = null,
    /** Put in the queue by Smart shuffle, not by the listener. Lives only in the queue, never stored. */
    val suggested: Boolean = false,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
}

/**
 * A YouTube cover at the size it is shown, without black bars. Music covers take the size in the URL (160 px for rows,
 * 544 px for big covers). Video covers default to a 4:3 frame with bars; the 320 px and 720p frames have none.
 * Other URLs pass through.
 */
fun artAt(url: String, large: Boolean): String {
    Regex("^https://i\\.ytimg\\.com/vi/([^/]+)/").find(url)?.let {
        return "https://i.ytimg.com/vi/${it.groupValues[1]}/${if (large) "hq720" else "mqdefault"}.jpg"
    }
    return url.replace(Regex("=w\\d+-h\\d+"), if (large) "=w544-h544" else "=w160-h160")
}

fun largeArt(url: String) = artAt(url, large = true)

/** A song from a music file on the phone (id "local:<MediaStore id>"), not from YouTube. */
val Song.isLocal get() = id.startsWith("local:")
