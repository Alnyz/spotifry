package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FakeMusicSource : MusicSource {
    var resolveCalls = 0
    var searchCalls = 0
    var failResolve = false
    var expiresAtMs = 10_000_000L
    var searchResult: List<Song> = emptyList()
    var streamUrl: String? = null
    var failSearch = false
    override suspend fun search(query: String): List<Song> { searchCalls++; if (failSearch) error("offline"); return searchResult }
    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
        resolveCalls++
        if (failResolve) error("boom")
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
}
