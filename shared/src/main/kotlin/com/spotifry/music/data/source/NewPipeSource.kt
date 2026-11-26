package com.spotifry.music.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Looks up songs and audio streams on YouTube through NewPipeExtractor. Needs `NewPipe.init` first. */
class NewPipeSource : MusicSource {
    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) { find(query, SearchType.SONGS).toSongs() }

    override suspend fun searchVideos(query: String): List<Song> = withContext(Dispatchers.IO) { find(query, SearchType.VIDEOS).toSongs() }

    override suspend fun searchPages(query: String, type: SearchType): List<PageLink> =
        withContext(Dispatchers.IO) { find(query, type).mapNotNull { linkOf(it, type) } }

    /**
     * An album or playlist with all its songs, or an artist. YouTube Music artists come through as "Topic" channels,
     * which NewPipe opens with no songs or albums in them, so an artist's page is made of two searches for their
     * name, kept to what that channel put out.
     */
    override suspend fun page(link: PageLink): PageContent = withContext(Dispatchers.IO) {
        if (link.type == SearchType.ARTISTS) {
            val songs = find(link.name, SearchType.SONGS).from(link.url).toSongs()
            val albums = find(link.name, SearchType.ALBUMS).from(link.url).mapNotNull { linkOf(it, SearchType.ALBUMS) }
            return@withContext PageContent(link.name, "", "Artist", link.thumbnailUrl, songs, albums)
        }
        val info = PlaylistInfo.getInfo(YouTube, link.url)
        val items = info.relatedItems.toMutableList()
        var next: Page? = info.nextPage
        // ponytail: up to 10 pages (about 1000 songs) loaded at once; load as you scroll if huge playlists matter
        repeat(9) {
            if (!Page.isValid(next)) return@repeat
            val more = PlaylistInfo.getMoreItems(YouTube, link.url, next)
            items += more.items
            next = more.nextPage
        }
        val (kind, name) = if (link.type == SearchType.ALBUMS) albumKind(info.name) else "Playlist" to info.name
        PageContent(
            link.name.ifBlank { name }, link.by.ifBlank { info.uploaderName.orEmpty() }, kind,
            link.thumbnailUrl ?: info.thumbnails.maxByOrNull { it.height }?.url, items.toSongs(),
        )
    }

    /** Only the first page, a single request. Mixes hours long count for nothing here, as they do in [toSongs]. */
    override suspend fun playableSongs(link: PageLink): Int = withContext(Dispatchers.IO) {
        PlaylistInfo.getInfo(YouTube, link.url).relatedItems.toSongs().size
    }

    private fun find(query: String, type: SearchType): List<InfoItem> {
        val ex = YouTube.getSearchExtractor(query, listOf(filterOf(type)), "")
        ex.fetchPage()
        return ex.initialPage.items
    }

    /** A video YouTube has since removed is swapped for a working upload of the same song, found by name; YouTube Music does the same itself. */
    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = try {
        resolveId(song, low)
    } catch (e: NoAudioException) {
        // ponytail: the swap is looked up again each time the song loads, not saved into the playlist; save it if that gets slow
        val copy = if (song.isLocal) null else runCatching { copyOf(song, search("${song.title} ${song.artist}")) }.getOrNull()
        if (copy == null) throw e
        runCatching { resolveId(copy, low) }.getOrElse { throw e }
    }

    private suspend fun resolveId(song: Song, low: Boolean): ResolvedStream = withContext(Dispatchers.IO) {
        val info = try { StreamInfo.getInfo(YouTube, song.url) } catch (e: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
            throw NoAudioException("${song.id}: ${e.message}")
        }
        val known = info.audioStreams.filter { it.averageBitrate > 0 }.ifEmpty { info.audioStreams }
        val a = (if (low) known.minByOrNull { it.averageBitrate } else known.maxByOrNull { it.averageBitrate }) ?: throw NoAudioException("no audio stream for ${song.id}")
        val url = a.content
        ResolvedStream(url, a.format?.mimeType ?: "audio/mp4", a.averageBitrate, expiryOf(url))
    }

    /**
     * YouTube Music's mix for the song (the "RDAMVM" list), which holds only music. YouTube's plain related videos
     * also hold talks and podcasts, so they are only the fallback when the mix can't be loaded.
     */
    override suspend fun related(song: Song): List<Song> = withContext(Dispatchers.IO) {
        // a file on the phone has no YouTube id: find the song on YouTube by name and artist, then its similar songs
        if (song.isLocal) return@withContext search("${song.title} ${song.artist}").firstOrNull()?.let { related(it) }.orEmpty()
        val mix = runCatching { PlaylistInfo.getInfo(YouTube, "${song.url}&list=RDAMVM${song.id}").relatedItems.toSongs() }.getOrNull()
        val found = (mix?.takeIf { it.size > 1 } ?: StreamInfo.getInfo(YouTube, song.url).relatedItems.toSongs()).filter { it.id != song.id }
        cleanSongsFor(found).filter { it.id != song.id }
    }

    /**
     * Music video uploads ("Artist X Artist - Song (Official Music Video)") swapped for the plain song, found in YouTube
     * Music's songs: a clean name, the song's own length and no video intro, so lyrics match too. A video with no song
     * version, or a search that fails or takes over 4 s, stays as it was.
     */
    private suspend fun cleanSongsFor(songs: List<Song>): List<Song> = kotlinx.coroutines.coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val swapped = songs.map { s ->
            async {
                if (!looksLikeVideo(s)) return@async s
                gate.withPermit {
                    kotlinx.coroutines.withTimeoutOrNull(4_000) {
                        runCatching { songVersionOf(s, find(songQuery(s), SearchType.SONGS).toSongs()) }.getOrNull()
                    }
                } ?: s
            }
        }.awaitAll()
        swapped.distinctBy { it.id }
    }

    private fun expiryOf(url: String): Long {
        val seconds = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (seconds != null) seconds * 1000 else System.currentTimeMillis() + FALLBACK_TTL_MS
    }

    private companion object {
        const val FALLBACK_TTL_MS = 5 * 60 * 60 * 1000L
    }
}

private const val MAX_SECONDS = 1200L

/** Found videos as songs. Drops live streams (duration -1) and anything over 20 minutes. */
fun List<InfoItem>.toSongs(): List<Song> = filterIsInstance<StreamInfoItem>().mapNotNull { item ->
    if (item.duration !in 1..MAX_SECONDS) return@mapNotNull null
    val id = YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url)
    Song(id, item.name, item.uploaderName.orEmpty(), item.duration, item.thumbnails.maxByOrNull { it.height }?.url)
}

private fun filterOf(type: SearchType) = when (type) {
    SearchType.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
    SearchType.VIDEOS -> YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
    SearchType.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
    SearchType.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    SearchType.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
}

/** A found album, playlist or artist; null for anything else. Artists lose YouTube's " - Topic" ending. */
fun linkOf(item: InfoItem, type: SearchType): PageLink? {
    val art = item.thumbnails.maxByOrNull { it.height }?.url
    return when (item) {
        is PlaylistInfoItem -> PageLink(type, item.url, item.name, item.uploaderName.orEmpty(), art, item.streamCount.takeIf { it > 0 }?.toInt())
        is ChannelInfoItem -> PageLink(type, item.url, item.name.removeSuffix(" - Topic"), "", art)
        else -> null
    }
}

/** Only what the channel at [channelUrl] put out: an artist's own songs and albums in a search for their name. */
fun List<InfoItem>.from(channelUrl: String) = filter {
    (it as? StreamInfoItem)?.uploaderUrl == channelUrl || (it as? PlaylistInfoItem)?.uploaderUrl == channelUrl
}

/** YouTube names an album page "Album – Kid A" (or Single, EP): the kind and the bare name. */
fun albumKind(title: String): Pair<String, String> =
    Regex("^(Album|Single|EP) – (.+)$").find(title)?.destructured?.let { (k, n) -> k to n } ?: ("Album" to title)

/** The first search result that is the same song as [song] under another id: same title, about the same length. */
fun copyOf(song: Song, results: List<Song>): Song? = results.firstOrNull {
    it.id != song.id && it.title.trim().equals(song.title.trim(), ignoreCase = true) && kotlin.math.abs(it.durationSec - song.durationSec) <= 3
}

/** A music video upload rather than the song: its name carries packaging ("(Official Music Video)", "Artist - ") a song's doesn't. */
fun looksLikeVideo(song: Song): Boolean =
    !song.isLocal && !song.artist.endsWith(" - Topic") && (" - " in song.title || VIDEO_WORDS.containsMatchIn(song.title))

private val VIDEO_WORDS = Regex("""(?i)\bofficial\b|\bvideo\b|\bm/?v\b|\blyrics?\b|\bvisuali[sz]er\b|[|｜]""")

/** What to search YouTube Music's songs for: the artist and the song's own name. */
fun songQuery(video: Song) =
    "${com.spotifry.music.data.artistForLyrics(video.artist)} ${com.spotifry.music.data.titleForLyrics(video.title, video.artist)}"

/**
 * The song version of a music video among [results]: the same name ("Satru (feat. Happy Asmara)" for "… - SATRU
 * (Official Music Video)", never "Satru 2"), an artist sharing a word with the video's channel or title, and no longer
 * than the video (videos add an intro, songs don't add anything). Null when none fits.
 */
fun songVersionOf(video: Song, results: List<Song>): Song? {
    val name = com.spotifry.music.data.titleForLyrics(video.title, video.artist)
    fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.toSet()
    val ours = words(video.artist) + words(video.title.substringBefore(" - ", ""))
    return results.firstOrNull { r ->
        r.id != video.id && com.spotifry.music.data.LyricsSource.nearlySameTitle(com.spotifry.music.data.titleForLyrics(r.title, r.artist), name) &&
            words(r.artist).any { it in ours } && r.durationSec in (video.durationSec - 120)..(video.durationSec + 5)
    }
}
