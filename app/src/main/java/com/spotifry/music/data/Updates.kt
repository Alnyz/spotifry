package com.spotifry.music.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long) {
    /** The first line of the release notes, for the card on Home. */
    val headline: String get() = notes.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val doneBytes: Long) : UpdateState
    data class Failed(val release: Release) : UpdateState
    /** Downloaded; Android's own install screen is (or was) showing. Tapping Update shows it again. */
    data class Ready(val release: Release, val file: File) : UpdateState
}

/** "0.10.0" is newer than "0.9.2"; a leading "v" is ignored. */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Reads GitHub's answer for the latest release; null if it has no APK attached. */
fun parseRelease(json: String): Release? {
    val o = JSONObject(json)
    val assets = o.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.optString("name").endsWith(".apk")) {
            return Release(o.optString("tag_name").removePrefix("v"), o.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
        }
    }
    return null
}

/**
 * Asks GitHub whether a newer Spotifry is out, downloads it and hands it to Android's installer. Android then checks
 * the new file is signed with the same key, so only a real Spotifry release can update the app.
 */
class Updates(
    private val context: Context,
    private val client: OkHttpClient,
    private val prefs: SharedPreferences,
    private val currentVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _later = MutableStateFlow(prefs.getString(LATER, null))
    /** The version "Later" was tapped for; Home hides the card for it, Settings still offers it. */
    val laterFor: StateFlow<String?> = _later.asStateFlow()
    private val _lastChecked = MutableStateFlow(prefs.getLong(LAST_CHECK, 0))
    /** When GitHub last answered, 0 if never. */
    val lastChecked: StateFlow<Long> = _lastChecked.asStateFlow()
    private var download: Job? = null

    /** On app start: asks GitHub at most once a day. */
    fun checkDaily() { if (now() - _lastChecked.value > DAY_MS) check() }

    fun check() {
        if (_checking.value || _state.value is UpdateState.Downloading) return
        _checking.value = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            _checking.value = false
            // offline or GitHub busy: try again next time, the old answer stands
            if (result.isFailure) return@launch
            _lastChecked.value = now(); prefs.edit().putLong(LAST_CHECK, _lastChecked.value).apply()
            val release = result.getOrNull()
            val current = _state.value
            _state.value = when {
                release == null || !isNewer(release.version, currentVersion) -> UpdateState.None
                current is UpdateState.Ready && current.release.version == release.version -> current
                current is UpdateState.Downloading -> current
                else -> UpdateState.Available(release)
            }
        }
    }

    fun later(release: Release) { _later.value = release.version; prefs.edit().putString(LATER, release.version).apply() }

    fun update() {
        when (val s = _state.value) {
            is UpdateState.Ready -> install(s.file)
            is UpdateState.Available -> start(s.release)
            is UpdateState.Failed -> start(s.release)
            else -> Unit
        }
    }

    private fun start(r: Release) {
        download?.cancel()
        _state.value = UpdateState.Downloading(r, 0)
        download = scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { fetchApk(r) } }.getOrNull()
            if (file == null) { _state.value = UpdateState.Failed(r); return@launch }
            _state.value = UpdateState.Ready(r, file)
            install(file)
        }
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").header("User-Agent", "Spotifry/$currentVersion").build()
        return client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "GitHub answered ${r.code}" }
            parseRelease(r.body!!.string())
        }
    }

    private fun fetchApk(r: Release): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "spotifry-${r.version}.apk")
        client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "Spotifry/$currentVersion").build()).execute().use { resp ->
            check(resp.isSuccessful) { "download failed: ${resp.code}" }
            val body = resp.body!!
            file.outputStream().use { out ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var done = 0L; var shown = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - shown > 256 * 1024) { shown = done; _state.value = UpdateState.Downloading(r, done) }
                }
            }
        }
        return file
    }

    /** Android's installer; the first time Android asks to allow installs from Spotifry, then the user taps Update again. */
    private fun install(file: File) {
        val pm = context.packageManager
        val intent = if (!pm.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/Alnyz/spotifry/releases/latest"
        const val LAST_CHECK = "update_last_check"
        const val LATER = "update_later"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
