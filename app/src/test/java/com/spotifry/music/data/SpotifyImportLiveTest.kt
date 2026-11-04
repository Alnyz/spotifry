package com.spotifry.music.data

import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/** Real Spotify + YouTube. Run with: ./gradlew testDebugUnitTest -Plive=1 --tests '*SpotifyImportLiveTest' */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpotifyImportLiveTest {
    @Test fun matchFirstTenSongs() = runBlocking {
        assumeTrue(System.getProperty("live").orEmpty().isNotEmpty())
        val client = OkHttpClient()
        NewPipe.init(OkHttpDownloader(client), Localization("en", "US"))
        val playlist = SpotifyImport.fetch(client, "2wauuVc0v7DBWGylJ6nFFr")
        println("PLAYLIST ${playlist.name}: ${playlist.tracks.size} songs")
        val source = NewPipeSource()
        for (t in playlist.tracks.take(10)) {
            val results = source.search("${t.title} ${t.artist}")
            val m = SpotifyImport.pick(t, results)
            println("SPOTIFY ${t.title} | ${t.artist} | ${t.durationSec}s")
            println("  -> ${m?.let { "${it.title} | ${it.artist} | ${it.durationSec}s | ${it.url}" } ?: "NO MATCH"}")
            if (m == null) results.take(3).forEach { println("     top: ${it.title} | ${it.artist} | ${it.durationSec}s") }
        }
    }
}
