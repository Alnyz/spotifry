package com.spotifry.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One desktop release in the change log: its version, GitHub's ISO date text (may be empty) and its notes as plain text. */
data class DesktopLogEntry(val version: String, val date: String, val notes: String)

/**
 * The desktop releases in GitHub's list, in its order: tagged "desktop-…", or carrying the desktop files (1.0.0 was one
 * release for the phone and the desktop). The phone's own releases, drafts and pre-releases are left out.
 */
fun parseDesktopLog(json: String): List<DesktopLogEntry> {
    val list = JSONArray(json)
    return (0 until list.length()).map(list::getJSONObject)
        .filter { (it.optString("tag_name").startsWith("desktop-") || it.hasDesktopFiles()) && !it.optBoolean("draft") && !it.optBoolean("prerelease") }
        .map { DesktopLogEntry(it.optString("tag_name").removePrefix("desktop-").removePrefix("v"), it.optString("published_at"), plainNotes(it.optString("body"))) }
}

private fun JSONObject.hasDesktopFiles(): Boolean {
    val assets = optJSONArray("assets") ?: return false
    return (0 until assets.length()).any { assets.getJSONObject(it).optString("name").let { n -> n.endsWith(".AppImage") || n.endsWith(".msi") } }
}

/** Release notes as people read them: no #, **, backticks or link addresses; list dashes become bullets. */
fun plainNotes(markdown: String): String =
    markdown.lines().map { raw ->
        raw.trim().replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^[-*]\\s+"), "• ")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace("**", "").replace("__", "").replace("`", "")
    }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

/** "14 Nov 2026" from "2026-11-14T09:12:00Z"; null when there is no date. */
fun logDate(iso: String): String? = runCatching {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
}.getOrNull()

/** The change log: asks GitHub when the page opens and keeps the last answer in [file], so it still shows offline. */
class ChangeLog(private val client: OkHttpClient, private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val entries = MutableStateFlow(read())
    val loading = MutableStateFlow(false)
    /** True when the last ask failed; [entries] then holds the saved list, if there is one. */
    val failed = MutableStateFlow(false)

    fun load() {
        if (loading.value) return
        loading.value = true
        scope.launch {
            runCatching {
                val body = client.newCall(Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Spotifry/" + com.spotifry.music.data.AppVersion.name + " (+https://github.com/Alnyz/spotifry)").build()).execute().use {
                    check(it.isSuccessful) { "GitHub said ${it.code}" }; it.body!!.string()
                }
                val list = parseDesktopLog(body)
                entries.value = list; failed.value = false
                runCatching { file.writeText(JSONArray(list.map { JSONObject().put("v", it.version).put("d", it.date).put("n", it.notes) }).toString()) }
            }.onFailure { failed.value = true }
            loading.value = false
        }
    }

    private fun read(): List<DesktopLogEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> DesktopLogEntry(o.getString("v"), o.optString("d"), o.optString("n")) } }
    }.getOrDefault(emptyList())
}
