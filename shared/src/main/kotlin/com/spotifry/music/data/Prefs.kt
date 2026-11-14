package com.spotifry.music.data

/**
 * The little key-value store the app's settings, trims and playlist orders live in. It has the shape of Android's
 * SharedPreferences, so the phone wraps its own and the desktop keeps a file; the code that reads and writes
 * settings is the same on both.
 */
interface Prefs {
    val all: Map<String, Any?>
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** Saves at once in memory, to disk when it can. */
        fun apply()
        /** Saves and says whether it reached the disk. */
        fun commit(): Boolean
    }
}

/** Keys that belong to one device's install, never to the listener's choices, so a backup leaves them out. */
const val UPDATE_LAST_CHECK = "update_last_check"
const val UPDATE_LATER = "update_later"

/** Lives in memory only: for tests, and the base of the desktop's file-backed store. */
open class MemoryPrefs : Prefs {
    protected val map = linkedMapOf<String, Any?>()
    override val all: Map<String, Any?> get() = LinkedHashMap(map)
    override fun contains(key: String) = map.containsKey(key)
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    override fun getString(key: String, default: String?) = if (map.containsKey(key)) map[key] as? String else default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = if (map.containsKey(key)) map[key] as? Set<String> else default

    /** Called after every save, with the whole content. The desktop overrides it to write its file. */
    protected open fun saved(): Boolean = true

    override fun edit(): Prefs.Editor = object : Prefs.Editor {
        private val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
        private fun put(key: String, value: Any?) = apply { changes += { m -> if (value == null) m.remove(key) else m[key] = value } }
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, value: Set<String>?) = put(key, value?.toSet())
        override fun remove(key: String) = apply { changes += { m -> m.remove(key) } }
        override fun clear() = apply { changes += { m -> m.clear() } }
        override fun commit(): Boolean { changes.forEach { it(map) }; changes.clear(); return saved() }
        override fun apply() { commit() }
    }
}
