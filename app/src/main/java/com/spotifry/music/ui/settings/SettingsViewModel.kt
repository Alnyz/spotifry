package com.spotifry.music.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.RecentSearches
import com.spotifry.music.data.Settings
import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.PlayCountDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.ui.components.clearCoverMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import java.io.File

data class Storage(val savedCount: Int, val savedBytes: Long, val coverBytes: Long, val songCacheBytes: Long = 0, val dbBytes: Long = 0, val phoneTotal: Long = 0)

class SettingsViewModel(
    val settings: Settings,
    val recentSearches: RecentSearches,
    private val history: HistoryDao,
    private val plays: PlayCountDao,
    private val listens: com.spotifry.music.data.db.ListenDao,
    private val songs: SongDao,
    private val songsDir: File,
    private val covers: Cache?,
    private val songCache: androidx.media3.datasource.cache.Cache? = null,
    private val forgetLinks: () -> Unit = {},
    private val dbDir: File? = null,
) : ViewModel() {
    private val _storage = MutableStateFlow<Storage?>(null)
    /** Null while counting. Counted each time the page opens and after clearing covers. */
    val storage: StateFlow<Storage?> = _storage

    init { count() }

    fun count() = viewModelScope.launch(Dispatchers.IO) {
        // the songs folder holds the saved audio and its covers, nothing else
        val bytes = songsDir.listFiles()?.filter { it.extension != "part" }?.sumOf { it.length() } ?: 0
        val db = dbDir?.listFiles()?.filter { it.name.startsWith("music.db") }?.sumOf { it.length() } ?: 0
        val phone = runCatching { android.os.StatFs(songsDir.parentFile?.path ?: songsDir.path).totalBytes }.getOrNull() ?: 0
        _storage.value = Storage(songs.savedCount(), bytes, runCatching { covers?.size() }.getOrNull() ?: 0, runCatching { songCache?.cacheSpace }.getOrNull() ?: 0, db, phone)
    }

    fun clearCovers() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { covers?.evictAll() }
        clearCoverMemory()
        count()
    }

    /** Songs being played right now may keep a few MB until they finish; the rest goes at once. */
    fun clearSongCache() = viewModelScope.launch(Dispatchers.IO) {
        songCache?.let { c -> runCatching { c.keys.toList().forEach(c::removeResource) } }
        count()
    }

    fun setJioSaavn(on: Boolean) { settings.setJioSaavn(on); forgetLinks() }
    fun setMonochrome(on: Boolean) { settings.setMonochrome(on); forgetLinks() }

    fun clearSearches() = recentSearches.clear()

    fun clearHistory() = viewModelScope.launch { history.clear(); plays.clear(); listens.clear() }
}
