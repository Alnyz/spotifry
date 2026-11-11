package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.toSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class YouTubeImportTest {
    private val pl = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    @Test fun readsEveryKindOfLink() {
        val plain = "https://www.youtube.com/playlist?list=$pl"
        assertEquals(plain, YouTubeImport.playlistUrl("https://www.youtube.com/playlist?list=$pl"))
        assertEquals(plain, YouTubeImport.playlistUrl("https://music.youtube.com/playlist?list=$pl&si=abc"))
        assertEquals(plain, YouTubeImport.playlistUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=$pl&index=3"))
        assertEquals(plain, YouTubeImport.playlistUrl("https://youtu.be/dQw4w9WgXcQ?list=$pl"))
        assertEquals(plain, YouTubeImport.playlistUrl("  $pl "))
        assertEquals(plain, YouTubeImport.playlistUrl("Check this out https://youtube.com/playlist?list=$pl"))
        val album = "OLAK5uy_kXyPUm2nREgfqLnqX9VNq3xSdkFV4zA2c"
        assertEquals("https://www.youtube.com/playlist?list=$album", YouTubeImport.playlistUrl(album))
    }

    @Test fun mixKeepsItsSong() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ",
            YouTubeImport.playlistUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ",
            YouTubeImport.playlistUrl("https://youtu.be/dQw4w9WgXcQ?list=RDAMVMdQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ", YouTubeImport.playlistUrl("RDdQw4w9WgXcQ"))
    }

    @Test fun signInListsAreStillRecognised() {
        // recognised here so the import can say it needs sign-in, not "that isn't a link"
        assertEquals("https://www.youtube.com/playlist?list=LL", YouTubeImport.playlistUrl("https://www.youtube.com/playlist?list=LL"))
        assertEquals("https://www.youtube.com/playlist?list=LM", YouTubeImport.playlistUrl("https://music.youtube.com/playlist?list=LM"))
    }

    @Test fun rejectsWhatIsNotAPlaylist() {
        assertNull(YouTubeImport.playlistUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeImport.playlistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(YouTubeImport.playlistUrl("https://example.com/playlist?list=$pl"))
        assertNull(YouTubeImport.playlistUrl("PL"))
        assertNull(YouTubeImport.playlistUrl("hello"))
        assertNull(YouTubeImport.playlistUrl(""))
    }

    private fun item(id: String, sec: Long = 200) = StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", "Song $id", StreamType.VIDEO_STREAM).apply {
        duration = sec; uploaderName = "Artist"
        thumbnails = listOf(Image("small", 90, 120, Image.ResolutionLevel.LOW), Image("big", 360, 480, Image.ResolutionLevel.HIGH))
    }
    private fun page(ids: List<String>, next: String?) = InfoItemsPage(ids.map { item(it) }, next?.let(::Page), emptyList())
    private fun ids(n: IntRange) = n.map { "v%010d".format(it) }

    @Test fun pagesThroughToTheEnd() {
        val pages = mapOf("2" to page(ids(101..200), "3"), "3" to page(ids(201..250), null))
        val asked = mutableListOf<String>()
        val (items, capped) = YouTubeImport.collect(page(ids(1..100), "2"), { asked += it.url; pages.getValue(it.url) }, Int.MAX_VALUE)
        assertEquals(250, items.size)
        assertEquals(listOf("2", "3"), asked)
        assertFalse(capped)
    }

    @Test fun endlessMixStopsAtTheLimit() {
        var calls = 0
        val (items, capped) = YouTubeImport.collect(page(ids(1..100), "x"), { calls++; page(ids(1..100), "x") }, YouTubeImport.MIX_LIMIT)
        assertEquals(500, items.size)
        assertEquals(4, calls)
        assertTrue(capped)
    }

    @Test fun anEmptyPageEndsIt() {
        val (items, capped) = YouTubeImport.collect(page(ids(1..3), "x"), { page(emptyList(), "x") }, Int.MAX_VALUE)
        assertEquals(3, items.size)
        assertFalse(capped)
    }

    /** Hits the real YouTube. Run with: ./gradlew testDebugUnitTest -Plive=1 --tests '*YouTubeImportTest' */
    @Test fun live() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("live").orEmpty().isNotEmpty())
        org.schabi.newpipe.extractor.NewPipe.init(com.spotifry.music.data.source.OkHttpDownloader(okhttp3.OkHttpClient()), org.schabi.newpipe.extractor.localization.Localization("en", "US"))
        val found = com.spotifry.music.data.source.NewPipeSource().searchPages("radiohead", com.spotifry.music.data.source.SearchType.PLAYLISTS).first()
        val list = YouTubeImport.fetch(YouTubeImport.playlistUrl(found.url)!!)
        println("LIVE playlist ${found.url} -> ${list.name}: ${list.songs.size} songs, ${list.skipped} skipped")
        assertTrue(list.songs.isNotEmpty())
        val mix = YouTubeImport.fetch(YouTubeImport.playlistUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ")!!)
        println("LIVE mix ${mix.name}: ${mix.songs.size} songs, ${mix.skipped} skipped, capped=${mix.capped}")
        assertTrue(mix.songs.isNotEmpty())
        val liked = runCatching { YouTubeImport.fetch("https://www.youtube.com/playlist?list=LL") }.exceptionOrNull()
        assertTrue(liked is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException)
    }

    @Test fun mapsLikeSearchedSongsAndDropsLiveAndLong() {
        val songs = listOf(item("dQw4w9WgXcQ"), item("aaaaaaaaaaa", -1), item("bbbbbbbbbbb", 1500)).toSongs()
        assertEquals(listOf(Song("dQw4w9WgXcQ", "Song dQw4w9WgXcQ", "Artist", 200, "big")), songs)
    }
}
