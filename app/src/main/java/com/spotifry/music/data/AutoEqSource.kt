package com.spotifry.music.data

import com.spotifry.music.player.AutoEqCurve
import com.spotifry.music.player.AutoEqEntry
import com.spotifry.music.player.parseAutoEqCsv
import com.spotifry.music.player.parseAutoEqIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Headphone measurements from the AutoEq project (MIT), read straight from its GitHub files: no key, no account. The
 * list is kept on disk and fetched again after a week, so search works offline once it has been loaded.
 */
class AutoEqSource(private val client: OkHttpClient, private val dir: File, private val base: String = BASE) {
    /** All results. Throws [IOException] when there is no connection and no saved list. */
    suspend fun index(): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        val file = File(dir, "INDEX.md")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < WEEK_MS
        val text = if (fresh) file.readText() else runCatching { get(base.toHttpUrl().newBuilder().addPathSegment("INDEX.md").build()) }
            .onSuccess { dir.mkdirs(); file.writeText(it) }
            .getOrElse { if (file.exists()) file.readText() else throw it as? IOException ?: IOException(it) }
        parseAutoEqIndex(text).ifEmpty { throw IOException("The headphone list came back empty") }
    }

    /** The measurement of one result. Throws [IOException] when it can't be fetched or read. */
    suspend fun curve(entry: AutoEqEntry): AutoEqCurve = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply {
            entry.path.split('/').forEach { addPathSegment(it) }
            addPathSegment(entry.path.substringAfterLast('/') + ".csv")
        }.build()
        parseAutoEqCsv(get(url))
    }

    private fun get(url: okhttp3.HttpUrl): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IOException("AutoEq answered ${it.code}")
        it.body!!.string()
    }

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
