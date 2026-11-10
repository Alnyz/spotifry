package com.spotifry.music.data

import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.MonochromeSource
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.parseMonochromeResults
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val creep = Song("yt", "Creep", "Radiohead", 238, null)
private fun track(id: String, title: String, ms: Long, playable: Boolean = true) =
    """{"trackId":"$id","title":"$title","artistNames":["Radiohead"],"duration":$ms,"playable":$playable}"""
private fun answer(vararg tracks: String) = """{"tracks":[${tracks.joinToString(",")}]}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonochromeTest {
    private fun file(body: String = "fLaC....", code: Int = 206, total: Long = 29_390_678) =
        MockResponse().setResponseCode(code).setHeader("Content-Range", "bytes 0-3/$total").setBody(Buffer().writeUtf8(body))

    private fun source(server: MockWebServer, now: () -> Long = { 1_000L }) = MonochromeSource(OkHttpClient(), server.url("/").toString().trimEnd('/'), now)

    @Test fun readsTheSearchAnswer() {
        val r = parseMonochromeResults(answer(track("1", "Creep", 238640), track("2", "Creep - Acoustic", 258640, playable = false))).first()
        assertEquals(listOf("1", "Creep", 238640L, true), listOf(r.id, r.title, r.durationMs, r.playable))
        assertEquals(emptyList<Any>(), parseMonochromeResults("{}"))
    }

    @Test fun findsTheFlacAndWorksOutItsBitrate() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(answer(track("9", "Creep", 238640)))); enqueue(file()); start() }
        val found = source(server).find(creep)!!
        assertTrue(found.url, found.url.endsWith("/track/9"))
        assertEquals("audio/flac", found.mimeType)
        assertEquals(985, found.bitrateKbps) // 29,390,678 bytes over 238.64 s
        assertTrue(found.fromMonochrome)
        assertEquals("monochrome", found.origin)
        assertEquals("creep Radiohead", server.takeRequest().requestUrl!!.queryParameter("q"))
        server.shutdown()
    }

    @Test fun onlyASureMatchCounts() = runBlocking {
        // another version, a different length, and one that can't play: none is the song
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(answer(track("1", "Creep - Acoustic", 238640), track("2", "Creep", 258000), track("3", "Creep", 238640, playable = false))))
            start()
        }
        assertNull(source(server).find(creep))
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun aFileThatIsNotFlacIsNoMatch() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(answer(track("9", "Creep", 238640)))); enqueue(file(body = "<htm")); start() }
        assertNull(source(server).find(creep))
        server.shutdown()
    }

    @Test fun aDeadServerIsLeftAloneForAFewMinutes() = runBlocking {
        var now = 1_000L
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(502)); start() }
        val s = source(server) { now }
        assertNull(s.find(creep))
        assertNull(s.find(creep))
        assertEquals(1, server.requestCount)
        now += 6 * 60_000L
        server.enqueue(MockResponse().setBody(answer(track("9", "Creep", 238640)))); server.enqueue(file())
        assertTrue(s.find(creep) != null)
        server.shutdown()
    }
}

class ChoosingLosslessTest {
    private val song = Song("a", "A", "B", 100, null)
    private val flac = ResolvedStream("https://x/flac", "audio/flac", 900, Long.MAX_VALUE, fromMonochrome = true)
    private val aac = ResolvedStream("https://x/aac", "audio/mp4", 320, Long.MAX_VALUE, fromSaavn = true)
    private val yt = FakeMusicSource()

    @Test fun losslessComesFirst() = runBlocking {
        assertEquals(flac, ChoosingSource(yt, { aac }, { true }, { flac }, { true }).resolve(song))
    }
    @Test fun aMissFallsToSaavnThenYoutube() = runBlocking {
        assertEquals(aac, ChoosingSource(yt, { aac }, { true }, { null }, { true }).resolve(song))
        assertEquals("https://x/1", ChoosingSource(yt, { null }, { true }, { error("down") }, { true }).resolve(song).url)
    }
    @Test fun switchedOffLowAndPhoneFilesNeverAsk() = runBlocking {
        val asked = mutableListOf<String>()
        val lossless: suspend (Song) -> ResolvedStream? = { asked += it.id; flac }
        ChoosingSource(yt, { null }, { false }, lossless, { false }).resolve(song)
        ChoosingSource(yt, { null }, { false }, lossless, { true }).resolve(song, low = true)
        ChoosingSource(yt, { null }, { false }, lossless, { true }).resolve(song.copy(id = "local:5"))
        assertEquals(emptyList<String>(), asked)
        assertFalse(flac.fromSaavn)
    }
}
