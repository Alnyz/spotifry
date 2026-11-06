package com.spotifry.music.ui.saved

import com.spotifry.music.data.db.SongEntity
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

private fun Song.nameKey() = title.trim().lowercase() + '\u0000' + artist.trim().lowercase()

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
            val phoneRows = phone.filter { p -> lengths[p.nameKey()].orEmpty().none { abs(it - p.durationSec) <= 3 } }.map { SavedRow(it, false) }
            (savedRows + phoneRows).distinctBy { it.song.id }
        }
        SavedFilter.Lately -> {
            val byId = (savedRows + phone.map { SavedRow(it, false) }).associateBy { it.song.id }
            recent.mapNotNull { byId[it.id] }
        }
    }
}
