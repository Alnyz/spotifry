package com.spotifry.music.data

/** The lettering a playlist or artist name can be set in. The key is what is stored. */
enum class NameStyle(val key: String, val label: String) {
    CLASSIC("classic", "Classic"), BRUSH("brush", "Brush"), GOTHIC("gothic", "Gothic"), BLOCK("block", "Block"),
    OUTLINE("outline", "Outline"), SCRIPT("script", "Script"), NEON("neon", "Neon"), PIXEL("pixel", "Pixel");

    companion object {
        /** A missing key, or one from a newer app, is Classic. */
        fun of(key: String?): NameStyle = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/** Which of the stored choices shows: the one picked last. */
enum class NameShow(val key: String) {
    TEXT("text"), OWN("own"), OFFICIAL("official");

    companion object {
        /** Null is the default for the page. */
        fun of(key: String?): NameShow? = entries.firstOrNull { it.key == key }
    }
}

/** Everything chosen for one name. The choices not showing stay stored, so switching back is one tap. */
data class NameLook(val show: NameShow? = null, val style: NameStyle = NameStyle.CLASSIC, val logoFile: String? = null, val tint: Boolean = true)

/** What the header draws. [tint]: the logo is painted in the page's text colour. */
sealed interface Shown {
    data class Text(val style: NameStyle) : Shown
    data class Own(val file: String, val tint: Boolean) : Shown
    data class Official(val tint: Boolean) : Shown
}

fun shownForPlaylist(look: NameLook): Shown =
    if (look.show == NameShow.OWN && look.logoFile != null) Shown.Own(look.logoFile, look.tint) else Shown.Text(look.style)

/** Only favourite artists keep a look of their own; the rest, and a favourite with nothing picked, get the official logo when there is one. */
fun shownForArtist(look: NameLook?, favourite: Boolean, officialFound: Boolean): Shown {
    val l = look?.takeIf { favourite } ?: NameLook()
    return when (l.show) {
        NameShow.TEXT -> Shown.Text(l.style)
        NameShow.OWN -> if (l.logoFile != null) Shown.Own(l.logoFile, l.tint) else Shown.Text(l.style)
        NameShow.OFFICIAL, null -> if (officialFound) Shown.Official(l.tint) else Shown.Text(l.style)
    }
}

private fun field(s: String) = s.replace(Regex("[\\t\\n]"), " ")

/** One line per artist key: key, show, style, logo file, tint (1 or 0), tab-separated. */
fun encodeArtistNames(m: Map<String, NameLook>): String = m.entries.joinToString("\n") { (k, l) ->
    listOf(field(k), l.show?.key.orEmpty(), l.style.key, field(l.logoFile.orEmpty()), if (l.tint) "1" else "0").joinToString("\t")
}

fun decodeArtistNames(s: String?): Map<String, NameLook> = s.orEmpty().lines().mapNotNull { line ->
    val p = line.split('\t')
    if (p.size != 5 || p[0].isBlank()) return@mapNotNull null
    p[0] to NameLook(NameShow.of(p[1]), NameStyle.of(p[2]), p[3].ifEmpty { null }, p[4] != "0")
}.toMap()
