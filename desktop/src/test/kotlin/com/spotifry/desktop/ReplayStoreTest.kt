package com.spotifry.desktop

import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A slow server like Monochrome's: a wait before each answer, about 1.5 MB/s, and the first long download cut off. */
class ReplayStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private var data = ByteArray(0)
    private var requests = 0
    private val remote = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).apply {
        createContext("/") { ex ->
            runCatching {
                requests++
                Thread.sleep(300)
                val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, 0) // sent in pieces, so a cut ends the answer at once, as a reset does
                ex.responseBody.use { out ->
                    var at = from.toInt()
                    while (at < data.size) {
                        if (requests == 1 && at > data.size / 3) error("cut off")
                        val k = minOf(32 * 1024, data.size - at); out.write(data, at, k); at += k
                        Thread.sleep(20)
                    }
                }
            }
            ex.close()
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool(); start()
    }
    private val url = "http://127.0.0.1:${remote.address.port}/song"
    private val store = java.nio.file.Files.createTempDirectory("replay").toFile()
    private var limitMb = 0
    private val copy = ReplayStore(client, scope, store) { limitMb }

    @After fun tearDown() { copy.close(); remote.stop(0); store.deleteRecursively() }

    @Test fun theCopyGivesTheSameBytesEvenAfterTheServerCutsOff() {
        data = ByteArray(3_000_000) { (it * 31 % 251).toByte() }
        val local = copy.open("s1", "monochrome", url)
        val part = client.newCall(Request.Builder().url(local).header("Range", "bytes=2500000-2500099").build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(data.copyOfRange(2_500_000, 2_500_100), part)
        val all = client.newCall(Request.Builder().url(local).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(data, all)
        // kept once complete: played again it comes from the computer, not the server
        runBlocking { withTimeout(10_000) { while (!java.io.File(store, "s1.monochrome").isFile) kotlinx.coroutines.delay(50) } }
        val asked = requests
        assertArrayEquals(data, java.io.File(copy.open("s1", "monochrome", url)).readBytes())
        assertTrue(requests == asked)
    }

    @Test fun aSongLeftHalfWayKeepsItsStartAndGoesOnFromThereNextTime() {
        data = ByteArray(3_000_000) { (it * 7 % 253).toByte() }
        copy.open("h", "youtube", url)
        runBlocking { withTimeout(10_000) { while (java.io.File(store, "h.youtube.part").length() < 500_000) kotlinx.coroutines.delay(20) } }
        copy.drop() // skipped
        val kept = java.io.File(store, "h.youtube.part").length()
        assertTrue("part of it kept: $kept", kept in 500_000 until 3_000_000)
        assertArrayEquals(data.copyOfRange(0, kept.toInt()), java.io.File(store, "h.youtube.part").readBytes())
        val asked = requests
        val local = copy.open("h", "youtube", url)
        val all = client.newCall(Request.Builder().url(local).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(data, all)
        assertTrue("went on from where it stopped, in one go", requests == asked + 1)
        runBlocking { withTimeout(10_000) { while (!java.io.File(store, "h.youtube").isFile) kotlinx.coroutines.delay(50) } }
    }

    @Test fun theSongsPlayedLongestAgoMakeRoom() {
        data = ByteArray(1_000_000) { 7 }
        limitMb = 2
        for ((i, id) in listOf("a", "b", "c").withIndex()) {
            copy.open(id, "youtube", url)
            runBlocking { withTimeout(15_000) { while (!java.io.File(store, "$id.youtube").isFile) kotlinx.coroutines.delay(50) } }
            java.io.File(store, "$id.youtube").setLastModified(1_000_000L * (i + 1))
        }
        copy.open("b", "youtube", url) // b played again: now the newest
        copy.open("d", "youtube", url)
        runBlocking { withTimeout(15_000) { while (!java.io.File(store, "d.youtube").isFile) kotlinx.coroutines.delay(50) } }
        Thread.sleep(200)
        assertTrue("a, played longest ago, went first", !java.io.File(store, "a.youtube").exists())
        assertTrue(java.io.File(store, "b.youtube").exists())
        assertTrue(copy.usedBytes() <= 2 * 1024 * 1024)
        copy.clear(); assertEquals(0L, copy.usedBytes())
    }

    @Test fun aSongPlaysFromTheCopyJumpsAndEnds() {
        assumeTrue("mpv not installed", runCatching { ProcessBuilder("mpv", "--version").start().waitFor() == 0 }.getOrDefault(false))
        data = silence(seconds = 8)
        val source = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun related(song: Song) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean) = ResolvedStream(url, "audio/wav", 0, Long.MAX_VALUE, fromMonochrome = true)
        }
        val player = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"), playFrom = { song, st -> copy.open(song.id, st.origin, st.url) })
        try {
            player.playList(listOf(Song("m", "M", "A", 8, null)), 0)
            runBlocking { withTimeout(15_000) { player.state.first { it.isPlaying } } }
            player.seekTo(6_000)
            runBlocking { withTimeout(15_000) { player.position.first { it > 6_200 } } }
            runBlocking { withTimeout(15_000) { player.state.first { it.ended } } }
            assertTrue("the server is asked a few times, not once per read: $requests", requests <= 4)
        } finally { player.close() }
    }

    private fun silence(seconds: Int): ByteArray {
        val rate = 44100; val body = ByteArray(rate * seconds * 4)
        return ByteBuffer.allocate(44 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + body.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 4); putShort(4); putShort(16); put("data".toByteArray()); putInt(body.size); put(body)
        }.array()
    }
}
