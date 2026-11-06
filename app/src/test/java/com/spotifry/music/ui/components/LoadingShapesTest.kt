package com.spotifry.music.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.FakeMusicSource
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.testSong
import com.spotifry.music.ui.home.HomeContent
import com.spotifry.music.ui.home.PageActions
import com.spotifry.music.ui.home.PageScreen
import com.spotifry.music.ui.home.PageViewModel
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoadingShapesTest {
    @get:Rule val compose = createComposeRule()

    private val gate = CompletableDeferred<PageContent>()
    private fun page(type: SearchType = SearchType.PLAYLISTS) = PageViewModel(object : MusicSource by FakeMusicSource() {
        override suspend fun page(link: PageLink) = gate.await()
    }, PageLink(type, "u", "Name", "", null))

    private fun show(vm: PageViewModel) = compose.setContent {
        MusicTheme { PageScreen(vm, emptySet(), {}, { _, _, _ -> }, { _, _ -> }, {}, PageActions(), 0.dp) }
    }

    @Test fun aQuickLoadShowsNothingAndASlowOneHasOneDescription() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicTheme { LoadingShapes { repeat(3) { LoadingRow() } } } }
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithContentDescription("Loading").assertCountEquals(0)
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithContentDescription("Loading").assertCountEquals(1)
    }

    @Test fun aShapeRowIsTheHeightARealRowHasWithItsCover() {
        compose.setContent { MusicTheme { LoadingRow() } }
        compose.onNodeWithTag("loading_row").assertHeightIsEqualTo(70.dp)
    }

    @Test fun theFirstSongLandsWhereTheFirstShapeWas() {
        val vm = page()
        show(vm)
        val shape = compose.onAllNodesWithTag("loading_row").onFirst().getUnclippedBoundsInRoot()
        gate.complete(PageContent("Name", "", "Playlist", null, listOf(testSong), emptyList()))
        compose.waitForIdle()
        // the title is merged into its whole row, so this is the top of the row
        assertEquals(shape.top.value, compose.onNodeWithText(testSong.title).getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test fun aFailedLoadSwapsTheShapesForItsMessage() {
        val vm = page()
        show(vm)
        compose.onAllNodesWithTag("loading_row").assertCountEquals(5)
        gate.completeExceptionally(RuntimeException("no network"))
        compose.waitForIdle()
        compose.onAllNodesWithTag("loading_row").assertCountEquals(0)
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun anEmptyPageKeepsItsWords() {
        val vm = page()
        show(vm)
        gate.complete(PageContent("Name", "", "Playlist", null, emptyList(), emptyList()))
        compose.waitForIdle()
        compose.onAllNodesWithTag("loading_row").assertCountEquals(0)
        compose.onNodeWithText("Nothing to play here.").assertIsDisplayed()
    }

    @Test fun homeOnlySaysItsEmptyOnceTheHistoryIsRead() {
        var loading by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, loading = loading) } }
        val words = "Find a song in Search to start. What you play shows up here."
        compose.onNodeWithText(words).assertDoesNotExist()
        loading = false
        compose.onNodeWithText(words).assertIsDisplayed()
    }


    @Test fun theShortcutsWaitForTheRead() {
        var loading by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, loading = loading) } }
        compose.onNodeWithText("Playlists").assertDoesNotExist()
        loading = false
        compose.onNodeWithText("Playlists").assertIsDisplayed()
    }

    @Test fun onAnArtistPageTheFirstSongLandsWhereTheFirstShapeWas() {
        val vm = page(SearchType.ARTISTS)
        show(vm)
        val shape = compose.onAllNodesWithTag("loading_row").onFirst().getUnclippedBoundsInRoot()
        gate.complete(PageContent("Name", "", "Artist", null, listOf(testSong), emptyList()))
        compose.waitForIdle()
        assertEquals(shape.top.value, compose.onNodeWithText(testSong.title).getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test fun onYourOwnPlaylistTheFirstSongLandsWhereTheFirstShapeWas() {
        var songs by androidx.compose.runtime.mutableStateOf<List<com.spotifry.music.data.db.SongEntity>?>(null)
        compose.setContent {
            MusicTheme {
                com.spotifry.music.ui.playlists.PlaylistDetailContent(
                    "Mine", songs.orEmpty(), "", {}, com.spotifry.music.data.PlaylistOrder.CUSTOM, {}, {}, {}, { _, _ -> }, {}, {}, {}, loading = songs == null,
                )
            }
        }
        val shape = compose.onAllNodesWithTag("loading_row").onFirst().getUnclippedBoundsInRoot()
        songs = listOf(testSong.toEntity(), com.spotifry.music.data.source.Song("b", "Other", "X", 100, null).toEntity())
        compose.waitForIdle()
        assertEquals(shape.top.value, compose.onNodeWithText(testSong.title).getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test fun retryBringsTheShapesBackAndTheyLeaveAgain() {
        var attempt = CompletableDeferred<PageContent>()
        val vm = PageViewModel(object : MusicSource by FakeMusicSource() {
            override suspend fun page(link: PageLink) = attempt.await()
        }, PageLink(SearchType.PLAYLISTS, "u", "Name", "", null))
        show(vm)
        attempt.completeExceptionally(RuntimeException("no network"))
        compose.waitForIdle()
        attempt = CompletableDeferred()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("loading_row").assertCountEquals(5)
        attempt.complete(PageContent("Name", "", "Playlist", null, listOf(testSong), emptyList()))
        compose.waitForIdle()
        compose.onAllNodesWithTag("loading_row").assertCountEquals(0)
        compose.onNodeWithText(testSong.title).assertIsDisplayed()
    }
}
