package com.spotifry.music.data

/**
 * The name a lyrics site has filed a song under, from the name YouTube gave it. Taken off: credits and packaging
 * ("(feat. X)", "(Official Video)", "| OFFICIAL VIDEO", a leading "Artist - "). Left on: anything that names another
 * recording ("(Remix)", "(Live)", "(Acoustic)", "(Sped Up)"), because lyrics for the wrong recording are worse than none.
 * Inspired by BitChord's lyrics search.
 */
fun titleForLyrics(title: String, artist: String = ""): String {
    var name = title
    TITLE_NOISE.forEach { name = it.replace(name, " ") }
    name = name.replace(SPACES, " ").trim().trimEnd(',', '-', '–', '—').trim()
    // "Artist - Song": the part before the dash is dropped only when it names the artist
    val dash = name.indexOf(" - ")
    if (dash > 0) {
        val words = artist.lowercase().split(NOT_WORD).filter { it.length >= 3 }
        val left = name.substring(0, dash).lowercase().split(NOT_WORD)
        if (words.any { it in left }) name = name.substring(dash + 3).trim()
    }
    return name.ifBlank { title.trim() }
}

/** The artist without YouTube's channel labels: "Nella Kharisma Official" and "Artist - Topic" become the artist. */
fun artistForLyrics(artist: String): String =
    artist.removeSuffix(" - Topic").replace(ARTIST_LABEL, "").trim().ifBlank { artist.trim() }

private val SPACES = Regex("""\s+""")
private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
private val ARTIST_LABEL = Regex("""\s*[-–|]?\s*\b(official(\s+(channel|artist))?|vevo)\s*$""", RegexOption.IGNORE_CASE)
private val TITLE_NOISE = listOf(
    Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
    Regex("""\s*[(\[]\s*(official\s*)?(music\s*)?(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[)\]]""", RegexOption.IGNORE_CASE),
    Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
    // everything after a pipe is the uploader's packaging: "Song | Official Video | Artist"
    Regex("""\s*[|｜].*$"""),
)
