package com.spotifry.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Replay storage, like the phone's: each song played is downloaded once, in order, into [dir], and mpv plays it from
 * this computer while it comes in. A song played again plays from here, with no internet. Jumps read the copy too, so
 * they wait only for the download to get there: mpv jumps around a lossless file by reading here and there, and asked of
 * Monochrome's server each read takes over a second. A song left half-way keeps what was downloaded, and its next play
 * goes on from there, as on the phone. The songs played longest ago go first past [limitMb] (0: no limit).
 */
class ReplayStore(private val client: OkHttpClient, private val scope: CoroutineScope, private val dir: File, private val limitMb: () -> Int) : AutoCloseable {
    private class Copy(val n: Int, @Volatile var file: File, val done: File, val url: String) {
        @Volatile var total = -1L
        /** Bytes from the start downloaded in order. */
        @Volatile var have = 0L
        @Volatile var gone = false
        var job: Job? = null
        /** Small parts far ahead, fetched apart for mpv's jumps; done and on their way. */
        val parts = mutableListOf<LongRange>()
        val asked = mutableSetOf<Long>()

        /** Bytes ready to read from [at], 0 if none yet. */
        fun ready(at: Long): Long = if (at < have) have - at else synchronized(parts) { parts.firstOrNull { at in it }?.let { it.last + 1 - at } ?: 0 }
    }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { ex -> runCatching { serve(ex) }; ex.close() }
        executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "local-copy").apply { isDaemon = true } }
        start()
    }
    /** Copies being played: one, or two while one song runs into the next (the newest last). */
    @Volatile private var live = emptyList<Copy>()
    private var count = 0
    /** The whole size of each half-kept song, by its kept name: the part on disk says only how much is there. */
    private val sizes by lazy { FilePrefs(File(dir, "parts.json")) }

    /**
     * Where mpv plays song [id] from [origin] (youtube, saavn, monochrome): its kept file, else [url] copied as it plays.
     * The copies before it stop, except, [alongside], the newest one, still being heard; a song left half-way is not kept.
     */
    @Synchronized fun open(id: String, origin: String, url: String, alongside: Boolean = false): String {
        (if (alongside) live.dropLast(1) else live).forEach(::stop)
        // the source is in the name: a song kept from YouTube is another file than the same song in lossless
        val done = File(dir, "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.$origin")
        if (done.isFile) { done.setLastModified(System.currentTimeMillis()); return done.path }
        dir.mkdirs()
        val c = Copy(++count, File(dir, done.name + ".part"), done, url)
        // half-kept from an earlier play: what is there plays at once, the rest comes from where it stopped
        if (c.file.isFile) { c.have = c.file.length(); c.total = sizes.getLong(done.name, -1L).takeIf { c.have > 0 } ?: -1L }
        c.job = scope.launch(Dispatchers.IO) { download(c, url); keep(c) }
        live = live + c
        return "http://127.0.0.1:${server.address.port}/${c.n}"
    }

    /** A finished copy becomes a kept song, and the songs played longest ago make room for it. */
    private fun keep(c: Copy) {
        if (c.gone || c.total < 0 || c.have < c.total || !c.file.renameTo(c.done)) return
        c.file = c.done // later reads of the playing song find it under its new name
        sizes.edit().remove(c.done.name).apply()
        makeRoom(c.done)
    }

    /** The songs played longest ago go until the rest fit; [newest] stays. */
    private fun makeRoom(newest: File) {
        val mb = limitMb().takeIf { it > 0 } ?: return
        var room = mb * 1024L * 1024 - usedBytes()
        kept().filter { it != newest }.sortedBy { it.lastModified() }.forEach { f ->
            if (room < 0) { room += f.length(); f.delete(); sizes.edit().remove(f.name.removeSuffix(".part")).apply() }
        }
    }

    /** Kept songs, whole or half, but not the one coming in now. */
    private fun kept() = dir.listFiles { f -> f.isFile && f.name != "parts.json" && live.none { c -> c.file == f } }.orEmpty().toList()

    /** Space the kept songs take. */
    fun usedBytes() = kept().sumOf { it.length() }

    /** Deletes every kept song; the one coming in now is kept when it is done. */
    fun clear() = kept().forEach { it.delete(); sizes.edit().remove(it.name.removeSuffix(".part")).apply() }

    private suspend fun download(c: Copy, url: String) {
        var fails = 0
        RandomAccessFile(c.file, "rw").use { out ->
            // one long read is fastest; the server cuts long ones off now and then, so each try goes on from where it got
            while (scope.isActive && !c.gone && (c.total < 0 || c.have < c.total)) {
                try {
                    client.newCall(Request.Builder().url(url).header("Range", "bytes=${c.have}-").build()).execute().use { r ->
                        check(r.code == 206 || r.code == 200) { "answer ${r.code}" }
                        val size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body!!.contentLength()
                        // a different file than the half-kept one (another format this time), or the whole file again: start over
                        if (r.code == 200 || (c.total >= 0 && size != c.total)) { c.have = 0; out.setLength(0) }
                        if (c.total != size) { c.total = size; sizes.edit().putLong(c.done.name, size).apply() }
                        val input = r.body!!.byteStream(); val buf = ByteArray(64 * 1024)
                        out.seek(c.have)
                        while (!c.gone) {
                            val k = input.read(buf); if (k < 0) break
                            synchronized(c) { if (!c.gone) { out.write(buf, 0, k); c.have += k } }
                        }
                    }
                    fails = 0
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (++fails > 8) { c.gone = true; return } // mpv's read then fails and the player moves on, as with any broken song
                    delay(500L * fails)
                }
            }
        }
    }

    private fun serve(ex: HttpExchange) {
        val c = live.firstOrNull { "/${it.n}" == ex.requestURI.path }
        if (c == null) { ex.sendResponseHeaders(404, -1); return }
        if (!waitFor(c) { c.total >= 0 }) { ex.sendResponseHeaders(503, -1); return }
        val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
        val from = range?.getOrNull(0)?.toLongOrNull() ?: 0L
        val to = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(c.total - 1) ?: (c.total - 1)
        if (from >= c.total) { ex.responseHeaders.add("Content-Range", "bytes */${c.total}"); ex.sendResponseHeaders(416, -1); return }
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Content-Type", "audio/flac")
        if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/${c.total}")
        ex.sendResponseHeaders(if (range != null) 206 else 200, to - from + 1)
        RandomAccessFile(c.file, "r").use { f ->
            val buf = ByteArray(64 * 1024); var at = from
            ex.responseBody.use { body ->
                while (at <= to) {
                    // far ahead of the download, as when mpv looks for a spot in a lossless file: fetch that part now
                    if (c.ready(at) == 0L && at > c.have + PART) fetchPart(c, at)
                    if (!waitFor(c) { c.ready(at) > 0 }) return
                    f.seek(at)
                    val k = f.read(buf, 0, minOf(buf.size.toLong(), c.ready(at), to - at + 1).toInt())
                    if (k <= 0) return
                    body.write(buf, 0, k); at += k
                }
            }
        }
    }

    private fun fetchPart(c: Copy, at: Long) {
        val start = at / PART * PART
        if (!synchronized(c.parts) { c.asked.add(start) }) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val end = minOf(start + PART, c.total) - 1
                val bytes = client.newCall(Request.Builder().url(c.url).header("Range", "bytes=$start-$end").build()).execute().use { r ->
                    check(r.code == 206); r.body!!.bytes()
                }
                synchronized(c) { if (!c.gone) RandomAccessFile(c.file, "rw").use { it.seek(start); it.write(bytes) } }
                synchronized(c.parts) { c.parts += start until start + bytes.size }
            }.onFailure { synchronized(c.parts) { c.asked.remove(start) } }
        }
    }

    /** Waits for [ready] while the copy is in use and still coming in; false if it never gets there. */
    private fun waitFor(c: Copy, ready: () -> Boolean): Boolean {
        var last = c.have; var still = 0
        while (!ready()) {
            if (c.gone || c !in live) return false
            Thread.sleep(50)
            if (c.have == last) { if (++still > 600) return false } else { last = c.have; still = 0 } // 30 s with nothing new
        }
        return true
    }

    /** Stops the copies in use; a song not finished is not kept. A finished one becomes kept as it finishes, not here. */
    @Synchronized fun drop() = live.forEach(::stop)

    private fun stop(c: Copy) {
        live = live - c
        if (c.total >= 0 && c.have >= c.total) return // finished: kept as it finishes
        // only what came in order from the start is kept: parts fetched far ahead for a jump are let go
        synchronized(c) { c.gone = true; runCatching { RandomAccessFile(c.file, "rw").use { it.setLength(c.have) } } }
        c.job?.cancel()
        if (c.have == 0L) c.file.delete() else { c.file.setLastModified(System.currentTimeMillis()); makeRoom(c.file) }
    }

    override fun close() { drop(); server.stop(0) }

    private companion object {
        /** Size of a part fetched apart, and how far ahead of the download a read must be to get one. */
        const val PART = 512 * 1024L
    }
}
