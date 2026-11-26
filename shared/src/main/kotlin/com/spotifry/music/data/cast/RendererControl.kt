package com.spotifry.music.data.cast

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** What the TV says it is doing. Anything a TV sends that isn't one of these reads as [Other]. */
enum class TransportState { Playing, Paused, Stopped, Transitioning, NoMedia, Other }

/** How far into the song the TV is, and how long the song is, in milliseconds. Null when the TV doesn't say. */
data class TransportPosition(val positionMs: Long?, val durationMs: Long?)

/** The TV answered, but said no. [code] is the UPnP error number when it sent one (701 is "can't do that now"). */
class RendererException(message: String, val code: Int? = null) : IOException(message)

/**
 * Play, pause, seek and volume on one [Renderer], sent the way every DLNA TV expects (a small SOAP message per
 * command). It only talks to the addresses the device gave in its own description; it never follows a redirect and
 * reads at most [MAX_ANSWER] bytes of an answer.
 *
 * Nothing here knows where the song comes from: [load] takes an address the TV can reach, which the phone serves.
 */
class RendererControl(client: OkHttpClient, private val renderer: Renderer) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Tells the TV which song to play next. The TV only starts after [play]. */
    suspend fun load(url: String, title: String, artist: String?, mimeType: String) {
        transport(
            "SetAVTransportURI",
            "CurrentURI" to url,
            "CurrentURIMetaData" to metadata(url, title, artist, mimeType),
        )
    }

    suspend fun play() { transport("Play", "Speed" to "1") }
    suspend fun pause() { transport("Pause") }
    suspend fun stop() { transport("Stop") }

    suspend fun seek(positionMs: Long) { transport("Seek", "Unit" to "REL_TIME", "Target" to formatTime(positionMs)) }

    suspend fun state(): TransportState = parseState(transport("GetTransportInfo")["CurrentTransportState"])

    suspend fun position(): TransportPosition {
        val answer = transport("GetPositionInfo")
        return TransportPosition(parseTime(answer["RelTime"]), parseTime(answer["TrackDuration"]))
    }

    /** False when the device doesn't let the phone change its volume (then its own remote does). */
    val hasVolume get() = renderer.volume != null

    /** 0 to 100, as the TV counts it. */
    suspend fun volume(): Int? = renderer.volume?.let { url ->
        send(url, RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))["CurrentVolume"]?.toIntOrNull()
    }

    suspend fun setVolume(percent: Int) {
        val url = renderer.volume ?: return
        send(url, RENDERING_CONTROL, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()))
    }

    private suspend fun transport(action: String, vararg args: Pair<String, String>) =
        send(renderer.transport, AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private suspend fun send(url: String, service: String, action: String, args: List<Pair<String, String>>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("SOAPACTION", "\"$service#$action\"")
                .post(envelope(service, action, args).toRequestBody(XML))
                .build()
            client.newCall(request).execute().use { response ->
                val source = response.body?.source() ?: throw RendererException("$action: no answer")
                source.request(MAX_ANSWER + 1)
                if (source.buffer.size > MAX_ANSWER) throw RendererException("$action: answer too long")
                val body = source.buffer.readUtf8()
                // a refused command comes back as 500 with the reason inside
                if (!response.isSuccessful) throw fault(action, body) ?: RendererException("$action: HTTP ${response.code}")
                parseAnswer(body, action) ?: throw RendererException("$action: answer not understood")
            }
        }

    companion object {
        /** An answer is a few hundred bytes; more than this is not read. */
        const val MAX_ANSWER = 64_000L
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        /** Song titles and addresses go inside XML, so &, < and quotes must not end up as markup. */
        fun escape(text: String) = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // characters XML can't hold at all are dropped rather than sent
                else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
            }
        }

        fun envelope(service: String, action: String, args: List<Pair<String, String>>) = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:$action xmlns:u=\"$service\">")
            for ((name, value) in args) append("<$name>").append(escape(value)).append("</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }

        /**
         * What the TV shows while the song plays. Many TVs refuse a song without it, and most need the type in
         * protocolInfo to know they can play it.
         */
        fun metadata(url: String, title: String, artist: String?, mimeType: String) = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(escape(title)).append("</dc:title>")
            if (!artist.isNullOrBlank()) append("<upnp:artist>").append(escape(artist)).append("</upnp:artist>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"http-get:*:").append(escape(mimeType)).append(":*\">").append(escape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }

        /** The values inside the answer to [action], by name. Null when it isn't that answer. */
        fun parseAnswer(xml: String, action: String): Map<String, String>? {
            val answer = parse(xml)?.getElementsByTagNameNS("*", "${action}Response")?.item(0) as? Element ?: return null
            val values = mutableMapOf<String, String>()
            var n = answer.firstChild
            while (n != null) {
                if (n is Element) values[n.localName ?: n.nodeName] = n.textContent.trim()
                n = n.nextSibling
            }
            return values
        }

        /** Why the TV refused, when it said. */
        fun fault(action: String, xml: String): RendererException? {
            val doc = parse(xml) ?: return null
            val code = doc.getElementsByTagNameNS("*", "errorCode").item(0)?.textContent?.trim()?.toIntOrNull()
            val text = doc.getElementsByTagNameNS("*", "errorDescription").item(0)?.textContent?.trim()
            if (code == null && text == null) return null
            return RendererException("$action: ${text ?: "refused"}${code?.let { " ($it)" }.orEmpty()}", code)
        }

        fun parseState(text: String?) = when (text?.trim()?.uppercase(Locale.ROOT)) {
            "PLAYING" -> TransportState.Playing
            "PAUSED_PLAYBACK" -> TransportState.Paused
            "STOPPED" -> TransportState.Stopped
            "TRANSITIONING" -> TransportState.Transitioning
            "NO_MEDIA_PRESENT" -> TransportState.NoMedia
            else -> TransportState.Other
        }

        /** "H:MM:SS", the only form every TV reads. */
        fun formatTime(ms: Long): String {
            val s = ms.coerceAtLeast(0) / 1000
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }

        /** "H:MM:SS" with or without a fraction. TVs send "NOT_IMPLEMENTED" or 0:00:00 when they don't know. */
        fun parseTime(text: String?): Long? {
            val parts = text?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val s = parts[2].toDoubleOrNull() ?: return null
            if (h < 0 || m !in 0..59 || s < 0 || s >= 60) return null
            return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
        }

        private fun parse(xml: String) = runCatching {
            RendererFinder.safeXml().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }
}
