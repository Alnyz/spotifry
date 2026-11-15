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
import java.io.File

/** A desktop release on GitHub: its version, its page, and this system's file ([file] is its download address, [size] its bytes, [sha256] GitHub's checksum of it, when GitHub gives one). */
data class DesktopRelease(val version: String, val page: String, val file: String = "", val size: Long = 0, val sha256: String? = null)

/**
 * The newest desktop release in GitHub's list of releases for this system. The phone's releases share the repository, so a
 * release counts only when it carries this system's file, and its version is read from the file's name:
 * Spotifry-<version>-x86_64.AppImage on Linux, Spotifry-<version>-windows-x64.msi on Windows. Drafts and pre-releases are skipped.
 */
fun newestDesktopRelease(json: String, windows: Boolean = Platform.isWindows): DesktopRelease? {
    val file = if (windows) Regex("^Spotifry-(.+)-windows-x64\\.msi$") else Regex("^Spotifry-(.+)-x86_64\\.AppImage$")
    val list = JSONArray(json)
    return (0 until list.length()).map(list::getJSONObject).filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }.mapNotNull { r ->
        val assets = r.optJSONArray("assets") ?: return@mapNotNull null
        val asset = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { file.matches(it.optString("name")) } ?: return@mapNotNull null
        DesktopRelease(file.find(asset.optString("name"))!!.groupValues[1], r.optString("html_url"), asset.optString("browser_download_url"), asset.optLong("size"),
            asset.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 })
    }.reduceOrNull { a, b -> if (isNewer(b.version, a.version)) b else a }
}

/** The version this build is, without the "desktop-" in front. */
val desktopVersion get() = APP_VERSION.removePrefix("desktop-")

/**
 * Asks GitHub, at most once a day, whether a newer desktop Spotifry is out, and installs it from inside the app: on Linux
 * the new AppImage is downloaded next to the running one and moved over it; on Windows the new .msi is downloaded and run.
 * Where that can't work (the app doesn't run from an AppImage, the folder can't be written, the download fails) the
 * release page opens in the browser as before.
 */
class Updates(private val client: OkHttpClient, private val prefs: Prefs, private val now: () -> Long = System::currentTimeMillis, private val files: String = ASSETS) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A release newer than this build, or null. */
    val available = MutableStateFlow<DesktopRelease?>(null)
    val checking = MutableStateFlow(false)
    /** When GitHub last answered, 0 if never. */
    val lastChecked = MutableStateFlow(prefs.getLong(LAST_CHECK, 0))

    /** What the Update button is doing, null when it isn't. */
    val progress = MutableStateFlow<UpdateProgress?>(null)
    private var after: List<String> = emptyList()

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

    /** Downloads [release] and puts it in place; [progress] says how far it got. [finish] then starts the new version. */
    fun install(release: DesktopRelease, running: String? = System.getenv("APPIMAGE")) {
        if (progress.value?.step == UpdateStep.Working) return
        val appImage = running?.let(::File)?.takeIf { it.isFile }
        if (!release.file.startsWith(files) || (!Platform.isWindows && appImage == null)) return open(release)
        progress.value = UpdateProgress("Downloading…", UpdateStep.Working)
        scope.launch {
            val ok = runCatching {
                val target = if (Platform.isWindows) File(System.getProperty("java.io.tmpdir"), "Spotifry-${release.version}-windows-x64.msi") else File(appImage!!.parentFile, appImage.name + ".new")
                download(release, target)
                if (Platform.isWindows) after = listOf("msiexec", "/i", target.path, "/passive")
                else { swapIn(target, appImage!!); after = listOf("sh", "-c", "sleep 1; exec \"\$0\"", appImage.path) }
            }
            progress.value = if (ok.isSuccess) UpdateProgress(if (Platform.isWindows) "Ready to install" else "Updated to ${release.version}", UpdateStep.Done)
            else UpdateProgress("Couldn't update here: ${ok.exceptionOrNull()?.message}", UpdateStep.Failed).also { open(release) }
        }
    }

    /** Starts what [install] left ready: the new version, or the installer. The caller quits the app right after. */
    fun finish() { runCatching { ProcessBuilder(after).start() } }

    private fun download(release: DesktopRelease, target: File) {
        client.newCall(Request.Builder().url(release.file).build()).execute().use { r ->
            check(r.isSuccessful) { "GitHub said ${r.code}" }
            val total = r.body!!.contentLength().takeIf { it > 0 } ?: release.size
            val sum = java.security.MessageDigest.getInstance("SHA-256")
            var done = 0L
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                r.body!!.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); sum.update(buf, 0, n); done += n
                        if (total > 0) progress.value = UpdateProgress("Downloading ${done * 100 / total}%", UpdateStep.Working)
                    }
                }
            }
            val hex = sum.digest().joinToString("") { "%02x".format(it) }
            if (!((release.size == 0L || done == release.size) && (release.sha256 == null || release.sha256 == hex))) { target.delete(); error("not the file GitHub lists") }
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

/** Only files from this project's own releases are downloaded. */
internal const val ASSETS = "https://github.com/Alnyz/spotifry/releases/download/"

enum class UpdateStep { Working, Done, Failed }
data class UpdateProgress(val text: String, val step: UpdateStep)

/** [downloaded] moved over [target] in one step, so the app is never half-replaced; the running copy keeps working, the next start is the new one. */
fun swapIn(downloaded: File, target: File) {
    check(downloaded.setExecutable(true, false)) { "can't make it runnable" }
    java.nio.file.Files.move(downloaded.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}
