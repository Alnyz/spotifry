package com.spotifry.music.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YourArtistsTest {
    @get:Rule val compose = createComposeRule()

    private val artists = listOf(PickedArtist("Tulus", "url/t", null), PickedArtist("Raisa", "url/r", null), PickedArtist("Hindia", "", null))
    private val songs = listOf(Song("1", "Hati-Hati di Jalan", "Tulus", 200, null), Song("2", "Mantan Terindah", "Raisa", 200, null))

    @Test fun pickedArtistsShowTheirRowsAndPlayingASongQueuesThem() {
        var played: Triple<List<Song>, Int, String>? = null
        var opened: PickedArtist? = null
        compose.setContent {
            MusicTheme { HomeContent(emptyList(), emptySet(), { l, i, n -> played = Triple(l, i, n) }, {}, artists = ArtistsHome(artists, songs), onOpenArtist = { opened = it }) }
        }
        compose.onNodeWithText("Your artists").assertIsDisplayed()
        compose.onNodeWithText("From your artists").assertIsDisplayed()
        // not "find a song", and no way back in while artists are picked
        compose.onAllNodesWithText("Find a song in Search to start. What you play shows up here.").assertCountEquals(0)
        compose.onAllNodesWithText("Pick artists for Home").assertCountEquals(0)
        compose.onNodeWithText("Mantan Terindah").performClick()
        assertEquals(Triple(songs, 1, "From your artists"), played)
        compose.onAllNodesWithText("Tulus")[0].performClick()
        assertEquals("Tulus", opened?.name)
    }

    @Test fun artistsWithoutSongsYetStillShowAndTheSongRowWaits() {
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, artists = ArtistsHome(artists)) } }
        compose.onNodeWithText("Your artists").assertIsDisplayed()
        compose.onAllNodesWithText("From your artists").assertCountEquals(0)
    }

    @Test fun skippedHomeKeepsTodaysTextAndOffersToPickArtists() {
        var picked = 0
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, onPickArtists = { picked++ }) } }
        compose.onNodeWithText("Find a song in Search to start. What you play shows up here.").assertIsDisplayed()
        compose.onNodeWithText("Pick artists for Home").performClick()
        assertEquals(1, picked)
        compose.onAllNodesWithText("Your artists").assertCountEquals(0)
    }
}
