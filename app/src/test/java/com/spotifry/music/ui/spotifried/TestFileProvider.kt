package com.spotifry.music.ui.spotifried

import androidx.core.content.FileProvider

/** FileProvider keeps its folders per authority for the whole JVM; every Robolectric test has a new cache folder. */
internal fun forgetFileProviderFolders() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") (cache.get(null) as MutableMap<*, *>).clear()
}
