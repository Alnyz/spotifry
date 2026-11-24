package com.spotifry.music.data.cast

import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element

/**
 * A TV, speaker or player on the home network that can play a song when given its address (a UPnP MediaRenderer,
 * which is what "DLNA" means on a TV's box). [transport] is where play, pause and seek go; [volume] is where volume
 * goes, when the device lets the phone change it.
 */
data class Renderer(
    val id: String,
    val name: String,
    val model: String?,
    val transport: String,
    val volume: String?,
)

/** One answer to the search: where the device describes itself, and who it is. */
data class SsdpReply(val location: String, val usn: String)

/**
 * Finds the TVs and speakers on the same Wi-Fi. It asks the network once (SSDP, the standard way), listens for
 * [listenMs], then reads each answering device's description. Nothing goes beyond the local network: a device that
 * points to a description somewhere else is skipped.
 *
 * [target] is where the search goes; tests point it at a fake device on this computer.
 */
class RendererFinder(
    private val client: OkHttpClient,
    private val target: InetSocketAddress = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT),
    private val listenMs: Int = 3_000,
) {
    suspend fun find(): List<Renderer> = coroutineScope {
        val replies = withContext(Dispatchers.IO) { search() }
        replies.distinctBy { it.second.location }
            .map { (from, reply) -> async(Dispatchers.IO) { describe(from, reply) } }
            .awaitAll().filterNotNull().distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Sends the question and collects every answer until [listenMs] has passed, with the address each came from. */
    private fun search(): List<Pair<InetAddress, SsdpReply>> = DatagramSocket().use { socket ->
        val ask = searchMessage(target).toByteArray(Charsets.US_ASCII)
        // sent twice: a lost packet on Wi-Fi is common, and the answers are deduplicated anyway
        repeat(2) { socket.send(DatagramPacket(ask, ask.size, target)) }
        val until = System.currentTimeMillis() + listenMs
        val found = mutableListOf<Pair<InetAddress, SsdpReply>>()
        val buffer = ByteArray(2048)
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            socket.soTimeout = left.toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
            parseReply(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let { found += packet.address to it }
        }
        found
    }

    private fun describe(from: InetAddress, reply: SsdpReply): Renderer? {
        // only from the device that answered: otherwise anything on the network could make the phone fetch any address
        val host = runCatching { URI(reply.location).host }.getOrNull() ?: return null
        if (runCatching { InetAddress.getByName(host) }.getOrNull() != from) return null
        // no redirects (a device could send the phone to another address) and at most MAX_DESCRIPTION bytes read
        val plain = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val xml = runCatching {
            plain.newCall(Request.Builder().url(reply.location).build()).execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null) null
                else { source.request(MAX_DESCRIPTION + 1); if (source.buffer.size > MAX_DESCRIPTION) null else source.buffer.readUtf8() }
            }
        }.getOrNull() ?: return null
        return parseDescription(xml, reply.location)
    }

    companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        /** A description is a few KB; a device sending more than this is not described. */
        const val MAX_DESCRIPTION = 1_000_000L
        const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"

        /** The question asked on the network: "which media renderers are there?", answered within 2 seconds. */
        fun searchMessage(to: InetSocketAddress = InetSocketAddress(SSDP_HOST, SSDP_PORT)) =
            "M-SEARCH * HTTP/1.1\r\nHOST: ${to.hostString}:${to.port}\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $MEDIA_RENDERER\r\n\r\n"

        /** An answer to the search, or null when it isn't one (or isn't from a media renderer). */
        fun parseReply(text: String): SsdpReply? {
            val lines = text.split("\r\n", "\n")
            if (!lines.first().trim().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val at = line.indexOf(':')
                if (at <= 0) null else line.substring(0, at).trim().uppercase() to line.substring(at + 1).trim()
            }.toMap()
            if (headers["ST"]?.equals(MEDIA_RENDERER, ignoreCase = true) == false) return null
            val location = headers["LOCATION"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            return SsdpReply(location, headers["USN"].orEmpty())
        }

        /**
         * The device's own description, read for its name and where to send play and volume. Null when it isn't a
         * media renderer or can't be played to.
         */
        fun parseDescription(xml: String, location: String): Renderer? {
            val doc = runCatching {
                safeXml().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            }.getOrNull() ?: return null
            // URLBase is old (UPnP 1.0) but some TVs still send it; otherwise addresses are relative to the description
            val base = doc.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
            val devices = doc.getElementsByTagNameNS("*", "device").elements()
            // the renderer can be the main device or one inside it (a TV that is also a media server, say)
            val device = devices.firstOrNull { it.child("deviceType")?.startsWith("urn:schemas-upnp-org:device:MediaRenderer:") == true } ?: return null
            val services = device.getElementsByTagNameNS("*", "service").elements()
            fun control(type: String) = services.firstOrNull { it.child("serviceType")?.startsWith(type) == true }?.child("controlURL")?.let { resolve(base, it) }
            val transport = control(AV_TRANSPORT) ?: return null
            return Renderer(
                id = device.child("UDN") ?: location,
                name = device.child("friendlyName")?.takeIf { it.isNotBlank() } ?: device.child("modelName") ?: "Speaker",
                model = listOfNotNull(device.child("manufacturer"), device.child("modelName")).joinToString(" ").ifBlank { null },
                transport = transport,
                volume = control(RENDERING_CONTROL),
            )
        }

        private fun resolve(base: String, path: String): String? = runCatching { URI(base).resolve(path.trim()).toString() }.getOrNull()

        /** A parser that never fetches anything the description points to: it comes from any device on the network. */
        private fun safeXml() = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

        private fun org.w3c.dom.NodeList.elements() = (0 until length).mapNotNull { item(it) as? Element }

        /** The text of a direct child, so a nested device's name isn't taken for its parent's. */
        private fun Element.child(name: String): String? {
            var n = firstChild
            while (n != null) {
                if (n is Element && (n.localName ?: n.nodeName) == name) return n.textContent.trim()
                n = n.nextSibling
            }
            return null
        }
    }
}
