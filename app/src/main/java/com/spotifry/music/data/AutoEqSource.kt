// The week-long saved list, the fetch flow and the address form are adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.

package com.spotifry.music.data

import com.spotifry.music.player.AutoEqBadFile
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
 * list is kept on disk and fetched again after a week, so search works offline once it has been loaded. A list with
 * fewer than [minEntries] results counts as damaged: the real one has thousands.
 */
class AutoEqSource(private val client: OkHttpClient, private val dir: File, private val base: String = BASE, private val minEntries: Int = 1000) {
    /** All results. Throws [IOException] when there is no connection and no usable saved list. */
    suspend fun index(): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        val file = File(dir, "INDEX.md")
        val saved = runCatching { parseAutoEqIndex(file.readText()) }.getOrNull()?.takeIf { it.size >= minEntries }
        if (saved != null && System.currentTimeMillis() - file.lastModified() < WEEK_MS) return@withContext saved
        try {
            val text = get(base.toHttpUrl().newBuilder().addPathSegment("INDEX.md").build())
            val fresh = parseAutoEqIndex(text).takeIf { it.size >= minEntries } ?: throw IOException("The headphone list came back damaged")
            // written beside the file and moved over it, so a failure half way can't leave a broken list in its place
            dir.mkdirs()
            File(dir, "INDEX.md.part").apply { writeText(text); renameTo(file) }
            fresh
        } catch (e: IOException) { saved ?: throw e }
    }

    /** The measurement of one result. Throws [AutoEqBadFile] when it isn't there or can't be read, [IOException] for the rest. */
    suspend fun curve(entry: AutoEqEntry): AutoEqCurve = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply {
            entry.path.split('/').forEach { addPathSegment(it) }
            addPathSegment(entry.path.substringAfterLast('/') + ".csv")
        }.build()
        parseAutoEqCsv(get(url))
    }

    private fun get(url: okhttp3.HttpUrl): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        if (it.code == 404) throw AutoEqBadFile("AutoEq has no file at $url")
        if (!it.isSuccessful) throw IOException("AutoEq answered ${it.code}")
        // a reader that stops at the cap, so an endless answer can't fill the phone's memory
        val source = it.body!!.source()
        source.request(MAX_BYTES + 1)
        if (source.buffer.size > MAX_BYTES) throw AutoEqBadFile("AutoEq answered with more than ${MAX_BYTES / 1_000_000} MB")
        source.readUtf8()
    }

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        /** The list is under 1 MB and a measurement under 100 KB. */
        const val MAX_BYTES = 4L * 1_000_000
    }
}
