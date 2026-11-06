package com.spotifry.music.ui.home

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
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

    private val list get() = compose.onAllNodes(hasScrollToNodeAction()).onFirst()

    @Test fun pickedArtistsShowTheirRowsAndPlayingASongQueuesThem() {
        var played: Triple<List<Song>, Int, String>? = null
        var opened: PickedArtist? = null
        compose.setContent {
            MusicTheme { HomeContent(emptyList(), emptySet(), { l, i, n -> played = Triple(l, i, n) }, {}, artists = ArtistsHome(artists, songs), onOpenArtist = { opened = it }) }
        }
        // the hero plays what the artist pages gave, and says how much
        compose.onNodeWithText("Songs from your artists").assertIsDisplayed()
        compose.onNodeWithText("2 songs, 2 artists").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(Triple(songs, 0, "From your artists"), played)
        compose.onAllNodesWithText("Nothing played yet").assertCountEquals(0)
        list.performScrollToNode(hasText("Tulus"))
        compose.onAllNodesWithText("Tulus")[0].performClick()
        assertEquals("Tulus", opened?.name)
        list.performScrollToNode(hasText("Mantan Terindah"))
        compose.onNodeWithText("From your artists").assertExists()
        compose.onNodeWithText("Mantan Terindah").performClick()
        assertEquals(Triple(songs, 1, "From your artists"), played)
    }

    @Test fun artistsWithoutSongsHaveNoHeroButStillShowWithAWayToImportAndMoods() {
        compose.setContent { MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, artists = ArtistsHome(artists)) } }
        compose.onNodeWithText("Your artists").assertIsDisplayed()
        compose.onAllNodesWithText("Songs from your artists").assertCountEquals(0)
        compose.onAllNodesWithText("From your artists").assertCountEquals(0)
        list.performScrollToNode(hasText("Import a Spotify playlist"))
        list.performScrollToNode(hasText("Moods and genres"))
        list.performScrollToNode(hasText("Sleep")) // no hero, so six tiles
    }

    @Test fun skippedHomeSaysNothingPlayedAndEveryWayLeads() {
        var search = 0; var import = 0; var picked = 0; var mood: Mood? = null
        compose.setContent {
            MusicTheme { HomeContent(emptyList(), emptySet(), { _, _, _ -> }, {}, onSearch = { search++ }, onImport = { import++ }, onPickArtists = { picked++ }, onMood = { mood = it }) }
        }
        compose.onNodeWithText("Nothing played yet").assertIsDisplayed()
        compose.onAllNodesWithText("Your artists").assertCountEquals(0)
        compose.onAllNodesWithText("Saved").assertCountEquals(0) // nothing in it, and it's in the bottom bar
        compose.onNodeWithText("Search for a song").performClick()
        compose.onNodeWithText("Import a Spotify playlist").performClick()
        list.performScrollToNode(hasText("Pick your artists"))
        compose.onNodeWithText("Pick your artists").performClick()
        assertEquals(Triple(1, 1, 1), Triple(search, import, picked))
        list.performScrollToNode(hasText("Chill"))
        compose.onNodeWithText("Chill").performClick()
        assertEquals("Chill", mood?.name)
    }
}
