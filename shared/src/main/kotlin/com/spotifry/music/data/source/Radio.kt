package com.spotifry.music.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A live internet radio station from the open radio-browser.info list. */
data class RadioStation(
    val uuid: String, val name: String, val url: String, val logo: String?,
    val country: String, val tags: String, val codec: String, val bitrate: Int,
)

/** The station as something the player can queue: a song with no length, whose address is the live stream. */
fun RadioStation.toSong() = Song(
    id = "radio:$uuid", title = name,
    artist = listOfNotNull(country.ifBlank { null }, tags.split(',').firstOrNull { it.isNotBlank() }?.trim()).joinToString(" · "),
    durationSec = 0, thumbnailUrl = logo, streamUrl = url,
)

/** The station behind a radio song, for starring the one that is playing (its country and first tag come back out of the artist line). */
fun Song.toStation() = RadioStation(
    uuid = id.removePrefix("radio:"), name = title, url = streamUrl.orEmpty(), logo = thumbnailUrl,
    country = artist.substringBefore(" · ").takeIf { " · " in artist } ?: artist, tags = artist.substringAfter(" · ", ""), codec = "", bitrate = 0,
)

/** Stations the list marks as working, with an address; a name or address missing drops the entry. */
fun parseStations(json: String): List<RadioStation> {
    val a = JSONArray(json)
    return (0 until a.length()).mapNotNull { i ->
        val o = a.getJSONObject(i)
        val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
        val name = o.optString("name").trim()
        val id = o.optString("stationuuid")
        if (url.isEmpty() || name.isEmpty() || id.isEmpty() || !url.startsWith("http")) null
        else RadioStation(id, name, url, o.optString("favicon").ifBlank { null }?.takeIf { it.startsWith("http") }, o.optString("country").trim(),
            o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(","), o.optString("codec"), o.optInt("bitrate"))
    }
}

fun RadioStation.toJson(): JSONObject = JSONObject().put("stationuuid", uuid).put("name", name).put("url_resolved", url)
    .put("favicon", logo ?: "").put("country", country).put("tags", tags).put("codec", codec).put("bitrate", bitrate)

/** The radio-browser.info servers among [names], each once, in random order: the list is only a way in, so no one server takes everything. */
internal fun serverNames(names: List<String>, random: kotlin.random.Random = kotlin.random.Random.Default): List<String> =
    names.map { it.trim().trimEnd('.') }.filter { it.endsWith(".api.radio-browser.info") }.distinct().shuffled(random)

/** The servers the list's own DNS name points to (as its docs ask), by name since their certificates are by name. */
private fun lookUpServers(): List<String> = serverNames(
    java.net.InetAddress.getAllByName("all.api.radio-browser.info").take(8).map { it.canonicalHostName },
)

/**
 * Asks radio-browser.info, which is free and open and needs no key. Nothing about the listener is sent.
 * The servers come from its DNS name (as its docs ask) and are tried in random order; the three below are only the
 * way out when that lookup fails.
 */
class RadioBrowser(private val client: OkHttpClient, private val servers: () -> List<String> = ::lookUpServers) {
    @Volatile private var known: List<String>? = null

    private fun hosts(): List<String> = known ?: (runCatching { servers() }.getOrDefault(emptyList()).ifEmpty { HOSTS }).also { known = it }

    /** Tells the list a station was played: that is how stations get to be popular for everyone. Only its id is sent; a failure is no matter. */
    suspend fun click(uuid: String) = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://${hosts().first()}/json/url/".toHttpUrl().newBuilder().addPathSegment(uuid).build()
            client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifry").build()).execute().close()
        }
        Unit
    }

    /** The most listened stations that match; an empty filter means no filter. */
    suspend fun search(name: String = "", countryCode: String = "", tag: String = "", limit: Int = 40): List<RadioStation> = withContext(Dispatchers.IO) {
        var last: IOException? = null
        for (host in hosts()) {
            try {
                val url = "https://$host/json/stations/search".toHttpUrl().newBuilder()
                    .addQueryParameter("hidebroken", "true").addQueryParameter("order", "clickcount").addQueryParameter("reverse", "true")
                    .addQueryParameter("limit", limit.toString())
                    .apply {
                        if (name.isNotBlank()) addQueryParameter("name", name.trim())
                        if (countryCode.isNotBlank()) addQueryParameter("countrycode", countryCode)
                        if (tag.isNotBlank()) addQueryParameter("tag", tag)
                    }.build()
                client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifry").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("radio list answered ${r.code}")
                    return@withContext parseStations(r.body?.string().orEmpty())
                }
            } catch (e: IOException) { last = e }
        }
        // none answered: ask the lookup again next time
        known = null
        throw last ?: IOException("no radio list")
    }

    companion object {
        val HOSTS = listOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info", "at1.api.radio-browser.info")
    }
}
