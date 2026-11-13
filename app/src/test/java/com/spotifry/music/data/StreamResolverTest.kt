package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FakeMusicSource : MusicSource {
    var resolveCalls = 0
    var searchCalls = 0
    var failResolve = false
    var noAudio = false
    var expiresAtMs = 10_000_000L
    var searchResult: List<Song> = emptyList()
    var streamUrl: String? = null
    var failSearch = false
    override suspend fun search(query: String): List<Song> { searchCalls++; if (failSearch) error("offline"); return searchResult }
    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
        resolveCalls++
        if (failResolve) error("boom")
        if (noAudio) throw com.spotifry.music.data.source.NoAudioException("no audio stream for ${song.id}")
        return ResolvedStream(streamUrl ?: "https://x/$resolveCalls", "audio/mp4", 128, expiresAtMs)
    }
    override suspend fun related(song: Song): List<Song> = emptyList()
    var pagesResult: List<PageLink> = emptyList()
    override suspend fun searchPages(query: String, type: SearchType): List<PageLink> = pagesResult
}

val testSong = Song("abc", "Title", "Artist", 200, null)

class StreamResolverTest {
    @Test fun reusesFreshStream() = runTest {
        val src = FakeMusicSource()
        val r = StreamResolver(src) { 1_000L }
        val a = r.get(testSong)
        val b = r.get(testSong)
        assertEquals(a, b)
        assertEquals(1, src.resolveCalls)
    }

    @Test fun aBitrateNotedForAnotherSourceIsNotUsed() = runTest {
        val src = FakeMusicSource()
        val r = StreamResolver(src) { 1_000L }
        r.get(testSong) // 128 kbps, YouTube
        assertEquals(128, r.knownKbps(testSong.id))
        assertEquals(128, r.knownKbps(testSong.id, "youtube"))
        // the lossless copy's bitrate must not label the YouTube file that is playing
        r.put(testSong, ResolvedStream("https://x/flac", "audio/flac", 983, 10_000_000L, fromMonochrome = true))
        assertEquals(null, r.knownKbps(testSong.id, "youtube"))
        assertEquals(983, r.knownKbps(testSong.id, "monochrome"))
    }

    @Test fun reResolvesNearExpiry() = runTest {
        val src = FakeMusicSource()
        var now = 1_000L
        val r = StreamResolver(src) { now }
        val first = r.get(testSong)
        now = src.expiresAtMs - 30_000
        val second = r.get(testSong)
        assertNotEquals(first.url, second.url)
        assertEquals(2, src.resolveCalls)
    }

    @Test fun invalidateForcesResolve() = runTest {
        val src = FakeMusicSource()
        val r = StreamResolver(src) { 1_000L }
        r.get(testSong)
        r.invalidate(testSong.id)
        r.get(testSong)
        assertEquals(2, src.resolveCalls)
    }

    @Test fun prefetchSwallowsErrors() = runTest {
        val src = FakeMusicSource().apply { failResolve = true }
        StreamResolver(src) { 1_000L }.prefetch(testSong)
        assertEquals(1, src.resolveCalls)
    }

    @Test fun aLookupInProgressIsWaitedForNotRepeated() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val src = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun related(song: Song) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
                calls++; gate.await(); return ResolvedStream("https://x/$calls", "audio/mp4", 128, 10_000_000L)
            }
        }
        val r = StreamResolver(src, backgroundScope + kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])) { 1_000L }
        r.warm(listOf(testSong))
        val tapped = async { r.get(testSong) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals("https://x/1", tapped.await().url)
        assertEquals(1, calls)
    }

    @Test fun aFailedLookupIsTriedAgainOnTheNextTap() = runTest {
        val src = FakeMusicSource().apply { failResolve = true }
        val r = StreamResolver(src, backgroundScope + kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])) { 1_000L }
        r.prefetch(testSong)
        src.failResolve = false
        assertEquals("https://x/2", r.get(testSong).url)
    }
}
