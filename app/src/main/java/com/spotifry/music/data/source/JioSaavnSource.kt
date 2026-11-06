package com.spotifry.music.data.source

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** A song in JioSaavn's search results. [artists] are the performers: `more_info.music` is the composer, so a cover has it too. */
data class SaavnResult(
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationSec: Long,
    val has320: Boolean,
    val encryptedUrl: String,
)

/** The song at 320 kbps from JioSaavn's own website endpoint, or null without a sure match. Unofficial; no login, no relay. */
class JioSaavnSource(
    private val client: OkHttpClient,
    private val base: String = "https://www.jiosaavn.com",
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun find(song: Song): ResolvedStream? {
        val url = base.toHttpUrl().newBuilder().addPathSegment("api.php")
            .addQueryParameter("__call", "search.getResults").addQueryParameter("_format", "json").addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4").addQueryParameter("ctx", "web6dot0").addQueryParameter("n", "5").addQueryParameter("p", "1")
            .addQueryParameter("q", "${cleanTitle(song.title)} ${song.artist.removeSuffix(" - Topic")}").build()
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()).text()
        val hit = parseSaavnResults(body).firstOrNull { isSameSong(song, it) } ?: return null
        return ResolvedStream(url320(decryptMediaUrl(hit.encryptedUrl)) ?: return null, "audio/mp4", 320, now() + LINK_LIFE_MS)
    }

    private companion object { const val LINK_LIFE_MS = 60 * 60 * 1000L }
}

/** Cancelled with the coroutine, so the time cap holds even while a blocking call would still sit in a DNS lookup. */
private suspend fun Call.text(): String = suspendCancellableCoroutine { waiting ->
    waiting.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = waiting.resumeWith(Result.failure(e))
        override fun onResponse(call: Call, response: Response) = response.use { r ->
            waiting.resumeWith(runCatching { if (r.isSuccessful) r.body!!.string() else throw IOException("HTTP ${r.code}") })
        }
    })
}

fun parseSaavnResults(json: String): List<SaavnResult> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return List(results.length()) { results.getJSONObject(it) }.mapNotNull { r ->
        val info = r.optJSONObject("more_info") ?: return@mapNotNull null
        val map = info.optJSONObject("artistMap")
        SaavnResult(
            title = plainText(r.optString("title")),
            artists = (names(map?.optJSONArray("primary_artists")) + names(map?.optJSONArray("featured_artists"))).map(::plainText),
            album = plainText(info.optString("album")),
            durationSec = info.optString("duration").toLongOrNull() ?: 0,
            has320 = info.optString("320kbps") == "true",
            encryptedUrl = info.optString("encrypted_media_url"),
        )
    }
}

private fun names(list: JSONArray?) = List(list?.length() ?: 0) { list!!.getJSONObject(it).optString("name") }

/** Their text comes with HTML entities: Tum Hi Ho (From &quot;Aashiqui 2&quot;). */
private fun plainText(s: String) = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

/** The key is a public constant from their web player. */
fun decryptMediaUrl(encrypted: String): String {
    val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
    return String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted)))
}

/** The search gives the 96 kbps file; the 320 kbps one sits next to it. Null for a link that doesn't look as expected. */
fun url320(url: String): String? = url.takeIf { it.startsWith("https://") && "_96.mp4" in it }?.replace("_96.mp4", "_320.mp4")

private const val MAX_LENGTH_DIFF_SEC = 3

/**
 * Only a sure match, because a wrong song is worse than a lower bitrate: 320 kbps exists, same title once the extras are
 * gone, a performer in common, length within 3 s, and nothing marks it as another version unless our title says so too.
 */
fun isSameSong(song: Song, r: SaavnResult): Boolean {
    if (!r.has320 || song.durationSec <= 0 || abs(song.durationSec - r.durationSec) > MAX_LENGTH_DIFF_SEC) return false
    val title = cleanTitle(song.title)
    if (title.isEmpty() || title != cleanTitle(r.title)) return false
    if (artistNames(song.artist).intersect(r.artists.flatMap(::artistNames).toSet()).isEmpty()) return false
    val own = VERSION.findAll(plain(song.title)).map { it.value }.toSet()
    return VERSION.findAll(plain("${r.artists.joinToString(" ")} ${r.album}")).all { it.value in own }
}

/** Another recording or arrangement. A group with one of these words stays in the title, so it never matches the plain song. */
private val VERSION = Regex("\\b(cover|covers|karaoke|tribute|remix|remixes|instrumental|live|acoustic|unplugged|reprise|lofi|nightcore|slowed|reverb|sped|demo|version|edit|mix|techno|ambient)\\b")
/** Words on a group that says nothing about the recording: (feat. X), (Official Video), - Remastered 2011, (From "Film"). */
private val EXTRA = Regex("\\b(feat|ft|featuring|official|lyric|lyrics|visuali[sz]er|remaster|remastered|from)\\b")
private val GROUP = Regex("[(\\[][^)\\]]*[)\\]]|\\s[-–—|]\\s.*$")
private val TRAILING_FEAT = Regex("\\s(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
private val MARKS = Regex("\\p{M}+")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
private val ARTIST_JOINS = Regex(",|&|/|;|\\s(feat\\.?|ft\\.?|featuring|x|vs\\.?|with)\\s", RegexOption.IGNORE_CASE)

/** Lowercase, no accents, words and digits only. */
private fun plain(s: String) = NOT_WORD.replace(MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), ""), " ").trim()

private fun cleanTitle(title: String): String = plain(
    GROUP.replace(title) { m ->
        val group = plain(m.value)
        if (EXTRA.containsMatchIn(group) && !VERSION.containsMatchIn(group)) "" else m.value
    }.replace(TRAILING_FEAT, ""),
)

private fun artistNames(artist: String): Set<String> =
    artist.removeSuffix(" - Topic").removeSuffix("VEVO").split(ARTIST_JOINS).map(::plain).filter { it.isNotEmpty() }.toSet()
