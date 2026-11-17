package com.spotifry.music.ui.saved

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotCopiedTest {
    @get:Rule val compose = createComposeRule()

    private val rows = listOf(SavedRow(Song("a", "Copied song", "A", 200, null), true), SavedRow(Song("b", "Waiting song", "B", 200, null), true))
    private fun show(uncopied: Set<String>?) = compose.setContent {
        MusicTheme { SavedContent(SavedView(SavedFilter.NotCopied, rows), {}, {}, { _, _ -> }, {}, uncopied = uncopied) }
    }

    @Test fun listsOnlyTheSongsWithNoCopy() {
        show(setOf("b"))
        compose.onNodeWithText("Not copied").assertIsDisplayed()
        compose.onNodeWithText("Waiting song").assertIsDisplayed()
        compose.onNodeWithText("Copied song").assertDoesNotExist()
    }

    @Test fun everythingCopiedSaysSo() {
        show(emptySet())
        compose.onNodeWithText("Everything is copied").assertIsDisplayed()
    }

    @Test fun beforeTheFolderIsLookedAtItShowsNothing() {
        show(null)
        compose.onNodeWithText("Everything is copied").assertDoesNotExist()
        compose.onNodeWithText("Copied song").assertDoesNotExist()
        compose.onNodeWithText("Waiting song").assertDoesNotExist()
    }
}
