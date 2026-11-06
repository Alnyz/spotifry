package com.spotifry.music.data.source

data class ResolvedStream(val url: String, val mimeType: String, val bitrateKbps: Int, val expiresAtMs: Long)

/** What a search looks for. The choice lasts while the app is open; recent searches don't keep it. */
enum class SearchType(val label: String) {
    SONGS("Songs"), VIDEOS("Music videos"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists");
    /** The word in messages: "No albums found…". */
    val word get() = label.lowercase()
}

/** An album, artist or playlist in the results: [by] is the artist or the playlist's maker, blank for an artist. */
data class PageLink(val type: SearchType, val url: String, val name: String, val by: String, val thumbnailUrl: String?)

/**
 * An opened album, playlist or artist. [kind] is "Album", "Single", "EP", "Playlist" or "Artist". An artist's [songs]
 * are what one search page finds from its channel (the page shows the first few until asked for all), with its [albums]
 * alongside.
 */
data class PageContent(val name: String, val by: String, val kind: String, val thumbnailUrl: String?, val songs: List<Song>, val albums: List<PageLink> = emptyList())

interface MusicSource {
    suspend fun search(query: String): List<Song>
    /** [low]: the smallest audio stream instead of the best one (data saver, low-quality saves). */
    suspend fun resolve(song: Song, low: Boolean = false): ResolvedStream
    suspend fun related(song: Song): List<Song>
    suspend fun searchVideos(query: String): List<Song> = emptyList()
    /** Albums, artists or playlists, for those [type]s. */
    suspend fun searchPages(query: String, type: SearchType): List<PageLink> = emptyList()
    suspend fun page(link: PageLink): PageContent = error("can't open ${link.url}")
    /** How many of a playlist's songs can play, going by what's quick to read, to check it's worth showing. */
    suspend fun playableSongs(link: PageLink): Int = page(link).songs.size
}

/**
 * The song exists but has no audio to play (removed, blocked, age-restricted, no audio stream). A FileNotFoundException,
 * so the player doesn't retry it (about 8 s of retries before) and moves on to the next song at once.
 */
class NoAudioException(message: String) : java.io.FileNotFoundException(message)
