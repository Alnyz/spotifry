package com.spotifry.music.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Looks up songs and audio streams on YouTube through NewPipeExtractor. Needs `NewPipe.init` first. */
class NewPipeSource : MusicSource {
    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val ex = YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        ex.fetchPage()
        ex.initialPage.items.toSongs()
    }

    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(YouTube, song.url)
        val known = info.audioStreams.filter { it.averageBitrate > 0 }.ifEmpty { info.audioStreams }
        val a = (if (low) known.minByOrNull { it.averageBitrate } else known.maxByOrNull { it.averageBitrate }) ?: error("no audio stream for ${song.id}")
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
