package com.spotifry.music.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.ArtistSongs
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.Settings
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtistsHomeViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val lookups = AtomicInteger(); private val pages = AtomicInteger()
    private val source = object : MusicSource {
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("unused")
        override suspend fun related(song: Song) = emptyList<Song>()
        override suspend fun searchPages(query: String, type: SearchType): List<PageLink> { lookups.incrementAndGet(); return listOf(PageLink(type, "url/$query", query, "", null)) }
        override suspend fun page(link: PageLink): PageContent { pages.incrementAndGet(); return PageContent(link.name, "", "Artist", null, listOf(Song("${link.name}-1", "t", link.name, 100, null))) }
    }
    private fun settings() = Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("ahvm", 0))

    @Test fun openingHomeManyTimesAsksOnceAndPicksOfflineGetTheirPageSaved() {
        val s = settings(); s.setArtists(listOf(PickedArtist("Tulus", "", null), PickedArtist("Raisa", "url/Raisa", null)))
        val vm = ArtistsHomeViewModel(s, ArtistSongs(source))
        repeat(5) { vm.refresh() }
        assertEquals(1, lookups.get()); assertEquals(2, pages.get())
        assertEquals(listOf("Tulus-1", "Raisa-1"), vm.state.value.songs.map { it.id })
        // the page found for Tulus is kept for next time
        assertEquals("url/Tulus", s.artists.value.first().url)
    }

    @Test fun noArtistsMeansNoRequestsAndNoRows() {
        val vm = ArtistsHomeViewModel(settings(), ArtistSongs(source))
        vm.refresh()
        assertEquals(0, lookups.get() + pages.get())
        assertEquals(ArtistsHome(), vm.state.value)
    }

    @Test fun pickingArtistsLaterLoadsThem() {
        val s = settings(); val vm = ArtistsHomeViewModel(s, ArtistSongs(source))
        s.setArtists(listOf(PickedArtist("Hindia", "url/Hindia", null)))
        assertEquals(listOf("Hindia"), vm.state.value.artists.map { it.name })
        assertEquals(1, vm.state.value.songs.size)
    }
}
