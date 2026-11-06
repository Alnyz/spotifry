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

    @Test fun loadingIsOnWhileThePagesAnswerAndOffAfter() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : MusicSource by source { override suspend fun page(link: PageLink): PageContent { gate.await(); return source.page(link) } }
        val s = settings(); s.setArtists(listOf(PickedArtist("Raisa", "url/Raisa", null)))
        val vm = ArtistsHomeViewModel(s, ArtistSongs(slow))
        assertEquals(true, vm.state.value.loading)
        gate.complete(Unit)
        assertEquals(false, vm.state.value.loading)
        assertEquals(1, vm.state.value.songs.size)
    }

    @Test fun pickingArtistsLaterLoadsThem() {
        val s = settings(); val vm = ArtistsHomeViewModel(s, ArtistSongs(source))
        s.setArtists(listOf(PickedArtist("Hindia", "url/Hindia", null)))
        assertEquals(listOf("Hindia"), vm.state.value.artists.map { it.name })
        assertEquals(1, vm.state.value.songs.size)
    }

    @Test fun editingAsksOnlyForAddedArtistsAndDropsRemovedOnes() {
        val s = settings(); s.setArtists(listOf(PickedArtist("Tulus", "url/Tulus", null), PickedArtist("Raisa", "", null)))
        val vm = ArtistsHomeViewModel(s, ArtistSongs(source))
        assertEquals(1, lookups.get()); assertEquals(2, pages.get())
        s.setArtists(listOf(PickedArtist("Raisa", "url/Raisa", null), PickedArtist("Hindia", "url/Hindia", null)))
        // Raisa is cached, Tulus is gone, only Hindia is new
        assertEquals(1, lookups.get()); assertEquals(3, pages.get())
        assertEquals(listOf("Raisa-1", "Hindia-1"), vm.state.value.songs.map { it.id })
        s.setArtists(emptyList())
        assertEquals(ArtistsHome(), vm.state.value)
        // Tulus was dropped from the cache, so picking it again asks again
        s.setArtists(listOf(PickedArtist("Tulus", "url/Tulus", null)))
        assertEquals(4, pages.get())
    }

    @Test fun aLateLookupDoesNotOverwriteAnEditSavedMeanwhile() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : MusicSource by source { override suspend fun searchPages(query: String, type: SearchType): List<PageLink> { gate.await(); return source.searchPages(query, type) } }
        val s = settings(); s.setArtists(listOf(PickedArtist("Tulus", "", null)))
        ArtistsHomeViewModel(s, ArtistSongs(slow))
        val edited = listOf(PickedArtist("Raisa", "url/Raisa", null))
        s.setArtists(edited)
        gate.complete(Unit)
        assertEquals(edited, s.artists.value)
    }

    @Test fun theFirstStateIsAlreadyLoadingWhenArtistsArePicked() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : MusicSource by source { override suspend fun page(link: PageLink): PageContent { gate.await(); return source.page(link) } }
        val s = settings(); s.setArtists(listOf(PickedArtist("Raisa", "url/Raisa", null)))
        val vm = ArtistsHomeViewModel(s, ArtistSongs(slow))
        assertEquals(true, vm.state.value.loading)
        gate.complete(Unit)
        assertEquals(false, vm.state.value.loading)
        // nobody picked: never loading
        assertEquals(false, ArtistsHomeViewModel(settings().also { it.setArtists(emptyList()) }, ArtistSongs(source)).state.value.loading)
    }

    @Test fun whenEveryPageFailsLoadingEndsWithNoSongs() {
        val dead = object : MusicSource by source { override suspend fun page(link: PageLink): PageContent = throw java.io.IOException("offline") }
        val s = settings(); s.setArtists(listOf(PickedArtist("Raisa", "url/Raisa", null)))
        val vm = ArtistsHomeViewModel(s, ArtistSongs(dead))
        assertEquals(ArtistsHome(s.artists.value), vm.state.value)
    }

    @Test fun shownSongsAreNeverSwappedWithinARun() {
        val s = settings(); s.setArtists(listOf(PickedArtist("Raisa", "url/Raisa", null), PickedArtist("Tulus", "url/Tulus", null)))
        val big = object : MusicSource by source {
            override suspend fun page(link: PageLink): PageContent { pages.incrementAndGet(); return PageContent(link.name, "", "Artist", null, (1..20).map { Song("${link.name}-$it", "t", link.name, 100, null) }) }
        }
        var t = 0L
        val vm = ArtistsHomeViewModel(s, ArtistSongs(big, now = { t }))
        val shown = vm.state.value.songs
        assertEquals(6, shown.size)
        repeat(3) { t += 24L * 60 * 60 * 1000; vm.refresh() }
        assertEquals(shown, vm.state.value.songs)
        assertEquals(2, pages.get())
    }
}
