package com.spotifry.music.ui.saved

import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.exportStem
import com.spotifry.music.data.source.Song
import kotlin.math.abs

/** What the Saved page lists. There is no "liked" in this app: a song is either saved on the phone for offline, or it isn't. */
enum class SavedFilter(val label: String, val queueName: String) {
    All("All", "Your music"),
    Offline("Saved offline", "Saved"),
    Phone("On this phone", "On this phone"),
    Lately("Played lately", "Played lately"),
}

/** One row of the page: [saved] is the check mark for a song saved on the phone for offline. */
data class SavedRow(val song: Song, val saved: Boolean)

/** The rows for one [filter], so the page never shows a list under the wrong chip. */
data class SavedView(val filter: SavedFilter = SavedFilter.All, val rows: List<SavedRow> = emptyList())

/** Case folded the way equalsIgnoreCase does, one letter at a time, so ı and I, or σ and ς, count as the same. */
private fun String.folded() = trim().map { it.uppercaseChar().lowercaseChar() }.joinToString("")
private fun Song.nameKey() = title.folded() + '\u0000' + artist.folded()

/**
 * [saved] newest first, then [phone] in its order, left as they come. [recent] is the play history, newest first; only
 * songs this page can list count, so every row plays. A song both saved and on the phone shows once, as the saved copy.
 */
fun filterSaved(filter: SavedFilter, saved: List<SongEntity>, phone: List<Song>, recent: List<SongEntity>): List<SavedRow> {
    val savedRows = saved.map { SavedRow(it.toSong(), true) }
    return when (filter) {
        SavedFilter.Offline -> savedRows
        SavedFilter.Phone -> phone.map { SavedRow(it, false) }
        SavedFilter.All -> {
            // the same song saved from YouTube and found as a file: same name and artist, and a length within a few seconds
            val lengths = savedRows.groupBy({ it.song.nameKey() }, { it.song.durationSec })
            // a copy made by Export has no tags of its own (an Opus one), so Android names it after its file: "Artist - Title"
            val copies = savedRows.groupBy({ exportStem(it.song.title, it.song.artist).folded() }, { it.song.durationSec })
            val phoneRows = phone.filter { p -> (lengths[p.nameKey()].orEmpty() + copies[p.title.folded()].orEmpty()).none { abs(it - p.durationSec) <= 3 } }.map { SavedRow(it, false) }
            (savedRows + phoneRows).distinctBy { it.song.id }
        }
        SavedFilter.Lately -> {
            val byId = (savedRows + phone.map { SavedRow(it, false) }).associateBy { it.song.id }
            recent.mapNotNull { byId[it.id] }
        }
    }
}
