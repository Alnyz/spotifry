package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artist: String, val durationSec: Long)
data class SpotifyPlaylist(val name: String, val tracks: List<SpotifyTrack>)

/**
 * Reads a public Spotify playlist from its embed page, which needs no login.
 * The embed page lists only the first 100 songs. For the rest it hands out an anonymous token, which the web player's own
 * playlist query accepts. Unofficial: Spotify can change it, then only the first 100 are read.
 * ponytail: fixed query hash, no refresh from the web player's script; add when it goes stale.
 */
object SpotifyImport {
    fun playlistId(link: String): String? =
        Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?playlist/([A-Za-z0-9]+)").find(link)?.groupValues?.get(1)

    suspend fun fetch(client: OkHttpClient, id: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://open.spotify.com/embed/playlist/$id").header("User-Agent", "Mozilla/5.0").build()
        val html = client.newCall(req).execute().use { if (!it.isSuccessful) error("Spotify said ${it.code}"); it.body!!.string() }
        val first = parse(html)
        if (first.tracks.size < EMBED_LIMIT) return@withContext first
        val more = runCatching { fetchRest(client, id, token(html), first.tracks.size) }.getOrDefault(emptyList())
        first.copy(tracks = first.tracks + more)
    }

    private fun nextData(html: String) = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: error("no playlist data on the page")

    private fun token(html: String) = JSONObject(nextData(html)).getJSONObject("props").getJSONObject("pageProps")
        .getJSONObject("state").getJSONObject("settings").getJSONObject("session").getString("accessToken")

    private fun fetchRest(client: OkHttpClient, id: String, token: String, start: Int): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        var offset = start
        while (true) {
            val variables = """{"uri":"spotify:playlist:$id","offset":$offset,"limit":$PAGE,"enableWatchFeedEntrypoint":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$QUERY_HASH"}}"""
            val url = "https://api-partner.spotify.com/pathfinder/v1/query".toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions).build()
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer").header("Accept", "application/json").build()
            val body = client.newCall(req).execute().use { if (!it.isSuccessful) error("Spotify said ${it.code}"); it.body!!.string() }
            val (page, total) = parsePage(body)
            out += page
            offset += page.size
            if (page.isEmpty() || offset >= total) return out
        }
    }

    /** One page of the web player's playlist query: its songs and how many the playlist has in all. */
    fun parsePage(body: String): Pair<List<SpotifyTrack>, Int> {
        val content = JSONObject(body).getJSONObject("data").getJSONObject("playlistV2").getJSONObject("content")
        val items = content.getJSONArray("items")
        val tracks = (0 until items.length()).mapNotNull { i ->
            val d = items.getJSONObject(i).optJSONObject("itemV2")?.optJSONObject("data") ?: return@mapNotNull null
            val title = d.optString("name").trim().ifEmpty { return@mapNotNull null }
            val artists = d.optJSONObject("artists")?.optJSONArray("items")
            val artist = (0 until (artists?.length() ?: 0)).mapNotNull { artists!!.getJSONObject(it).optJSONObject("profile")?.optString("name") }
            // duration 0 = unknown; pick() then skips the length check
            val ms = d.optJSONObject("trackDuration")?.optLong("totalMilliseconds") ?: 0L
            SpotifyTrack(title, artist.joinToString(", "), ms / 1000)
        }
        return tracks to content.optInt("totalCount", 0)
    }

    fun parse(html: String): SpotifyPlaylist {
        val json = nextData(html)
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
            (track.durationSec == 0L || abs(s.durationSec - track.durationSec) <= TOLERANCE_SEC) && junk.none { it in "${s.title} ${s.artist}".lowercase() }
        }
        val artist = track.artist.substringBefore(',').trim().lowercase()
        val byArtist: (Song) -> Boolean = { artist.isNotEmpty() && artist in it.artist.lowercase() }
        val byTitle: (Song) -> Boolean = { title in it.title.lowercase() }
        return ok.firstOrNull { byArtist(it) && byTitle(it) } ?: ok.firstOrNull(byArtist) ?: ok.firstOrNull(byTitle) ?: ok.firstOrNull()
    }

    suspend fun match(source: MusicSource, track: SpotifyTrack): Song? =
        pick(track, source.search("${track.title} ${track.artist}"))

    private const val TOLERANCE_SEC = 5
    private const val EMBED_LIMIT = 100
    private const val PAGE = 100
    private const val QUERY_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"
    private val JUNK = listOf("karaoke", "instrumental", "backing track", "sunfly")
}
