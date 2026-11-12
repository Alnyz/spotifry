package com.spotifry.desktop

import com.spotifry.music.data.Prefs
import com.spotifry.music.data.isNewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** A desktop release on GitHub: its version and the page to download it from. */
data class DesktopRelease(val version: String, val page: String)

/**
 * The newest desktop release in GitHub's list of releases. The phone's releases share the repository, so a release
 * counts only when it carries an AppImage, and its version is read from the AppImage's name
 * (Spotifry-<version>-x86_64.AppImage). Drafts and pre-releases are skipped.
 */
fun newestDesktopRelease(json: String): DesktopRelease? {
    val list = JSONArray(json)
    return (0 until list.length()).map(list::getJSONObject).filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }.mapNotNull { r ->
        val assets = r.optJSONArray("assets") ?: return@mapNotNull null
        val name = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }.firstOrNull { it.endsWith(".AppImage") } ?: return@mapNotNull null
        val version = Regex("^Spotifry-(.+)-x86_64\\.AppImage$").find(name)?.groupValues?.get(1) ?: return@mapNotNull null
        DesktopRelease(version, r.optString("html_url"))
    }.reduceOrNull { a, b -> if (isNewer(b.version, a.version)) b else a }
}

/** The version this build is, without the "desktop-" in front. */
val desktopVersion get() = APP_VERSION.removePrefix("desktop-")

/**
 * Asks GitHub, at most once a day, whether a newer desktop Spotifry is out. Updating is opening the release page in the
 * browser and downloading the new AppImage there.
 * ponytail: no download in the app; a self-replacing AppImage (AppImageUpdate, zsync) when the page gets tiresome.
 */
class Updates(private val client: OkHttpClient, private val prefs: Prefs, private val now: () -> Long = System::currentTimeMillis) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A release newer than this build, or null. */
    val available = MutableStateFlow<DesktopRelease?>(null)
    val checking = MutableStateFlow(false)
    /** When GitHub last answered, 0 if never. */
    val lastChecked = MutableStateFlow(prefs.getLong(LAST_CHECK, 0))

    fun checkDaily() { if (now() - lastChecked.value > DAY_MS) check() }

    fun check() {
        if (checking.value) return
        checking.value = true
        scope.launch {
            // offline or GitHub busy: the old answer stands, and the next start asks again
            runCatching {
                val body = client.newCall(Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()).execute().use {
                    check(it.isSuccessful) { "GitHub said ${it.code}" }; it.body!!.string()
                }
                available.value = newestDesktopRelease(body)?.takeIf { isNewer(it.version, desktopVersion) }
                lastChecked.value = now(); prefs.edit().putLong(LAST_CHECK, lastChecked.value).apply()
            }
            checking.value = false
        }
    }

    /** Opens the release page in the browser. */
    fun open(release: DesktopRelease) {
        runCatching { ProcessBuilder("xdg-open", release.page).start() }.onFailure { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(release.page)) } }
    }

    private companion object {
        const val RELEASES = "https://api.github.com/repos/Alnyz/spotifry/releases?per_page=30"
        const val LAST_CHECK = "desktop_update_checked"
        const val DAY_MS = 86_400_000L
    }
}
