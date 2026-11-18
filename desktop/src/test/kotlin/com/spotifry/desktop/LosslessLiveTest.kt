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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Real YouTube titles to their lossless copies, then the whole download, the swap, and a restart in between, as the desktop does it. */
    @Test fun youtubeTitlesGetTheirLosslessCopyInPlace() = runBlocking<Unit> {
        assumeTrue("LIVE not set", System.getenv("LIVE") == "1")
        val client = OkHttpClient()
        val mono = MonochromeSource(client, log = { println("  lossless: $it") })
        val songs = listOf(
            Song("h2jvHynuMjI", "Ariana Grande, Justin Bieber - Stuck with U (Lyric Video)", "Justin Bieber", 230, null),
            Song("YQ-qToZUybM", "The Weeknd, Ariana Grande - Die For You (Remix / Lyric Video)", "The Weeknd", 233, null),
            Song("7f_mlKlOLMo", "So Easy (To Fall In Love)", "Olivia Dean", 169, null),
            Song("XFkzRNyygfk", "Radiohead - Creep (Official Video)", "Radiohead", 238, null),
        )
        val found = songs.associateWith { mono.find(it) }
        found.forEach { (s, f) -> println("${s.title}: ${f?.let { "${it.origin} ${it.bitrateKbps} kbps" } ?: "NO MATCH"}") }
        assertTrue("every one matches", found.values.all { it != null })

        val dir = kotlin.io.path.createTempDirectory("live-replay").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val noYoutube = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean) = error("not asked")
            override suspend fun related(song: Song) = emptyList<Song>()
        }
        val source = ChoosingSource(noYoutube, { null }, { false })
        val song = songs.first { it.id == "7f_mlKlOLMo" }
        java.io.File(dir, "${song.id}.youtube").writeText("opus")
        var store = ReplayStore(client, scope, dir) { 0 }
        BetterCopies(client, source, store, { Long.MAX_VALUE }, scope).apply { offer(song, found[song]!!); download(song) }
        val done = java.io.File(dir, "${song.id}.monochrome.better")
        val t0 = System.currentTimeMillis()
        while (!done.exists() && System.currentTimeMillis() - t0 < 180_000) delay(500)
        println("downloaded: ${done.length() / 1024} KB in ${(System.currentTimeMillis() - t0) / 1000} s")
        assertTrue("whole copy downloaded", done.exists())
        assertEquals("fLaC", done.inputStream().use { String(it.readNBytes(4)) })
        // the app closes before the song changes; the next start puts it in place
        store.close(); store = ReplayStore(client, scope, dir) { 0 }
        BetterCopies(client, source, store, { Long.MAX_VALUE }, scope).upgradeWhenFree(emptySet())
        println("after restart, plays from: ${store.best(song.id)}")
        assertEquals("monochrome", store.best(song.id)?.second)
        assertTrue("the Opus copy is gone", !java.io.File(dir, "${song.id}.youtube").exists())
        store.close(); dir.deleteRecursively()
    }
}
