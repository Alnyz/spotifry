package com.spotifry.music.data.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** A song in Monochrome's search results. [durationMs] is as Tidal gives it. */
data class MonochromeResult(val id: String, val title: String, val artists: List<String>, val durationMs: Long, val playable: Boolean)

/**
 * The song as lossless FLAC from Monochrome's track server (the one Mochy Music also uses), or null without a sure match.
 * Unofficial and not ours: the server plays through someone else's Tidal account and can go away any day, so a failure
 * is remembered for a few minutes instead of costing every song a wait.
 */
class MonochromeSource(
    private val client: OkHttpClient,
    private val base: String = "https://tracks.monochrome.st",
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var downUntil = 0L

    suspend fun find(song: Song): ResolvedStream? {
        if (now() < downUntil) return null
        try {
            val search = base.toHttpUrl().newBuilder().addPathSegments("search/tracks")
                .addQueryParameter("q", "${cleanTitle(song.title)} ${song.artist.removeSuffix(" - Topic")}").addQueryParameter("limit", "8").build()
            val body = client.newCall(request(search.toString()).build()).read { r -> if (r.isSuccessful) r.body!!.string() else throw IOException("HTTP ${r.code}") }
            val hit = parseMonochromeResults(body).firstOrNull { it.playable && isSameRecording(song, it.title, it.artists, "", it.durationMs / 1000) } ?: return null
            val link = base.toHttpUrl().newBuilder().addPathSegments("track").addPathSegment(hit.id).build().toString()
            // the first bytes say whether it really is FLAC; the range's total says how big, which gives the bitrate for the label
            val total = client.newCall(request(link).header("Range", "bytes=0-3").build()).read { r ->
                if (r.code != 206 && r.code != 200) return@read null
                val isFlac = r.body!!.source().readUtf8(4) == "fLaC"
                if (isFlac) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L else null
            } ?: return null
            val kbps = if (total > 0 && hit.durationMs > 0) (total * 8 / hit.durationMs).toInt() else 0
            return ResolvedStream(link, "audio/flac", kbps, now() + LINK_LIFE_MS, fromMonochrome = true)
        } catch (e: IOException) {
            downUntil = now() + DOWN_MS
            return null
        }
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT)

    private companion object {
        const val LINK_LIFE_MS = 60 * 60 * 1000L
        const val DOWN_MS = 5 * 60 * 1000L
    }
}

fun parseMonochromeResults(json: String): List<MonochromeResult> {
    val tracks = JSONObject(json).optJSONArray("tracks") ?: return emptyList()
    return List(tracks.length()) { tracks.getJSONObject(it) }.mapNotNull { t ->
        val names = t.optJSONArray("artistNames")
        MonochromeResult(
            id = t.optString("trackId").ifEmpty { return@mapNotNull null },
            title = t.optString("title"),
            artists = List(names?.length() ?: 0) { names!!.getString(it) },
            durationMs = t.optLong("duration"),
            playable = t.optBoolean("playable", true),
        )
    }
}
