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

/** A "no logo for this artist" answer is trusted this long. */
const val NO_LOGO_DAYS = 30

/**
 * Official artist logos: the artist's MusicBrainz id, then fanart.tv's logos for it, with the listener's own [key].
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
) {
    private sealed interface Answer {
        data class Logo(val url: String) : Answer
        data object None : Answer
        data object Later : Answer
    }

    suspend fun logo(artist: String): File? = withContext(Dispatchers.IO) {
        val k = key().trim()
        val want = plain(artist)
        if (k.isEmpty() || want.isEmpty()) return@withContext null
        val id = sha1(want)
        val file = File(dir, "$id.png")
        val none = File(dir, "$id.none")
        if (file.isFile) return@withContext file
        if (none.isFile && clock() - (none.readText().toLongOrNull() ?: 0) < NO_LOGO_DAYS * 86_400_000L) return@withContext null
        try {
            val answer = when (val mbid = musicBrainzId(artist, want)) {
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
        val url = fanartBase.toHttpUrl().newBuilder().addPathSegments("v3/music").addPathSegment(mbid).addQueryParameter("api_key", k).build()
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
