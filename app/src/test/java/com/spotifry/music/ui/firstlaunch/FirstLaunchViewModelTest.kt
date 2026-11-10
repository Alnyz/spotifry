package com.spotifry.music.ui.firstlaunch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.STARTER_ARTISTS
import com.spotifry.music.data.Settings
import com.spotifry.music.data.source.MusicSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstLaunchViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Source(val offline: Boolean = false) : MusicSource {
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("unused")
        override suspend fun related(song: Song) = emptyList<Song>()
        override suspend fun searchPages(query: String, type: SearchType): List<PageLink> {
            if (offline) throw java.io.IOException("offline")
            return listOf(PageLink(SearchType.ARTISTS, "url/$query", query, "", "img/$query"))
        }
    }

    private var n = 0
    private fun settings() = Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("fl${n++}", 0))
    private fun a(name: String) = PickedArtist(name, "", null)

    @Test fun threeAreNeededToContinueAndTappingAgainTakesOneBack() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(), s)
        vm.toggle(a("Tulus")); vm.toggle(a("Raisa"))
        assertFalse(vm.canContinue)
        vm.next()
        assertEquals(1, vm.step.value); assertTrue(s.artists.value.isEmpty())
        vm.toggle(a("Hindia")); assertTrue(vm.canContinue)
        vm.toggle(a("hindia")); assertFalse(vm.canContinue)
        vm.toggle(a("Queen"))
        vm.next()
        assertEquals(2, vm.step.value)
        assertEquals(listOf("Tulus", "Raisa", "Queen"), s.artists.value.map { it.name })
        assertFalse(s.firstLaunchSeen.value)
    }

    @Test fun skippingTheArtistsKeepsNothingAndGoesToTheLink() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(), s)
        vm.toggle(a("Tulus"))
        vm.skipArtists()
        assertEquals(2, vm.step.value)
        assertTrue(s.artists.value.isEmpty()); assertFalse(s.firstLaunchSeen.value)
    }

    @Test fun skippingTheLinkEndsItAndIsRememberedAcrossARestart() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(), s)
        vm.skipArtists(); vm.finish()
        assertTrue(s.firstLaunchSeen.value)
        assertTrue(Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("fl${n - 1}", 0)).firstLaunchSeen.value)
    }

    @Test fun backFromTheLinkScreenReturnsToTheArtistsWithPicksKept() {
        val vm = FirstLaunchViewModel(Source(), settings())
        listOf("Tulus", "Raisa", "Hindia").forEach { vm.toggle(a(it)) }
        vm.next(); vm.back()
        assertEquals(1, vm.step.value); assertEquals(3, vm.picked.value.size)
    }

    @Test fun welcomeThenNameThenArtistsAndBackWalksTheSameWay() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(), s)
        assertEquals(Intro.WELCOME, vm.intro.value)
        vm.startFresh(); assertEquals(Intro.NAME, vm.intro.value)
        vm.name.value = "  Ali  "; vm.saveName()
        assertEquals("Ali", s.name.value); assertEquals(Intro.DONE, vm.intro.value)
        vm.back(); assertEquals(Intro.NAME, vm.intro.value); assertEquals("  Ali  ", vm.name.value)
        vm.back(); assertEquals(Intro.WELCOME, vm.intro.value)
    }

    @Test fun anEmptyNameDoesNotContinueAndSkipKeepsNone() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(), s)
        vm.startFresh(); vm.name.value = "   "; vm.saveName()
        assertEquals(Intro.NAME, vm.intro.value); assertEquals("", s.name.value)
        vm.skipName()
        assertEquals(Intro.DONE, vm.intro.value); assertEquals("", s.name.value)
    }

    @Test fun reopenedFromHomeHasNoWelcomeOrName() {
        val vm = FirstLaunchViewModel(Source(), settings(), reopen = true)
        assertEquals(Intro.DONE, vm.intro.value)
        vm.back(); assertEquals(Intro.DONE, vm.intro.value)
    }

    @Test fun photosAndPagesFillInWhenTheLookupsAnswer() {
        val vm = FirstLaunchViewModel(Source(), settings())
        assertTrue(vm.starters.value.all { it.photo != null && it.url.isNotEmpty() })
        assertEquals(STARTER_ARTISTS, vm.starters.value.map { it.name })
        assertFalse(vm.offline.value)
    }

    @Test fun offlineTheListStaysWithoutPhotosAndPicksStillWork() {
        val s = settings(); val vm = FirstLaunchViewModel(Source(offline = true), s)
        assertTrue(vm.offline.value)
        assertEquals(STARTER_ARTISTS.size, vm.starters.value.size)
        assertTrue(vm.starters.value.all { it.photo == null })
        listOf("Tulus", "Raisa", "Hindia").forEach { n -> vm.toggle(vm.starters.value.first { it.name == n }.picked()) }
        vm.next()
        assertEquals(3, s.artists.value.size)
    }

    @Test fun reopenedFromHomeStartsFromTheSavedArtistsAndHasNoSecondScreen() {
        val s = settings()
        s.setArtists(listOf(a("Tulus"), a("Raisa"), a("Hindia")))
        val vm = FirstLaunchViewModel(Source(), s, reopen = true)
        assertEquals(3, vm.picked.value.size)
        vm.toggle(a("Queen")); vm.next()
        assertEquals(1, vm.step.value)
        assertEquals(4, s.artists.value.size)
    }

    @Test fun editKeepsTheCurrentPicksTickedAndRemoveAndAddAreSaved() {
        val s = settings(); s.setArtists(listOf(PickedArtist("Tulus", "url/Tulus", null), a("Raisa"), a("Hindia")))
        val vm = FirstLaunchViewModel(Source(), s, reopen = true)
        assertEquals(listOf("tulus", "raisa", "hindia"), vm.picked.value.map { it.key })
        vm.toggle(a("Raisa")); vm.toggle(a("Queen")); vm.next()
        assertEquals(listOf("Tulus", "Hindia", "Queen"), s.artists.value.map { it.name })
        // a kept artist keeps the page it already had
        assertEquals("url/Tulus", s.artists.value.first().url)
    }

    @Test fun editAllowsFewerThanThreeAndEvenNone() {
        val s = settings(); s.setArtists(listOf(a("Tulus"), a("Raisa"), a("Hindia")))
        val vm = FirstLaunchViewModel(Source(), s, reopen = true)
        vm.toggle(a("Raisa")); vm.toggle(a("Hindia")); vm.next()
        assertEquals(listOf("Tulus"), s.artists.value.map { it.name })
        vm.toggle(a("Tulus")); assertTrue(vm.canContinue); vm.next()
        assertTrue(s.artists.value.isEmpty())
    }

    @Test fun leavingWithoutSavingKeepsTheOldPicks() {
        val s = settings(); s.setArtists(listOf(a("Tulus"), a("Raisa"), a("Hindia")))
        val vm = FirstLaunchViewModel(Source(), s, reopen = true)
        vm.toggle(a("Raisa")); vm.toggle(a("Queen")) // then Cancel/back: next() is never called
        assertEquals(listOf("Tulus", "Raisa", "Hindia"), s.artists.value.map { it.name })
    }

    @Test fun savingKeepsAPageHomeFoundWhileTheScreenWasOpen() {
        val s = settings(); s.setArtists(listOf(a("Tulus"), a("Raisa")))
        val vm = FirstLaunchViewModel(Source(), s, reopen = true)
        s.setArtists(listOf(PickedArtist("Tulus", "url/Tulus", null), a("Raisa")))
        vm.toggle(a("Queen")); vm.next()
        assertEquals("url/Tulus", s.artists.value.first().url)
    }
}
