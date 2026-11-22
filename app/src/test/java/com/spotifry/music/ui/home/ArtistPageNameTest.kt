package com.spotifry.music.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.NameLook
import com.spotifry.music.data.NameShow
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtistPageNameTest {
    @get:Rule val compose = createComposeRule()
    private val link = PageLink(SearchType.ARTISTS, "https://music.youtube.com/channel/x", "Radiohead", "", null)

    private fun show(starred: Boolean, names: ArtistNameUi) = compose.setContent {
        ArtistPage(link, null, false, emptyMap(), starred, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, PageActions(), 0.dp, {}, names)
    }

    private fun ui(look: NameLook? = null) = ArtistNameUi(look, null, null, false, {}, {}, {}, {}, {}, {})

    @Test fun pencilOnlyForFavourites() {
        show(false, ui())
        compose.onNodeWithContentDescription("Change name style").assertDoesNotExist()
    }

    @Test fun favouritesGetThePencil() {
        show(true, ui())
        compose.onNodeWithContentDescription("Change name style").assertExists()
    }

    @Test fun classicWhileTheLogoLoads() {
        show(true, ui())
        compose.onNodeWithText("Radiohead").assertExists()
    }

    @Test fun favouriteStyleShows() {
        show(true, ui(NameLook(NameShow.TEXT, NameStyle.BLOCK)))
        compose.onNodeWithText("RADIOHEAD").assertExists()
    }
}
