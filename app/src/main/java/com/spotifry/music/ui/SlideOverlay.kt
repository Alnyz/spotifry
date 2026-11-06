package com.spotifry.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

const val SLIDE_OUT_MS = 200

/**
 * A full-screen page that slides up from the bottom and back down. The moment [visible] turns false it stops taking touches:
 * it is still drawn while it slides away, but its touch area is nothing, so the mini player and the tabs it uncovers last
 * answer the first tap. (Left whole, it caught taps for the whole slide-out and the first tap on the bar was lost.)
 */
@Composable
fun SlideOverlay(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // the slide-out must still travel the page's full height after the page below has been given no size
    val height = remember { intArrayOf(0) }
    AnimatedVisibility(visible, modifier, enter = slideInVertically { it }, exit = slideOutVertically(tween(SLIDE_OUT_MS)) { height[0] }) {
        Box(
            Modifier.fillMaxSize().layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                if (visible) {
                    height[0] = p.height
                    layout(p.width, p.height) { p.place(0, 0) }
                } else {
                    // children outside their parent's bounds are not hit, but they are still drawn
                    layout(0, 0) { p.place(0, 0) }
                }
            },
        ) { content() }
    }
}
