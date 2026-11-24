package com.spotifry.music.data.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererFinderTest {
    private fun description(type: String = "urn:schemas-upnp-org:device:MediaRenderer:1", base: String = "", name: String = "Living room TV") = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          $base
          <device>
            <deviceType>$type</deviceType>
            <friendlyName>$name</friendlyName>
            <manufacturer>Acme</manufacturer>
            <modelName>TV 55</modelName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/rendering</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/transport</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun theSearchAsksForMediaRenderers() {
        val m = RendererFinder.searchMessage()
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in m)
        assertTrue("MAN: \"ssdp:discover\"\r\n" in m)
        assertTrue("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" in m)
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    @Test fun readsAnAnswerWhateverTheCaseOfItsHeaders() {
        val r = RendererFinder.parseReply("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://192.168.1.20:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUsn: uuid:1234::urn:x\r\n\r\n")!!
        assertEquals("http://192.168.1.20:9197/dmr", r.location)
        assertEquals("uuid:1234::urn:x", r.usn)
    }

    @Test fun skipsWhatIsNotAnAnswerFromARenderer() {
        assertNull(RendererFinder.parseReply("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.20/d\r\n\r\n"))
        assertNull(RendererFinder.parseReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://192.168.1.20/d\r\n\r\n"))
        assertNull(RendererFinder.parseReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: file:///etc/passwd\r\n\r\n"))
        assertNull(RendererFinder.parseReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
    }

    @Test fun readsNameAndWhereToSendPlayAndVolume() {
        val r = RendererFinder.parseDescription(description(), "http://192.168.1.20:9197/dmr/desc.xml")!!
        assertEquals("uuid:1234", r.id)
        assertEquals("Living room TV", r.name)
        assertEquals("Acme TV 55", r.model)
        // a relative address is relative to the description, one starting with / to the device
        assertEquals("http://192.168.1.20:9197/dmr/upnp/control/transport", r.transport)
        assertEquals("http://192.168.1.20:9197/upnp/control/rendering", r.volume)
    }

    @Test fun anOldDeviceCanSayWhereItsAddressesStart() {
        val r = RendererFinder.parseDescription(description(base = "<URLBase>http://192.168.1.20:8080/</URLBase>"), "http://192.168.1.20:9197/desc.xml")!!
        assertEquals("http://192.168.1.20:8080/upnp/control/transport", r.transport)
    }

    @Test fun aDeviceThatCantPlayIsSkipped() {
        assertNull(RendererFinder.parseDescription(description(type = "urn:schemas-upnp-org:device:MediaServer:1"), "http://192.168.1.20/d"))
        assertNull(RendererFinder.parseDescription(description().replace("AVTransport", "Other"), "http://192.168.1.20/d"))
        assertNull(RendererFinder.parseDescription("not xml", "http://192.168.1.20/d"))
    }

    @Test fun aDescriptionCantMakeTheParserReadFiles() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hostname">]>""" +
            description(name = "&x;").substringAfter("?>")
        // refused outright rather than read with the file's text as the name
        assertNull(RendererFinder.parseDescription(evil, "http://192.168.1.20/d"))
    }

    @Test fun aRendererInsideAnotherDeviceIsFound() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS</friendlyName><UDN>uuid:nas</UDN>
              <deviceList>${description(name = "Kitchen speaker").substringAfter("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">").substringBefore("</root>")}</deviceList>
            </device></root>
        """.trimIndent()
        assertEquals("Kitchen speaker", RendererFinder.parseDescription(xml, "http://192.168.1.30/d")!!.name)
    }

    /** A pretend TV on this computer: answers the search and serves its description. */
    private fun withFakeDevice(
        location: (MockWebServer) -> String,
        answer: () -> MockResponse = { MockResponse().setBody(description()) },
        block: (InetSocketAddress, MockWebServer) -> Unit,
    ) {
        val web = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = answer()
        } }
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val answering = Thread {
            runCatching {
                while (true) {
                    val p = DatagramPacket(ByteArray(1024), 1024)
                    udp.receive(p)
                    if (!String(p.data, 0, p.length).startsWith("M-SEARCH")) continue
                    val reply = "HTTP/1.1 200 OK\r\nLOCATION: ${location(web)}\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:1234\r\n\r\n".toByteArray()
                    udp.send(DatagramPacket(reply, reply.size, p.socketAddress))
                }
            }
        }.apply { isDaemon = true; start() }
        try { block(InetSocketAddress("127.0.0.1", udp.localPort), web) } finally { udp.close(); answering.join(1_000); web.shutdown() }
    }

    @Test fun findsADeviceOnTheNetworkOnce() = withFakeDevice({ it.url("/desc.xml").toString() }) { target, web ->
        val found = runBlocking { RendererFinder(OkHttpClient(), target, listenMs = 500).find() }
        assertEquals(listOf("Living room TV"), found.map { it.name })
        // asked twice, answered twice, described once
        assertEquals(1, web.requestCount)
    }

    @Test fun aDescriptionOnAnotherMachineIsNotFetched() = withFakeDevice({ "http://10.255.255.1:${it.port}/desc.xml" }) { target, web ->
        val found = runBlocking { RendererFinder(OkHttpClient(), target, listenMs = 500).find() }
        assertTrue(found.isEmpty())
        assertEquals(0, web.requestCount)
    }

    @Test fun aRedirectToAnotherAddressIsNotFollowed() {
        val elsewhere = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val redirect = { MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/desc.xml").toString()) }
            withFakeDevice({ it.url("/desc.xml").toString() }, redirect) { target, _ ->
                val found = runBlocking { RendererFinder(OkHttpClient(), target, listenMs = 500).find() }
                assertTrue(found.isEmpty())
                assertEquals(0, elsewhere.requestCount)
            }
        } finally { elsewhere.shutdown() }
    }

    @Test fun aDescriptionOverTheSizeLimitIsNotRead() {
        val huge = { MockResponse().setBody(description() + " ".repeat(RendererFinder.MAX_DESCRIPTION.toInt())) }
        withFakeDevice({ it.url("/desc.xml").toString() }, huge) { target, _ ->
            assertTrue(runBlocking { RendererFinder(OkHttpClient(), target, listenMs = 500).find() }.isEmpty())
        }
    }
}
