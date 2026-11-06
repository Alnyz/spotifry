package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ArtistSongsTest {
    private class Source(val fail: Boolean = false) : MusicSource {
        val running = AtomicInteger(); val peak = AtomicInteger(); val pages = AtomicInteger(); val lookups = AtomicInteger()
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("unused")
        override suspend fun related(song: Song) = emptyList<Song>()
        override suspend fun searchPages(query: String, type: SearchType): List<PageLink> {
            lookups.incrementAndGet()
            return listOf(PageLink(SearchType.ARTISTS, "url/$query", query, "", "img/$query"))
        }
        override suspend fun page(link: PageLink): PageContent {
            pages.incrementAndGet()
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                delay(20)
                if (fail) throw java.io.IOException("offline")
                return PageContent(link.name, "", "Artist", null, (1..5).map { Song("${link.name}-$it", "t$it", link.name, 100, null) })
            } finally { running.decrementAndGet() }
        }
    }

    private fun artists(n: Int) = (1..n).map { PickedArtist("A$it", "url/A$it", null) }

    @Test fun neverMoreThanTwoRequestsAtOnceAndOnlyTheFirstSixArtists() = runBlocking {
        val s = Source()
        val got = ArtistSongs(s).load(artists(10))
        assertEquals(6, s.pages.get())
        assertTrue("peak ${s.peak.get()}", s.peak.get() <= 2)
        // three from each of six, one artist after another
        assertEquals(18, got.songs.size)
        assertEquals(listOf("A1-1", "A2-1", "A3-1"), got.songs.take(3).map { it.id })
    }

    @Test fun openingHomeAgainAsksNothingWhileTheAnswersAreFresh() = runBlocking {
        val s = Source(); var t = 0L
        val loader = ArtistSongs(s, now = { t })
        loader.load(artists(3)); loader.load(artists(3))
        assertEquals(3, s.pages.get())
        t += ArtistSongs.KEEP_MS + 1
        loader.load(artists(3))
        assertEquals(6, s.pages.get())
    }

    @Test fun aFailedArtistIsNotAskedAgainRightAway() = runBlocking {
        val s = Source(fail = true); var t = 0L
        val loader = ArtistSongs(s, now = { t })
        assertTrue(loader.load(artists(2)).songs.isEmpty())
        loader.load(artists(2)); loader.load(artists(2))
        assertEquals(2, s.pages.get())
        t += ArtistSongs.RETRY_MS + 1
        loader.load(artists(2))
        assertEquals(4, s.pages.get())
    }

    @Test fun anArtistPickedOfflineGetsItsPageLookedUpOnceAndKept() = runBlocking {
        val s = Source()
        val got = ArtistSongs(s).load(listOf(PickedArtist("Tulus", "", null)))
        assertEquals(PickedArtist("Tulus", "url/Tulus", "img/Tulus"), got.artists.single())
        assertEquals(1, s.lookups.get())
    }
}
