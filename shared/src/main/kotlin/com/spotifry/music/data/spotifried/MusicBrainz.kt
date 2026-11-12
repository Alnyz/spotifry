package com.spotifry.music.data.spotifried

import com.spotifry.music.data.source.artistNames
import com.spotifry.music.data.source.cleanTitle
import com.spotifry.music.data.source.read
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

sealed interface YearResult {
    data class Found(val year: Int) : YearResult
    data object NotFound : YearResult
    data object TryLater : YearResult
}

private fun lucene(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

/** Asks for 100 results because the original release is often far down the list, below later compilations. */
class MusicBrainz(
    private val client: OkHttpClient,
    private val base: String = "https://musicbrainz.org",
    private val userAgent: String,
) {
    suspend fun yearOf(title: String, artist: String): YearResult {
        if (cleanTitle(title).isEmpty()) return YearResult.NotFound
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("ws/2/recording")
            .addQueryParameter("query", "recording:\"${lucene(title)}\" AND artist:\"${lucene(artist)}\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "100")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return try {
            client.newCall(request).read { r ->
                if (!r.isSuccessful) {
                    // a rate limit or a server problem passes; any other refusal would repeat for ever, so it counts as not found
                    return@read if (r.code == 429 || r.code >= 500) YearResult.TryLater else YearResult.NotFound
                }
                val year = try {
                    earliestYear(r.body!!.string(), title, artist)
                } catch (e: org.json.JSONException) {
                    return@read YearResult.TryLater
                }
                if (year == null) YearResult.NotFound else YearResult.Found(year)
            }
        } catch (e: IOException) {
            YearResult.TryLater
        }
    }
}

/** Throws JSONException on a body that is not JSON. */
fun earliestYear(json: String, title: String, artist: String): Int? {
    val wantTitle = cleanTitle(title)
    val wantArtists = artistNames(artist)
    val recordings = JSONObject(json).optJSONArray("recordings") ?: return null
    return (0 until recordings.length()).asSequence()
        .map { recordings.getJSONObject(it) }
        .filter { it.optInt("score") >= 90 && cleanTitle(it.optString("title")) == wantTitle }
        .filter { rec ->
            val credits = rec.optJSONArray("artist-credit") ?: return@filter false
            (0 until credits.length()).any { i ->
                val c = credits.getJSONObject(i)
                val names = listOf(c.optString("name"), c.optJSONObject("artist")?.optString("name").orEmpty())
                names.any { n -> artistNames(n).any { it in wantArtists } }
            }
        }
        .mapNotNull { it.optString("first-release-date").take(4).toIntOrNull() }
        .filter { it >= 1900 }
        .minOrNull()
}
