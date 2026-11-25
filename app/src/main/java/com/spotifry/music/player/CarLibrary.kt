package com.spotifry.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotifry.music.data.source.Song

/**
 * What Android Auto (and any other media browser, like a watch or a car's own screen) can open: the songs played
 * lately, the saved ones, the most played and each playlist. Everything comes from what is already on the phone, so
 * nothing is looked up while driving.
 *
 * A song in a list carries the list in its id ("saved|abc"), so tapping it plays the whole list from that song.
 */
class CarLibrary(
    private val recent: suspend () -> List<Song>,
    private val saved: suspend () -> List<Song>,
    private val mostPlayed: suspend () -> List<Song>,
    private val playlists: suspend () -> List<Pair<Long, String>>,
    private val playlist: suspend (Long) -> List<Song>,
    private val song: suspend (String) -> Song?,
    private val search: suspend (String) -> List<Song>,
) {
    fun root(): MediaItem = folder(ROOT, "Spotifry", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The items under [parentId], or null when there is no such list. */
    suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        ROOT -> listOf(
            folder(RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(SAVED, "Saved", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(MOST, "Most played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )
        PLAYLISTS -> playlists().map { (id, name) -> folder("$PLAYLIST$id", name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
        else -> songsOf(parentId)?.map { playable(parentId, it) }
    }

    /** One item by its id: a list, or a song in a list. */
    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root()
        val parent = id.substringBefore(SEP, "")
        if (parent.isEmpty()) return children(ROOT)?.firstOrNull { it.mediaId == id }
            ?: children(PLAYLISTS)?.firstOrNull { it.mediaId == id }
        return song(id.substringAfter(SEP))?.let { playable(parent, it) }
    }

    /** One song picked in a list becomes that whole list, with the position of the song; null for anything else. */
    suspend fun wholeList(items: List<MediaItem>): Pair<List<MediaItem>, Int>? {
        val one = items.singleOrNull()?.mediaId ?: return null
        if (SEP !in one) return null
        val id = one.substringAfter(SEP)
        val list = songsOf(one.substringBefore(SEP)) ?: return null
        val at = list.indexOfFirst { it.id == id }
        return if (at < 0) null else list.map { it.toMediaItem() } to at
    }

    /**
     * What a voice request like "play Adele on Spotifry" plays. Nothing named: what was played lately. Otherwise the
     * songs on the phone whose title or artist match, and only when none do, a search like the app's own.
     */
    suspend fun forQuery(query: String): List<MediaItem> = found(query).map { it.toMediaItem() }

    /**
     * The search box in the car: the same songs as a voice request, shown as a list. They're kept until the next
     * search, so picking one plays the whole list from there, even songs that came from the web search.
     */
    suspend fun searchResults(query: String): List<MediaItem> {
        val songs = found(query).also { lastSearch = it }
        return songs.map { playable(SEARCH, it) }
    }

    private var lastSearch: List<Song> = emptyList()

    private suspend fun found(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return recent().ifEmpty { saved() }
        val mine = (saved() + recent() + mostPlayed()).distinctBy { it.id }
            .filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
        return mine.ifEmpty { runCatching { search(q) }.getOrDefault(emptyList()).take(20) }
    }

    /** An item that can play as it is (the app's own queue) is kept; one known only by its id is looked up. */
    suspend fun resolve(item: MediaItem): MediaItem? =
        if (item.localConfiguration != null) item else item.mediaId.substringAfter(SEP).let { id ->
            (song(id) ?: lastSearch.firstOrNull { it.id == id })?.toMediaItem()
        }

    private suspend fun songsOf(parentId: String): List<Song>? = when {
        parentId == RECENT -> recent()
        parentId == SAVED -> saved()
        parentId == MOST -> mostPlayed()
        parentId == SEARCH -> lastSearch
        parentId.startsWith(PLAYLIST) -> parentId.removePrefix(PLAYLIST).toLongOrNull()?.let { playlist(it) }
        else -> null
    }

    private fun playable(parent: String, s: Song): MediaItem {
        val item = s.toMediaItem()
        return item.buildUpon().setMediaId("$parent$SEP${s.id}")
            .setMediaMetadata(item.mediaMetadata.buildUpon().setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .build()
    }

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build())
        .build()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val SAVED = "saved"
        const val MOST = "most"
        const val PLAYLISTS = "playlists"
        const val SEARCH = "search"
        const val PLAYLIST = "playlist/"
        const val SEP = '|'
    }
}
