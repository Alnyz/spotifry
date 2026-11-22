package com.spotifry.music.data.spotifried

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtistLogosTest {
    private val server = MockWebServer()
    private val dir = Files.createTempDirectory("logos").toFile().also { it.deleteOnExit() }
    private var key = "k"
    private var t = 1_800_000_000_000L
    private val day = 86_400_000L

    private var mbCode = 200
    /** Busy answers (503) before MusicBrainz answers. */
    private var mbBusy = 0
    private var artists = """{"artists":[{"id":"mb1","name":"Radiohead","score":100}]}"""
    private var fanart: MockResponse = MockResponse().setBody("""{"hdmusiclogo":[{"url":"URL/hd3","likes":"3"},{"url":"URL/hd9","likes":"9"}],"musiclogo":[{"url":"URL/m","likes":"50"}]}""")

    private fun png(tag: Int) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, tag.toByte())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/ws/2/artist") -> if (mbBusy-- > 0) MockResponse().setResponseCode(503) else MockResponse().setResponseCode(mbCode).setBody(artists)
                    p.startsWith("/v3/music/mb1") -> fanart.clone().setBody(fanart.getBody()?.clone()?.readUtf8().orEmpty().replace("URL", server.url("/img").toString().trimEnd('/')))
                    p == "/img/hd9" -> MockResponse().setBody(Buffer().write(png(9)))
                    p == "/img/hd3" -> MockResponse().setBody(Buffer().write(png(3)))
                    p == "/img/m" -> MockResponse().setBody(Buffer().write(png(1)))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After fun stop() = runCatching { server.shutdown() }.let { }

    private fun logos(): ArtistLogos {
        val base = server.url("/").toString().trimEnd('/')
        return ArtistLogos(OkHttpClient(), dir, "Spotifry/1 (test)", { key }, base, base, { t }, pause = {})
    }

    @Test fun picksHdLogoWithMostLikes() = runBlocking {
        assertArrayEquals(png(9), logos().logo("Radiohead")!!.readBytes())
    }

    @Test fun fallsBackToMusicLogo() = runBlocking {
        fanart = MockResponse().setBody("""{"musiclogo":[{"url":"URL/m","likes":"1"}]}""")
        assertArrayEquals(png(1), logos().logo("radiohead - Topic")!!.readBytes())
    }

    @Test fun noKeyMakesNoRequest() = runBlocking {
        key = ""
        assertNull(logos().logo("Radiohead"))
        assertEquals(0, server.requestCount)
    }

    @Test fun looseMatchGivesNoLogo() = runBlocking {
        artists = """{"artists":[{"id":"mb1","name":"Radiohead","score":85},{"id":"mb1","name":"Radiohead Tribute","score":100}]}"""
        assertNull(logos().logo("Radiohead"))
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".none") })
    }

    @Test fun noneFoundIsNotAskedAgainFor30Days() = runBlocking {
        artists = """{"artists":[]}"""
        val l = logos()
        assertNull(l.logo("Nobody"))
        val n = server.requestCount
        assertNull(l.logo("Nobody"))
        assertEquals(n, server.requestCount)
        t += 31 * day
        l.logo("Nobody")
        assertTrue(server.requestCount > n)
    }

    @Test fun refusedKeyStoresNoMarker() = runBlocking {
        fanart = MockResponse().setResponseCode(401)
        assertNull(logos().logo("Radiohead"))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".none") })
    }

    @Test fun newKeyForgetsNoneFound() = runBlocking {
        artists = """{"artists":[]}"""
        val l = logos()
        l.logo("Nobody")
        l.forgetNoneFound()
        val n = server.requestCount
        l.logo("Nobody")
        assertTrue(server.requestCount > n)
    }

    @Test fun brokenLogoAddressGivesNoLogo() = runBlocking {
        fanart = MockResponse().setBody("""{"hdmusiclogo":[{"url":"not a web address","likes":"1"}]}""")
        assertNull(logos().logo("Radiohead"))
    }

    @Test fun musicBrainzRefusalStoresNoMarker() = runBlocking {
        mbCode = 403
        assertNull(logos().logo("Radiohead"))
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".none") })
    }

    @Test fun busyMusicBrainzIsAskedAgain() = runBlocking {
        mbBusy = 2
        assertArrayEquals(png(9), logos().logo("Radiohead")!!.readBytes())
    }

    @Test fun alwaysBusyGivesUpWithoutAMarker() = runBlocking {
        mbBusy = 100
        assertNull(logos().logo("Radiohead"))
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".none") })
    }

    @Test fun cachedLogoMakesNoRequest() = runBlocking {
        val l = logos()
        l.logo("Radiohead")
        val n = server.requestCount
        assertArrayEquals(png(9), l.logo("Radiohead")!!.readBytes())
        assertEquals(n, server.requestCount)
    }
}
