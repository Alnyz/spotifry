package com.spotifry.music.data

import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.PlaylistDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import kotlinx.coroutines.flow.first

/** An artist picked on the first-launch screen. [url] is blank when it was picked offline and not looked up yet. */
data class PickedArtist(val name: String, val url: String, val thumbnailUrl: String?) {
    val link get() = PageLink(SearchType.ARTISTS, url, name, "", thumbnailUrl)
    /** Same artist, whether picked from the starter list (name only) or from a search (with its page). */
    val key get() = name.trim().lowercase()
}

const val MIN_ARTISTS = 3

/** The list that comes with the app. Not based on the listener; mixes pop, rock, Indonesian and Japanese music. */
val STARTER_ARTISTS = listOf(
    "Taylor Swift", "Tulus", "Coldplay", "YOASOBI", "Billie Eilish", "Raisa", "Radiohead", "Bad Bunny", "Hindia",
    "Dua Lipa", "The Weeknd", "Nadin Amizah", "Ed Sheeran", "Bruno Mars", "Queen",
)

/**
 * A fresh install has nothing played, saved or made. Anyone who updated from an older version has history, songs or
 * playlists, and is never shown the first-launch screens, whatever the "seen" flag says.
 */
suspend fun isFreshInstall(history: HistoryDao, songs: SongDao, playlists: PlaylistDao): Boolean =
    history.observeRecent().first().isEmpty() && songs.savedCount() == 0 && playlists.observePlaylists().first().isEmpty()
