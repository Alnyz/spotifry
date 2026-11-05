package com.spotifry.music.data.source

import kotlinx.coroutines.Dispatchers
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

    private fun find(query: String, type: SearchType): List<InfoItem> {
        val ex = YouTube.getSearchExtractor(query, listOf(filterOf(type)), "")
        ex.fetchPage()
        return ex.initialPage.items
    }

    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = withContext(Dispatchers.IO) {
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
        (mix?.takeIf { it.size > 1 } ?: StreamInfo.getInfo(YouTube, song.url).relatedItems.toSongs()).filter { it.id != song.id }
    }

    private fun List<InfoItem>.toSongs(): List<Song> = filterIsInstance<StreamInfoItem>().mapNotNull { item ->
        // drops live streams (duration -1) and anything over 20 minutes
        if (item.duration !in 1..MAX_SECONDS) return@mapNotNull null
        val id = YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url)
        Song(id, item.name, item.uploaderName.orEmpty(), item.duration, item.thumbnails.maxByOrNull { it.height }?.url)
    }

    private fun expiryOf(url: String): Long {
        val seconds = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (seconds != null) seconds * 1000 else System.currentTimeMillis() + FALLBACK_TTL_MS
    }

    private companion object {
        const val MAX_SECONDS = 1200L
        const val FALLBACK_TTL_MS = 5 * 60 * 60 * 1000L
    }
}

private fun filterOf(type: SearchType) = when (type) {
    SearchType.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
    SearchType.VIDEOS -> YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
    SearchType.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
    SearchType.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    SearchType.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
}

/** A found album, playlist or artist; null for anything else. Artists lose YouTube's " - Topic" ending. */
internal fun linkOf(item: InfoItem, type: SearchType): PageLink? {
    val art = item.thumbnails.maxByOrNull { it.height }?.url
    return when (item) {
        is PlaylistInfoItem -> PageLink(type, item.url, item.name, item.uploaderName.orEmpty(), art)
        is ChannelInfoItem -> PageLink(type, item.url, item.name.removeSuffix(" - Topic"), "", art)
        else -> null
    }
}

/** Only what the channel at [channelUrl] put out: an artist's own songs and albums in a search for their name. */
internal fun List<InfoItem>.from(channelUrl: String) = filter {
    (it as? StreamInfoItem)?.uploaderUrl == channelUrl || (it as? PlaylistInfoItem)?.uploaderUrl == channelUrl
}

/** YouTube names an album page "Album – Kid A" (or Single, EP): the kind and the bare name. */
internal fun albumKind(title: String): Pair<String, String> =
    Regex("^(Album|Single|EP) – (.+)$").find(title)?.destructured?.let { (k, n) -> k to n } ?: ("Album" to title)
