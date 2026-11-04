package com.spotifry.music.player

import com.spotifry.music.data.source.Song
import org.json.JSONArray
import org.json.JSONObject

/** What was playing, kept on the phone so the app can pick up where it left off after it was closed. */
data class LastQueue(
    val songs: List<Song>, val index: Int, val positionMs: Long,
    val shuffle: Boolean, val repeatMode: Int, val from: String?,
) {
    fun encode(): String = JSONObject()
        .put("index", index).put("positionMs", positionMs).put("shuffle", shuffle).put("repeatMode", repeatMode).put("from", from ?: JSONObject.NULL)
        .put("songs", JSONArray(songs.map {
            JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("durationSec", it.durationSec)
                .put("thumbnailUrl", it.thumbnailUrl ?: JSONObject.NULL).put("localThumbPath", it.localThumbPath ?: JSONObject.NULL)
        }))
        .toString()

    companion object {
        /** Null for anything unreadable or empty, so a broken file just means a fresh start. */
        fun decode(text: String): LastQueue? = runCatching {
            val o = JSONObject(text)
            val a = o.getJSONArray("songs")
            val songs = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Song(it.getString("id"), it.getString("title"), it.getString("artist"), it.getLong("durationSec"),
                    it.optStringOrNull("thumbnailUrl"), it.optStringOrNull("localThumbPath"))
            }
            if (songs.isEmpty()) return null
            LastQueue(songs, o.getInt("index").coerceIn(songs.indices), o.getLong("positionMs").coerceAtLeast(0),
                o.getBoolean("shuffle"), o.getInt("repeatMode"), o.optStringOrNull("from"))
        }.getOrNull()

        private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k)
    }
}
