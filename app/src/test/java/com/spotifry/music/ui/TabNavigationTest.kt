package com.spotifry.music.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController

    private fun host() = compose.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = "listen") {
            listOf("listen", "search", "saved", "playlists", "settings", "settings/equalizer", "listening", "page").forEach { r -> composable(r) { Text(r) } }
        }
    }

    private fun goTo(r: String) = compose.runOnUiThread { nav.goToTab(r) }
    private fun open(r: String) = compose.runOnUiThread { nav.navigate(r) }
    private fun route() = nav.currentDestination?.route

    @Test fun homeTabFromSettingsGoesHome() {
        host(); open("settings"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun homeTabFromYourListeningGoesHome() {
        host(); open("listening"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun homeTabAfterLeavingSettingsForAnotherTabGoesHome() {
        host(); open("settings"); compose.waitForIdle()
        goTo("search"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun homeTabFromTheEqualizerGoesHome() {
        host(); open("settings"); compose.waitForIdle(); open("settings/equalizer"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun homeTabFromAPageOpenedOnHomeGoesHome() {
        host(); open("page"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun homeTabFromAPageOpenedInSearchGoesHome() {
        host(); goTo("search"); compose.waitForIdle(); open("page"); compose.waitForIdle()
        goTo("listen"); compose.waitForIdle()
        assertEquals("listen", route())
    }

    @Test fun searchKeepsItsPageWhileYouLookAtAnotherTab() {
        host(); goTo("search"); compose.waitForIdle(); open("page"); compose.waitForIdle()
        goTo("saved"); compose.waitForIdle()
        assertEquals("saved", route())
        goTo("search"); compose.waitForIdle()
        assertEquals("page", route())
    }

    private fun fromSettingsTo(tab: String) {
        host(); open("settings"); compose.waitForIdle()
        goTo(tab); compose.waitForIdle()
        assertEquals(tab, route())
    }

    @Test fun searchIsReachableFromSettings() = fromSettingsTo("search")
    @Test fun savedIsReachableFromSettings() = fromSettingsTo("saved")
    @Test fun playlistsIsReachableFromSettings() = fromSettingsTo("playlists")
}
