package com.spotifry.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.spotifry.music.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FadeBarTest {
    @get:Rule val compose = createComposeRule()
    private val tabs = listOf(
        BarTab(Routes.LISTEN, "Home", Icons.Home), BarTab(Routes.SEARCH, "Search", Icons.Search),
        BarTab(Routes.SAVED, "Saved", Icons.Saved), BarTab(Routes.PLAYLISTS, "Playlists", Icons.List),
    )

    @Test fun endPaddingIs168WithASongAnd108Without_OnA16dpGestureArea() {
        assertEquals(168.dp, FadeBarSpec.reserve(true, 16.dp))
        assertEquals(108.dp, FadeBarSpec.reserve(false, 16.dp))
        // the system bar is added on top: a 3-button bar (48 dp) lifts the whole thing
        assertEquals(200.dp, FadeBarSpec.reserve(true, 48.dp))
        assertTrue(FadeBarSpec.FADE >= 32.dp)
        assertTrue(FadeBarSpec.TAB_ROW_ALPHA >= 0.88f)
    }

    @Test fun eachScreenBelongsToItsTab() {
        fun on(route: String?) = tabs.filter { tabSelected(it.route, route) }.map { it.label }
        assertEquals(listOf("Home"), on(Routes.LISTEN))
        assertEquals(listOf("Home"), on(Routes.SETTINGS))
        assertEquals(listOf("Home"), on(Routes.EQUALIZER))
        assertEquals(listOf("Home"), on(Routes.LISTENING))
        assertEquals(listOf("Search"), on(Routes.SEARCH))
        assertEquals(listOf("Search"), on(Routes.MOOD))
        assertEquals(listOf("Search"), on(Routes.PAGE))
        assertEquals(listOf("Saved"), on(Routes.SAVED))
        assertEquals(listOf("Playlists"), on(Routes.PLAYLISTS))
        assertEquals(listOf("Playlists"), on(Routes.PLAYLIST))
    }

    @Test fun fourLabelledTabs_selectedOneIsMarked_clickGoesToItsRoute() {
        val route = mutableStateOf<String?>(Routes.SEARCH)
        val clicked = mutableListOf<String>()
        compose.setContent { MaterialTheme { FadeBar(tabs, route.value, { clicked += it; route.value = it }, playing = false) } }
        tabs.forEach { compose.onNodeWithText(it.label).assert(SemanticsMatcher("is a tab") { n -> n.config.getOrNull(SemanticsProperties.Role) == Role.Tab }) }
        compose.onNodeWithText("Search").assertIsSelected()
        compose.onNodeWithText("Home").assertIsNotSelected()
        compose.onNodeWithText("Playlists").performClick()
        assertEquals(listOf(Routes.PLAYLISTS), clicked)
        compose.onNodeWithText("Playlists").assertIsSelected()
        compose.onNodeWithText("Search").assertIsNotSelected()
    }

    @Test fun capsuleShowsOnlyWhileASongIsLoaded() {
        val playing = mutableStateOf(false)
        compose.setContent { MaterialTheme { FadeBar(tabs, Routes.LISTEN, {}, playing.value) { androidx.compose.material3.Text("capsule") } } }
        assertTrue(compose.onAllNodes(hasText("capsule")).fetchSemanticsNodes().isEmpty())
        playing.value = true
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(hasText("capsule")).fetchSemanticsNodes().isEmpty())
    }
}
