package com.spotifry.music.data

import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.MonochromeSource
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.parseMonochromeResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        assertEquals(flac, ChoosingSource(yt, { aac }, { true }, { flac }, { true }).resolve(song, false, true, null))
    }
    @Test fun aMissFallsToSaavnThenYoutube() = runBlocking {
        assertEquals(aac, ChoosingSource(yt, { aac }, { true }, { null }, { true }).resolve(song, false, true, null))
        assertEquals("youtube", ChoosingSource(yt, { null }, { true }, { error("down") }, { true }).resolve(song, false, true, null).origin)
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

    @Test fun aSongPlayedNowStartsFromTheFastestAndHearsOfTheBetterOne() = runBlocking {
        val slowFlac: suspend (Song) -> ResolvedStream? = { delay(300); flac }
        val better = CompletableDeferred<ResolvedStream>()
        val src = ChoosingSource(yt, { null }, { true }, slowFlac, { true }).also { it.onBetter = { _, s -> better.complete(s) } }
        assertEquals("youtube", src.resolve(song).origin)
        assertEquals(flac, withTimeout(2_000) { better.await() })
    }
    @Test fun aSongTappedWhileLookedUpAheadDoesNotWaitForTheBestAnswer() = runBlocking {
        val slowFlac: suspend (Song) -> ResolvedStream? = { delay(800); flac }
        val src = ChoosingSource(yt, { null }, { true }, slowFlac, { true })
        val ahead = async { src.resolve(song, false, true, null) }
        delay(100)
        val started = System.currentTimeMillis()
        assertEquals("youtube", src.resolve(song).origin)
        assertTrue(System.currentTimeMillis() - started < 500)
        assertEquals(flac, ahead.await())
    }
    @Test fun aSongLookedUpAheadWaitsForTheBestAnswer() = runBlocking {
        val slowFlac: suspend (Song) -> ResolvedStream? = { delay(300); flac }
        assertEquals(flac, ChoosingSource(yt, { aac }, { true }, slowFlac, { true }).resolve(song, false, true, null))
    }
    @Test fun keptBytesOfOneSourceAreCarriedOnFromThatSource() = runBlocking {
        val asked = mutableListOf<String>()
        val src = ChoosingSource(yt, { asked += "saavn"; aac }, { true }, { asked += "lossless"; flac }, { true })
        assertEquals("youtube", src.resolve(song, false, false, "youtube").origin)
        assertEquals(emptyList<String>(), asked)
        assertEquals(aac, src.resolve(song, false, false, "saavn"))
        assertEquals(listOf("saavn"), asked)
    }
    @Test fun whenYoutubeFailsTheOtherSourceStillPlaysTheSong() = runBlocking {
        yt.failResolve = true
        assertEquals(aac, ChoosingSource(yt, { aac }, { true }).resolve(song))
    }
    @Test fun whenEveryoneFailsTheSongFails() {
        yt.failResolve = true
        assertTrue(runCatching { runBlocking { ChoosingSource(yt, { null }, { true }, { null }, { true }).resolve(song) } }.isFailure)
    }
    @Test fun anUpgradeIsLookedForOnlyWhenTheStoredCopyIsNotTheBestAllowed() {
        assertTrue(com.spotifry.music.player.shouldLook("youtube", 3, null, null, 0))
        assertFalse(com.spotifry.music.player.shouldLook("monochrome", 3, null, null, 0))
        assertFalse(com.spotifry.music.player.shouldLook("youtube", 1, null, null, 0))
        assertFalse(com.spotifry.music.player.shouldLook("youtube", 3, "monochrome", null, 0))
        assertFalse(com.spotifry.music.player.shouldLook("youtube", 3, null, 0L, 60_000L))
        assertTrue(com.spotifry.music.player.shouldLook("youtube", 3, null, 0L, 31 * 60_000L))
        assertTrue(com.spotifry.music.player.shouldLook("saavn", 3, "saavn", null, 0))
    }
}
