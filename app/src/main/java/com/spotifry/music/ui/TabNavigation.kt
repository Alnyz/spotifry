package com.spotifry.music.ui

import androidx.navigation.NavHostController
import com.spotifry.music.Routes

private val TAB_ROUTES = setOf(Routes.LISTEN, Routes.SEARCH, Routes.SAVED, Routes.PLAYLISTS)

/**
 * What the bottom bar does. A tab's own screens (Search with a page open) are saved and come back with the tab.
 * A screen opened from Home that is no tab (Settings, Your listening, the equalizer, a page from Home) is dropped first:
 * saved with the rest, it came back when Home was pressed, so Home looked dead.
 */
fun NavHostController.goToTab(route: String) {
    val aboveHome = currentBackStack.value.dropWhile { it.destination.route != Routes.LISTEN }.getOrNull(1)?.destination?.route
    if (aboveHome != null && aboveHome !in TAB_ROUTES) popBackStack(Routes.LISTEN, inclusive = false)
    navigate(route) {
        popUpTo(Routes.LISTEN) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
