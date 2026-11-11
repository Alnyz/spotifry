package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.albumKind
import com.spotifry.music.data.source.toSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** [skipped]: entries that were deleted, private, live or too long. [capped]: a mix cut at [YouTubeImport.MIX_LIMIT]. */
data class YouTubePlaylist(val name: String, val songs: List<Song>, val skipped: Int, val capped: Boolean)

/** Reads a public YouTube or YouTube Music playlist. Its songs are YouTube songs already, so nothing has to be matched. */
object YouTubeImport {
    const val MIX_LIMIT = 500

    /** The playlist's YouTube address from a pasted link or a bare list id; null if it is neither. */
    fun playlistUrl(link: String): String? {
        val text = link.trim()
        val id = if (ID.matches(text)) text
        else Regex("(?:youtube\\.com|youtu\\.be)/\\S*?[?&]list=([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1) ?: return null
        // a mix is built around a song, so its link keeps that song
        val video = Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
        return if (id.startsWith("RD") && video != null) "https://www.youtube.com/watch?v=$video&list=$id"
        else "https://www.youtube.com/playlist?list=$id"
    }

    suspend fun fetch(url: String): YouTubePlaylist = withContext(Dispatchers.IO) {
        val id = url.substringAfter("list=")
        // liked songs, watch later: only their owner can open them
        if (id in SIGN_IN) throw PrivateContentException("needs sign-in")
        val ex = YouTube.getPlaylistExtractor(url)
        ex.fetchPage()
        val (items, capped) = collect(ex.initialPage, ex::getPage, if (id.startsWith("RD")) MIX_LIMIT else Int.MAX_VALUE)
        val songs = items.toSongs()
        // repeats (a mix plays the same songs again and again) are dropped but not counted as skipped
        YouTubePlaylist(albumKind(ex.name.trim()).second.ifEmpty { "YouTube playlist" }, songs.distinctBy { it.id }, items.size - songs.size, capped)
    }

    /** Every page in turn, up to [limit] entries. A page with nothing on it ends it too, so a broken next link can't loop. */
    internal fun collect(first: InfoItemsPage<StreamInfoItem>, more: (Page) -> InfoItemsPage<StreamInfoItem>, limit: Int): Pair<List<StreamInfoItem>, Boolean> {
        val items = first.items.toMutableList()
        var page = first
        while (page.hasNextPage() && items.size < limit) {
            page = more(page.nextPage!!)
            if (page.items.isEmpty()) break
            items += page.items
        }
        return items.take(limit) to (items.size > limit || (items.size == limit && page.hasNextPage()))
    }

    private val ID = Regex("(?:PL|OLAK5uy_|RD|UU|FL)[A-Za-z0-9_-]{10,}|LL|WL|LM")
    private val SIGN_IN = setOf("LL", "WL", "LM")
}
