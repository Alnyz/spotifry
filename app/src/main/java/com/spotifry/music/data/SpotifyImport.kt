package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artist: String, val durationSec: Long)
data class SpotifyPlaylist(val name: String, val tracks: List<SpotifyTrack>)

/**
 * Reads a public Spotify playlist from its embed page, which needs no login.
 * ponytail: the embed page lists only the first 100 songs; the full list needs the Web API, which needs Premium.
 */
object SpotifyImport {
    fun playlistId(link: String): String? =
        Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?playlist/([A-Za-z0-9]+)").find(link)?.groupValues?.get(1)

    suspend fun fetch(client: OkHttpClient, id: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://open.spotify.com/embed/playlist/$id").header("User-Agent", "Mozilla/5.0").build()
        val html = client.newCall(req).execute().use { if (!it.isSuccessful) error("Spotify said ${it.code}"); it.body!!.string() }
        parse(html)
    }

    fun parse(html: String): SpotifyPlaylist {
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("no playlist data on the page")
        val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val list = entity.getJSONArray("trackList")
        val tracks = (0 until list.length()).map { list.getJSONObject(it) }.map {
            SpotifyTrack(it.getString("title"), it.optString("subtitle"), it.optLong("duration") / 1000)
        }
        return SpotifyPlaylist(entity.optString("name", "Spotify playlist"), tracks)
    }

    /**
     * Search results within a few seconds of the Spotify song's length, so long versions lose. Karaoke and
     * instrumental copies are dropped unless Spotify's title asks for one. Then artist and title both matching wins, then
     * the artist alone, then the title alone (keeps "Into The Night" from matching its Japanese original), then search order.
     */
    fun pick(track: SpotifyTrack, results: List<Song>): Song? {
        val title = track.title.lowercase()
        val junk = JUNK.filterNot { it in title }
        val ok = results.filter { s ->
            abs(s.durationSec - track.durationSec) <= TOLERANCE_SEC && junk.none { it in "${s.title} ${s.artist}".lowercase() }
        }
        val artist = track.artist.substringBefore(',').trim().lowercase()
        val byArtist: (Song) -> Boolean = { artist.isNotEmpty() && artist in it.artist.lowercase() }
        val byTitle: (Song) -> Boolean = { title in it.title.lowercase() }
        return ok.firstOrNull { byArtist(it) && byTitle(it) } ?: ok.firstOrNull(byArtist) ?: ok.firstOrNull(byTitle) ?: ok.firstOrNull()
    }

    suspend fun match(source: MusicSource, track: SpotifyTrack): Song? =
        pick(track, source.search("${track.title} ${track.artist}"))

    private const val TOLERANCE_SEC = 5
    private val JUNK = listOf("karaoke", "instrumental", "backing track", "sunfly")
}
