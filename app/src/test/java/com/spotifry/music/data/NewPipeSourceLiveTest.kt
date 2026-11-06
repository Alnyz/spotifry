package com.spotifry.music.data

import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.data.source.OkHttpDownloader
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.ui.home.MOODS
import com.spotifry.music.ui.home.moodLists
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/** Hits the real YouTube. Run with: ./gradlew testDebugUnitTest -Plive=1 --tests '*NewPipeSourceLiveTest' */
class NewPipeSourceLiveTest {
    @Test fun searchResolveRelatedWork() = runBlocking {
        assumeTrue(System.getProperty("live").orEmpty().isNotEmpty())
        NewPipe.init(OkHttpDownloader(OkHttpClient()), Localization("en", "US"))
        val source = NewPipeSource()
        val songs = source.search("coldplay yellow")
        assertTrue(songs.isNotEmpty())
        val stream = source.resolve(songs.first())
        assertTrue(stream.url.startsWith("https://"))
        assertTrue(source.related(songs.first()).isNotEmpty())
        assertTrue(source.searchVideos("coldplay yellow").isNotEmpty())
        val album = source.searchPages("ok computer", SearchType.ALBUMS).first()
        assertTrue(source.page(album).songs.size >= 10)
        val playlist = source.searchPages("radiohead", SearchType.PLAYLISTS).first()
        assertTrue(source.page(playlist).songs.isNotEmpty())
        val artist = source.page(source.searchPages("radiohead", SearchType.ARTISTS).first())
        assertTrue(artist.songs.isNotEmpty() && artist.albums.isNotEmpty())
        println("LIVE album=${source.page(album).let { it.kind + " " + it.name + " " + it.by + " " + it.songs.size }} playlist=${playlist.name}/${playlist.by} artist=${artist.name} songs=${artist.songs.map { it.title }} albums=${artist.albums.map { it.name }}")
    }

    /** Run with the same command and --tests '*NewPipeSourceLiveTest'; prints each tile's lists and how long they took. */
    @Test fun everyMoodFindsListsThatPlay() = runBlocking {
        assumeTrue(System.getProperty("live").orEmpty().isNotEmpty())
        NewPipe.init(OkHttpDownloader(OkHttpClient()), Localization("en", "US"))
        val source = NewPipeSource()
        val few = mutableListOf<String>()
        for (m in MOODS) {
            val start = System.currentTimeMillis()
            val lists = moodLists(source, m)
            val took = System.currentTimeMillis() - start
            println("LIVE ${m.name}: ${lists.size} lists in $took ms: ${lists.take(6).joinToString(" | ") { it.name.take(40) }}")
            if (lists.size < 3) { few += m.name; continue }
            val song = source.page(lists.first()).songs.first()
            assertTrue(m.name, source.resolve(song).url.startsWith("https://"))
        }
        assertTrue("too few lists: $few", few.isEmpty())
    }
}
