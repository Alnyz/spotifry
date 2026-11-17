package com.spotifry.music.ui.playlists

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.testSong
import com.spotifry.music.player.SongPlayer
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistCoverUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: PlaylistsViewModel
    private var id = 0L
    private val store = androidx.lifecycle.ViewModelStore()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        vm = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PlaylistsViewModel(db.playlists(), db.songs(), object : SongPlayer { override fun playList(songs: List<Song>, startIndex: Int, from: String?) {} }) as T
        })[PlaylistsViewModel::class.java]
        id = db.playlists().create("Road trip")
        db.songs().upsert(com.spotifry.music.data.db.SongEntity("a", "Song A", "X", 100, null, null, null, null))
        db.playlists().addSong(id, "a")
    }
    // the view model's flows stop before the database closes, so nothing outlives the test
    @After fun tearDown() { store.clear(); db.close() }

    private fun show() = compose.setContent { MusicTheme { PlaylistDetailScreen(vm, id, {}, { _, _ -> }, 0.dp) } }

    @Test fun thePencilOffersRenameAndChangeCover() {
        show()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Rename or change cover").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithContentDescription("Rename or change cover").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Change cover").assertIsDisplayed()
    }

    @Test fun theMenuKeepsShareAndDeleteOnly() {
        show()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Playlist options").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithContentDescription("Playlist options").performClick()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Delete playlist").assertIsDisplayed()
        assert(compose.onAllNodesWithTextCount("Rename") == 0) { "Rename is still in the menu" }
    }

    @Test fun useDefaultCoverOnlyShowsOnceACustomCoverIsSet() {
        show()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Rename or change cover").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithContentDescription("Rename or change cover").performClick()
        compose.onNodeWithText("Change cover").performClick()
        compose.onNodeWithText("Choose from gallery").assertIsDisplayed()
        assert(compose.onAllNodesWithTextCount("Use default cover") == 0)
    }

    @Test fun withACustomCoverTheSheetOffersTheWayBack() {
        runBlocking { db.playlists().setCover(id, "x.jpg") }
        show()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Rename or change cover").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithContentDescription("Rename or change cover").performClick()
        compose.onNodeWithText("Change cover").performClick()
        compose.onNodeWithText("Use default cover").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodesWithText(t).fetchSemanticsNodes().size
}
