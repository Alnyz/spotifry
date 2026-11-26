package com.spotifry.music.data.spotifried

import com.spotifry.music.data.isPng
import com.spotifry.music.data.source.read
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** How long to wait before asking a busy MusicBrainz again. */
private val BUSY_WAITS_MS = listOf(1_500L, 3_000L, 5_000L)

/** A "no logo for this artist" answer is trusted this long. */
const val NO_LOGO_DAYS = 30

/**
 * Official artist logos: the artist's MusicBrainz id, then fanart.tv's logos for it. fanart.tv asks for the app's own
 * [projectKey]; the listener's own [key] is optional and goes along as their personal key (newer logos show up sooner).
 * With no project key, the listener's key alone does the job.
 * Logos and "none found" markers are kept in [dir]. Only a clear "no match" leaves a marker; a refused key, a limit or a
 * network problem leaves nothing, so the next visit asks again.
 */
class ArtistLogos(
    private val client: OkHttpClient,
    private val dir: File,
    private val userAgent: String,
    private val key: () -> String,
    private val mbBase: String = "https://musicbrainz.org",
    private val fanartBase: String = "https://webservice.fanart.tv",
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val wikidataBase: String = "https://www.wikidata.org",
    private val projectKey: String = "",
) {
    private sealed interface Answer {
        data class Logo(val url: String) : Answer
        data object None : Answer
        data object Later : Answer
    }

    suspend fun logo(artist: String): File? = withContext(Dispatchers.IO) {
        val k = key().trim()
        val want = plain(artist)
        if ((k.isEmpty() && projectKey.isEmpty()) || want.isEmpty()) return@withContext null
        val id = sha1(want)
        val file = File(dir, "$id.png")
        val none = File(dir, "$id.none")
        if (file.isFile) return@withContext file
        if (none.isFile && clock() - (none.readText().toLongOrNull() ?: 0) < NO_LOGO_DAYS * 86_400_000L) return@withContext null
        try {
            val answer = when (val mbid = musicBrainzIdPatiently(artist, want)) {
                null -> Answer.None
                "" -> Answer.Later
                else -> fanartLogo(mbid, k)
            }
            when (answer) {
                Answer.None -> { dir.mkdirs(); none.writeText(clock().toString()); null }
                Answer.Later -> null
                is Answer.Logo -> download(answer.url)?.let { bytes -> dir.mkdirs(); file.writeBytes(bytes); file }
            }
        } catch (e: IOException) { null } catch (e: JSONException) { null }
    }

    /** After a new key, artists marked "none found" are asked for again. */
    fun forgetNoneFound() { dir.listFiles { f -> f.name.endsWith(".none") }?.forEach { it.delete() } }

    /**
     * MusicBrainz often turns requests away for a moment (503): wait and ask again, a few times. Some networks can't reach it
     * at all; then, and when it stays busy, Wikidata gives the same id for most artists.
     */
    private suspend fun musicBrainzIdPatiently(artist: String, want: String): String? {
        for (wait in BUSY_WAITS_MS + 0L) {
            val id = try { musicBrainzId(artist, want) } catch (e: IOException) { break } catch (e: JSONException) { break }
            if (id != "") return id
            if (wait > 0) pause(wait)
        }
        return wikidataId(artist, want)
    }

    /** The MusicBrainz id Wikidata keeps (P434) on the first found item whose English name is exactly the artist's. Null for no match, "" for try later. */
    private suspend fun wikidataId(artist: String, want: String): String? {
        val api = wikidataBase.toHttpUrl().newBuilder().addPathSegments("w/api.php")
        val search = api.build().newBuilder().addQueryParameter("action", "wbsearchentities").addQueryParameter("search", artist.trim().removeSuffix(" - Topic"))
            .addQueryParameter("language", "en").addQueryParameter("type", "item").addQueryParameter("limit", "7").addQueryParameter("format", "json").build()
        val ids = client.newCall(Request.Builder().url(search).header("User-Agent", userAgent).build()).read { r ->
            if (!r.isSuccessful) return@read null
            val a = JSONObject(r.body!!.string()).optJSONArray("search") ?: return@read emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }.filter { plain(it.optString("label")) == want }.map { it.optString("id") }.filter { it.isNotEmpty() }
        } ?: return ""
        if (ids.isEmpty()) return null
        val get = api.build().newBuilder().addQueryParameter("action", "wbgetentities").addQueryParameter("ids", ids.joinToString("|"))
            .addQueryParameter("props", "claims").addQueryParameter("format", "json").build()
        return client.newCall(Request.Builder().url(get).header("User-Agent", userAgent).build()).read { r ->
            if (!r.isSuccessful) return@read ""
            val entities = JSONObject(r.body!!.string()).optJSONObject("entities") ?: return@read null
            ids.firstNotNullOfOrNull { id ->
                entities.optJSONObject(id)?.optJSONObject("claims")?.optJSONArray("P434")?.optJSONObject(0)
                    ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifEmpty { null }
            }
        }
    }

    /** The id; null for no match; "" for try later. */
    private suspend fun musicBrainzId(artist: String, want: String): String? {
        val url = mbBase.toHttpUrl().newBuilder().addPathSegments("ws/2/artist")
            .addQueryParameter("query", "artist:\"${artist.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            .addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
        return client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).read { r ->
            // a refusal (403: this app blocked, 401) passes like a limit; only a plain empty answer means no match
            if (!r.isSuccessful) return@read if (r.code == 400 || r.code == 404) null else ""
            val a = JSONObject(r.body!!.string()).optJSONArray("artists") ?: return@read null
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optInt("score") >= 90 && plain(it.optString("name")) == want }?.optString("id")?.ifEmpty { null }
        }
    }

    private suspend fun fanartLogo(mbid: String, k: String): Answer {
        val url = fanartBase.toHttpUrl().newBuilder().addPathSegments("v3/music").addPathSegment(mbid)
            .addQueryParameter("api_key", projectKey.ifEmpty { k })
            .apply { if (projectKey.isNotEmpty() && k.isNotEmpty()) addQueryParameter("client_key", k) }.build()
        return client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).read { r ->
            when {
                r.code == 404 -> Answer.None
                !r.isSuccessful -> Answer.Later
                else -> {
                    val o = JSONObject(r.body!!.string())
                    fun best(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                        ?.maxByOrNull { it.optString("likes").toIntOrNull() ?: 0 }?.optString("url")?.ifEmpty { null }
                    (best("hdmusiclogo") ?: best("musiclogo"))?.let { Answer.Logo(it) } ?: Answer.None
                }
            }
        }
    }

    /** The address comes from fanart.tv: one that isn't a web address gives no logo. */
    private suspend fun download(url: String): ByteArray? {
        val u = url.toHttpUrlOrNull() ?: return null
        return client.newCall(Request.Builder().url(u).header("User-Agent", userAgent).build()).read { r ->
            if (r.isSuccessful) r.body!!.bytes().takeIf { isPng(it.toByteString()) } else null
        }
    }

    private fun plain(name: String) = name.trim().lowercase().removeSuffix(" - topic").trim()
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
