package com.spotifry.music.data

import com.spotifry.music.data.source.RadioStation
import com.spotifry.music.data.source.parseStations
import com.spotifry.music.data.source.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** The stations the listener starred, newest first; kept in [prefs], so a backup carries them. */
class RadioFavourites(private val prefs: Prefs) {
    private val _stations = MutableStateFlow(read())
    val stations: StateFlow<List<RadioStation>> = _stations

    private fun read() = prefs.getString(Settings.RADIO_FAVOURITES, null)?.let { runCatching { parseStations(it) }.getOrNull() }.orEmpty()

    fun isFavourite(uuid: String) = _stations.value.any { it.uuid == uuid }

    fun toggle(s: RadioStation) {
        val now = _stations.value
        val next = if (now.any { it.uuid == s.uuid }) now.filterNot { it.uuid == s.uuid } else listOf(s) + now
        _stations.value = next
        prefs.edit().putString(Settings.RADIO_FAVOURITES, JSONArray(next.map { it.toJson() }).toString()).apply()
    }
}
