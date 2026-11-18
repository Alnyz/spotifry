package com.spotifry.desktop

import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.MonochromeSource
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Needs the internet: run with LIVE=1. The lossless lookups the player makes for one song, as they happen, printed. */
class LosslessLiveTest {
    @Test fun soEasyAsThePlayerAsksForIt() = runBlocking {
        assumeTrue("LIVE not set", System.getenv("LIVE") == "1")
        val t0 = System.currentTimeMillis()
        fun say(s: String) = println("${(System.currentTimeMillis() - t0) / 1000.0}s $s")
        val client = OkHttpClient.Builder().connectionPool(ConnectionPool(5, 10, TimeUnit.SECONDS)).build()
        val youtube = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean) = ResolvedStream("https://yt/x", "audio/webm", 160, Long.MAX_VALUE).also { say("youtube answers") }
            override suspend fun related(song: Song) = emptyList<Song>()
        }
        val mono = MonochromeSource(client, log = { say("lossless: $it") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = ChoosingSource(youtube, { null }, { false }, lossless = { s -> mono.find(s).also { say("lossless answers ${it?.origin}") } }, losslessEnabled = { true }, scope = scope,
            onBetter = { _, b -> say("better offered: ${b.origin}") })
        val resolver = StreamResolver(source, scope)
        val song = Song("7f_mlKlOLMo", "So Easy (To Fall In Love)", "Olivia Dean", 169, null)
        say("looked up ahead (next in line): ${resolver.get(song, patient = true).origin}")
        say("played: ${resolver.get(song).origin}")
        delay(30_000)
        say("after 30 s heard: ${source.findBetter(song, "youtube")?.origin}")
    }
}
