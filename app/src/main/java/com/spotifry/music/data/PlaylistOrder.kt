package com.spotifry.music.data

import android.content.SharedPreferences
import com.spotifry.music.data.db.SongEntity

/** How a playlist's songs are listed and played. */
enum class PlaylistOrder(val label: String) {
    CUSTOM("Your order"), RECENT("Recently added"), TITLE("Song name A–Z"), ARTIST("Artist A–Z");

    /** Sorts by name or artist; [songs] already come from the database in your order or newest first. */
    fun arrange(songs: List<SongEntity>): List<SongEntity> = when (this) {
        CUSTOM, RECENT -> songs
        TITLE -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        ARTIST -> songs.sortedWith(compareBy<SongEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

/** The song's name or artist contains every word typed, in any case. */
fun SongEntity.matches(query: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.all { title.contains(it, ignoreCase = true) || artist.contains(it, ignoreCase = true) }
}

/** The order picked for each playlist, kept on the phone. Without prefs (tests) it only lives in memory. */
class PlaylistOrders(private val prefs: SharedPreferences?) {
    private val memory = mutableMapOf<Long, PlaylistOrder>()
    fun get(id: Long): PlaylistOrder = memory[id]
        ?: runCatching { PlaylistOrder.valueOf(prefs?.getString(key(id), null)!!) }.getOrDefault(PlaylistOrder.CUSTOM)
    fun set(id: Long, order: PlaylistOrder) { memory[id] = order; prefs?.edit()?.putString(key(id), order.name)?.apply() }
    private fun key(id: Long) = "playlist_order_$id"
}
