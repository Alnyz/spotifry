package com.spotifry.music.data

import android.content.SharedPreferences
import com.spotifry.music.data.db.BackupDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The phone's SharedPreferences as the shared code's [Prefs]. */
class AndroidPrefs(private val sp: SharedPreferences) : Prefs {
    override val all: Map<String, Any?> get() = sp.all
    override fun contains(key: String) = sp.contains(key)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun getFloat(key: String, default: Float) = sp.getFloat(key, default)
    override fun getString(key: String, default: String?) = sp.getString(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sp.getStringSet(key, default)
    override fun edit(): Prefs.Editor = object : Prefs.Editor {
        private val e = sp.edit()
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putStringSet(key: String, value: Set<String>?) = apply { e.putStringSet(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}

fun SharedPreferences.asPrefs(): Prefs = AndroidPrefs(this)

// These take the phone's SharedPreferences, so the rest of the phone app builds them as it always did.
fun Settings(prefs: SharedPreferences) = Settings(prefs.asPrefs())
fun PlaylistOrders(prefs: SharedPreferences) = PlaylistOrders(prefs.asPrefs())
fun RestoreQueue(
    prefs: SharedPreferences, songs: SongDao, save: suspend (Song) -> Unit, metered: () -> Boolean,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) = RestoreQueue(prefs.asPrefs(), songs, save, metered, scope)
fun Backups(
    dao: BackupDao, app: SharedPreferences, trims: SharedPreferences, waiting: RestoreQueue, appVersion: String,
    now: () -> Long = System::currentTimeMillis,
) = Backups(dao, app.asPrefs(), trims.asPrefs(), waiting, appVersion, now)
