package com.spotifry.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BetterCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun youtubeTakesItsRangeInTheLinkAndOthersInAHeader() {
        val yt = ranged("https://rr1---sn-x.googlevideo.com/videoplayback?id=1", 0, null)
        assertEquals("0-", yt.url.queryParameter("range")); assertNull(yt.header("Range"))
        assertEquals("100-199", ranged("https://rr1---sn-x.googlevideo.com/videoplayback?id=1&range=5-", 100, 199).url.queryParameter("range"))
        val ll = ranged("https://lossless.example/a.flac", 100, null)
        assertEquals("bytes=100-", ll.header("Range")); assertNull(ll.url.queryParameter("range"))
    }

    @Test fun aCutDownloadGoesOnFromWhereItGot() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val asked = mutableListOf<String?>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { ex ->
                val range = ex.requestHeaders.getFirst("Range"); asked += range
                val from = range!!.removePrefix("bytes=").substringBefore('-').toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, (data.size - from).toLong())
                // the first answer is cut off half-way, as a slow server's connection is
                val upTo = if (asked.size == 1) data.size / 2 else data.size
                runCatching { ex.responseBody.write(data, from, upTo - from); ex.responseBody.flush() }
                ex.close()
            }
            start()
        }
        try {
            val part = File(tmp.root, "a.monochrome.better")
            assertNull(fetchResuming(OkHttpClient(), "http://127.0.0.1:${server.address.port}/a.flac", part, { true }))
            assertTrue(data.contentEquals(part.readBytes()))
            assertEquals(listOf("bytes=0-", "bytes=${data.size / 2}-"), asked)
        } finally { server.stop(0) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aLookThatFoundNothingIsAskedAgainWithoutPlayingTheSongAgain() {
        val song = com.spotifry.music.data.source.Song("yt:1", "So Easy", "A", 169, null)
        var asked = 0
        val youtube = object : com.spotifry.music.data.source.MusicSource {
            override suspend fun search(query: String) = emptyList<com.spotifry.music.data.source.Song>()
            override suspend fun resolve(song: com.spotifry.music.data.source.Song, low: Boolean) = error("not asked")
            override suspend fun related(song: com.spotifry.music.data.source.Song) = emptyList<com.spotifry.music.data.source.Song>()
        }
        // the server says "not now" once, then finds it
        val source = com.spotifry.music.data.source.ChoosingSource(youtube, { null }, { false },
            lossless = { if (++asked == 1) null else com.spotifry.music.data.source.ResolvedStream("http://127.0.0.1:1/x", "audio/flac", 1000, Long.MAX_VALUE, fromMonochrome = true) },
            losslessEnabled = { true })
        val test = kotlinx.coroutines.test.TestScope()
        val store = ReplayStore(OkHttpClient(), test, tmp.newFolder("r")) { 0 }
        val better = BetterCopies(OkHttpClient(), source, store, { Long.MAX_VALUE }, test, now = { 0L })
        test.launch { better.onHeard(song, "youtube") }
        fun settle() { repeat(50) { test.testScheduler.runCurrent(); Thread.sleep(20) } }
        settle(); assertEquals(1, asked)
        test.testScheduler.advanceTimeBy(91_000); settle()
        assertEquals("asked again after 90 s", 2, asked)
        test.testScheduler.advanceTimeBy(11 * 60_000L); settle()
        assertEquals("found: not asked a third time", 2, asked)
        store.close()
    }

    @Test fun theBestKeptCopyPlaysAndABetterOneReplacesThePoorer() {
        val dir = tmp.newFolder("replay")
        val store = ReplayStore(OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.IO), dir) { 0 }
        try {
            assertNull(store.best("yt:1"))
            File(dir, "yt_1.youtube").writeText("opus")
            File(dir, "yt_1.saavn.part").writeText("half")
            assertEquals(File(dir, "yt_1.youtube").path to "youtube", store.best("yt:1"))
            val incoming = store.incoming("yt:1", "monochrome").apply { writeText("flac") }
            assertTrue(store.install("yt:1", "monochrome", incoming))
            assertEquals(File(dir, "yt_1.monochrome").path to "monochrome", store.best("yt:1"))
            // the poorer copies, halves included, are gone; the better one is whole
            assertFalse(File(dir, "yt_1.youtube").exists()); assertFalse(File(dir, "yt_1.saavn.part").exists()); assertFalse(incoming.exists())
            assertEquals("flac", File(dir, "yt_1.monochrome").readText())
        } finally { store.close() }
    }

    @Test fun aCopyFinishedBeforeTheAppClosedGoesInPlaceAfterIt() {
        val dir = tmp.newFolder("left")
        File(dir, "abc.youtube").writeText("opus")
        File(dir, "abc.monochrome.better").writeText("flac")
        File(dir, "def.monochrome.better.part").writeText("half")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ReplayStore(OkHttpClient(), scope, dir) { 0 }
        try {
            val source = com.spotifry.music.data.source.ChoosingSource(object : com.spotifry.music.data.source.MusicSource {
                override suspend fun search(query: String) = emptyList<com.spotifry.music.data.source.Song>()
                override suspend fun resolve(song: com.spotifry.music.data.source.Song, low: Boolean) = error("not asked")
                override suspend fun related(song: com.spotifry.music.data.source.Song) = emptyList<com.spotifry.music.data.source.Song>()
            }, { null }, { false })
            val better = BetterCopies(OkHttpClient(), source, store, { Long.MAX_VALUE }, scope)
            better.upgradeWhenFree(setOf("abc"))
            assertTrue("busy: waits", File(dir, "abc.monochrome.better").exists())
            better.upgradeWhenFree(emptySet())
            assertEquals(File(dir, "abc.monochrome").path to "monochrome", store.best("abc"))
            assertFalse(File(dir, "abc.youtube").exists())
            // a download cut off when the app closed is not taken as whole
            assertNull(store.best("def"))
        } finally { store.close() }
    }

    @Test fun theSongBeforeLetsGoOfItsCopyOnceItHasFadedOut() {
        val dir = tmp.newFolder("fade")
        val store = ReplayStore(OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.IO), dir) { 0 }
        try {
            store.open("a", "youtube", "http://127.0.0.1:1/a")
            store.open("b", "youtube", "http://127.0.0.1:1/b", alongside = true) // "a" fades out as "b" comes in
            val better = store.incoming("a", "monochrome").apply { writeText("flac") }
            assertFalse("a is still heard", store.install("a", "monochrome", better))
            store.fadedOut()
            assertTrue(store.install("a", "monochrome", better))
        } finally { store.close() }
    }
}
