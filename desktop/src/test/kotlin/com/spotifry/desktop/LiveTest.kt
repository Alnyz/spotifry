package com.spotifry.desktop

import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/** Needs the internet and mpv: run with LIVE=1. A real search, a real stream, a real mpv with its sound off. */
class LiveTest {
    @Test fun aSearchedSongPlays() = runBlocking {
        assumeTrue("LIVE not set", System.getenv("LIVE") == "1")
        NewPipe.init(OkHttpDownloader(OkHttpClient()), Localization("en", "US"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = NewPipeSource()
        val player = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"))
        try {
            val results = source.search("Coldplay Yellow")
            println("found ${results.size}: ${results.take(3).map { it.title }}")
            player.playList(results, 0, "Search")
            withTimeout(30_000) { player.state.first { it.isPlaying } }
            val started = player.position.value
            withTimeout(10_000) { player.position.first { it > started + 2_000 } }
            println("playing ${player.state.value.current?.title}, position ${player.position.value} ms, duration ${player.state.value.durationMs} ms")
            player.pause(); player.seekTo(60_000)
            withTimeout(10_000) { player.position.first { it in 59_000..62_000 } }
            assertTrue(player.state.value.durationMs > 60_000)
        } finally { player.close() }
    }

    @Test fun lyricsAreFoundForARealSong() = runBlocking {
        assumeTrue("LIVE not set", System.getenv("LIVE") == "1")
        val song = com.spotifry.music.data.source.Song("yellow", "Yellow", "Coldplay", 269, null)
        val found = com.spotifry.music.data.LyricsSource(OkHttpClient()).get(song)
        println("lyrics: ${found?.let { it::class.simpleName }} ${(found as? com.spotifry.music.data.Lyrics.Timed)?.let { "${it.lines.size} lines from ${it.from}" }}")
        assertTrue(found != null)
    }
}
