package com.spotifry.music.data

import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Saved : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/** Saves songs as files on the phone. A song counts as saved only after the whole file is on disk. */
class DownloadRepository(
    filesDir: File,
    client: OkHttpClient,
    private val resolver: StreamResolver,
    private val songs: SongDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val lowQuality: () -> Boolean = { false },
    /** How often a cut-off download picks up again before the save counts as failed, and the pause (times the try number) before each. */
    private val retries: Int = 3,
    private val retryPauseMs: Long = 1_500,
) {
    // a weak connection can go quiet for a while: wait longer than the default 10 s before giving up on one read
    private val client = client.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private val songsDir = File(filesDir, "songs").apply { mkdirs() }
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val _pending = MutableStateFlow<List<Song>>(emptyList())

    /** Songs whose save was just pressed and whose file is still on its way. The Saved list shows these at once. */
    val pending: StateFlow<List<Song>> = _pending

    /** A save that failed, so the screen can say so. */
    val failures = MutableSharedFlow<Song>(extraBufferCapacity = 4)
    // downloads belong to the app, not to the screen that started them, so leaving a screen does not kill them
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { songsDir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() } }

    fun state(songId: String): StateFlow<DownloadState> = flowFor(songId)
    private fun flowFor(id: String) = states.getOrPut(id) { MutableStateFlow(DownloadState.Idle) }

    suspend fun save(song: Song) {
        if (song.isLocal) return // already a file on the phone
        if (!active.add(song.id)) return // already downloading: a second tap must not start a second writer
        cancelled.remove(song.id)
        // shown as saved right away; the file arrives in the background
        flowFor(song.id).value = DownloadState.Downloading(0f)
        _pending.update { it + song }
        try { scope.async { download(song) }.await() } finally {
            active.remove(song.id)
            _pending.update { list -> list.filter { it.id != song.id } }
        }
    }

    private suspend fun download(song: Song) {
        val flow = flowFor(song.id)
        val part = File(songsDir, "${song.id}.part")
        try {
            songs.upsert(song.toEntity())
            var stream = resolver.get(song, lowQuality(), patient = true)
            var written = 0L
            var tries = 0
            while (true) {
                try {
                    var response = open(stream.url, written)
                    if (response.code == 403) { // link expired or refused: resolve a fresh one once
                        response.close()
                        resolver.invalidate(song.id)
                        stream = resolver.get(song, lowQuality(), patient = true)
                        response = open(stream.url, written)
                    }
                    response.use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("empty response")
                        // 206 continues the part file; a plain 200 means the server ignored the range and sends it all again
                        val resumed = written > 0 && r.code == 206
                        if (!resumed) written = 0
                        val total = body.contentLength().let { if (it > 0 && resumed) written + it else it }
                        body.byteStream().use { input ->
                            java.io.FileOutputStream(part, resumed).use { out ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    if (total > 0) flow.value = DownloadState.Downloading((written.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                        // ponytail: a response with no Content-Length cannot be length-checked; YouTube audio always sends one
                        if (total > 0 && written != total) throw IOException("incomplete download")
                    }
                    break
                } catch (e: IOException) {
                    // a dropped connection keeps what is already on disk and asks for the rest
                    if (++tries > retries) throw e
                    delay(retryPauseMs * tries)
                }
            }
            val final = File(songsDir, "${song.id}.${when { stream.mimeType.contains("webm") -> "webm"; stream.mimeType.contains("flac") -> "flac"; else -> "m4a" }}")
            if (!part.renameTo(final)) throw IOException("could not finish file")
            if (song.id in cancelled) { // the person removed it while it was downloading
                final.delete()
                flow.value = DownloadState.Idle
                return
            }
            songs.markSaved(song.id, final.absolutePath, downloadThumbnail(song)?.absolutePath, now())
            flow.value = DownloadState.Saved
        } catch (e: CancellationException) {
            part.delete()
            flow.value = DownloadState.Failed("cancelled")
            throw e
        } catch (e: Exception) {
            part.delete()
            flow.value = DownloadState.Failed(e.message ?: "download failed")
            failures.tryEmit(song)
        }
    }

    private fun open(url: String, from: Long): okhttp3.Response =
        client.newCall(Request.Builder().url(withRange(url, from)).apply { if (from > 0) header("Range", "bytes=$from-") }.build()).execute()

    suspend fun remove(songId: String) = withContext(Dispatchers.IO) {
        if (songId in active) cancelled.add(songId)
        songs.get(songId)?.let { row ->
            row.filePath?.let { File(it).delete() }
            row.thumbPath?.let { File(it).delete() }
        }
        songs.clearSaved(songId)
        flowFor(songId).value = DownloadState.Idle
    }

    /**
     * YouTube serves a plain request at about 33 KB/s but a request with a range at full speed
     * (measured: 4 MB in 118 s versus 0.3 s). NewPipe does the same.
     */
    private fun withRange(url: String, from: Long = 0): String = when {
        from > 0 -> url.toHttpUrl().newBuilder().setQueryParameter("range", "$from-").build().toString() // YouTube wants it in the link, others in the header
        url.contains("range=") -> url
        else -> url + (if (url.contains('?')) "&" else "?") + "range=0-"
    }

    private fun downloadThumbnail(song: Song): File? {
        val url = song.thumbnailUrl ?: return null
        return fetchThumbnail(song, com.spotifry.music.data.source.largeArt(url)) ?: fetchThumbnail(song, url)
    }

    private fun fetchThumbnail(song: Song, url: String): File? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            File(songsDir, "${song.id}.jpg").also { f -> r.body?.byteStream()?.use { i -> f.outputStream().use { i.copyTo(it) } } }
        }
    } catch (_: Exception) { null } // the cover is optional, the song is still saved
}
