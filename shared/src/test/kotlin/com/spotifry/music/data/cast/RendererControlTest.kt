package com.spotifry.music.data.cast

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RendererControlTest {
    private lateinit var tv: MockWebServer

    @Before fun start() { tv = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun stop() { tv.shutdown() }

    private fun control(volume: Boolean = true) = RendererControl(
        OkHttpClient(),
        Renderer("uuid:1", "TV", null, tv.url("/transport").toString(), if (volume) tv.url("/volume").toString() else null),
    )

    private fun answer(action: String, service: String = "AVTransport", values: String = "") = MockResponse().setBody(
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$values</u:${action}Response></s:Body></s:Envelope>""",
    )

    @Test fun loadingASongSendsItsAddressAndWhatToShow() = runBlocking {
        tv.enqueue(answer("SetAVTransportURI"))
        control().load("http://192.168.1.5:8080/song/1", "Song", "Band", "audio/mp4")
        val sent = tv.takeRequest()
        assertEquals("/transport", sent.path)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", sent.getHeader("SOAPACTION"))
        val body = sent.body.readUtf8()
        assertTrue("<CurrentURI>http://192.168.1.5:8080/song/1</CurrentURI>" in body)
        // the metadata is XML inside XML, so it goes escaped once more
        assertTrue("&lt;dc:title&gt;Song&lt;/dc:title&gt;" in body)
        assertTrue("http-get:*:audio/mp4:*" in body)
    }

    @Test fun aTitleCantAddItsOwnXml() {
        val body = RendererControl.envelope("s", "A", listOf("X" to "</X><Evil>1</Evil>"))
        assertFalse("<Evil>" in body)
        val meta = RendererControl.metadata("http://a/?b=1&c=2", "AC/DC <live> & \"more\"", null, "audio/mp4")
        assertFalse("<live>" in meta)
        assertTrue("http://a/?b=1&amp;c=2" in meta)
        // control characters XML can't hold are dropped instead of breaking the whole message
        assertEquals("ab", RendererControl.escape("a\u0001b"))
    }

    @Test fun playPauseStopAndSeekReachTheTv() = runBlocking {
        listOf("Play", "Pause", "Stop", "Seek").forEach { tv.enqueue(answer(it)) }
        val c = control()
        c.play(); c.pause(); c.stop(); c.seek(3_723_000)
        val actions = (1..4).map { tv.takeRequest() }
        assertEquals(listOf("Play", "Pause", "Stop", "Seek"), actions.map { it.getHeader("SOAPACTION")!!.substringAfter('#').trim('"') })
        assertTrue("<Target>1:02:03</Target>" in actions[3].body.readUtf8())
    }

    @Test fun readsWhatTheTvIsDoingAndHowFarItIs() = runBlocking {
        tv.enqueue(answer("GetTransportInfo", values = "<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState><CurrentSpeed>1</CurrentSpeed>"))
        tv.enqueue(answer("GetPositionInfo", values = "<Track>1</Track><TrackDuration>0:03:30</TrackDuration><RelTime>0:01:05.500</RelTime>"))
        val c = control()
        assertEquals(TransportState.Paused, c.state())
        assertEquals(TransportPosition(65_500, 210_000), c.position())
    }

    @Test fun volumeGoesToItsOwnAddressAndStaysInRange() = runBlocking {
        tv.enqueue(answer("SetVolume", "RenderingControl"))
        tv.enqueue(answer("GetVolume", "RenderingControl", "<CurrentVolume>42</CurrentVolume>"))
        val c = control()
        c.setVolume(250)
        val set = tv.takeRequest()
        assertEquals("/volume", set.path)
        assertTrue("<DesiredVolume>100</DesiredVolume>" in set.body.readUtf8())
        assertEquals(42, c.volume())
    }

    @Test fun aTvWithoutVolumeIsNotAsked() = runBlocking {
        val c = control(volume = false)
        assertFalse(c.hasVolume)
        c.setVolume(10)
        assertNull(c.volume())
        assertEquals(0, tv.requestCount)
    }

    @Test fun aRefusalSaysWhy() = runBlocking {
        tv.enqueue(MockResponse().setResponseCode(500).setBody(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>""" +
                """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError>""" +
                """</detail></s:Fault></s:Body></s:Envelope>""",
        ))
        try { control().pause(); fail() } catch (e: RendererException) {
            assertEquals(701, e.code)
            assertTrue("Transition not available" in e.message!!)
        }
    }

    @Test fun aRedirectIsNotFollowed() = runBlocking {
        val elsewhere = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            tv.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/x").toString()))
            try { control().play(); fail() } catch (_: RendererException) {}
            assertEquals(0, elsewhere.requestCount)
        } finally { elsewhere.shutdown() }
    }

    @Test fun aHugeAnswerIsNotRead() = runBlocking {
        tv.enqueue(MockResponse().setBody(" ".repeat(RendererControl.MAX_ANSWER.toInt() + 10)))
        try { control().play(); fail() } catch (e: RendererException) { assertTrue("too long" in e.message!!) }
    }

    @Test fun anAnswerCantMakeTheParserReadFiles() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hostname">]>""" +
            """<PlayResponse><A>&x;</A></PlayResponse>"""
        assertNull(RendererControl.parseAnswer(evil, "Play"))
    }

    @Test fun timesInTheFormTvsUse() {
        assertEquals("0:00:00", RendererControl.formatTime(-5))
        assertEquals("0:04:09", RendererControl.formatTime(249_999))
        assertEquals("12:00:00", RendererControl.formatTime(43_200_000))
        assertEquals(249_000L, RendererControl.parseTime("0:04:09"))
        assertEquals(1_500L, RendererControl.parseTime("00:00:01.5"))
        assertNull(RendererControl.parseTime("NOT_IMPLEMENTED"))
        assertNull(RendererControl.parseTime("0:75:00"))
        assertNull(RendererControl.parseTime(null))
    }

    @Test fun statesTvsSend() {
        assertEquals(TransportState.Playing, RendererControl.parseState("PLAYING"))
        assertEquals(TransportState.NoMedia, RendererControl.parseState("NO_MEDIA_PRESENT"))
        assertEquals(TransportState.Other, RendererControl.parseState("RECORDING"))
        assertEquals(TransportState.Other, RendererControl.parseState(null))
    }
}
