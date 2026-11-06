package com.spotifry.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotifry.music.Routes

class BarTab(val route: String, val label: String, val icon: ImageVector)

/**
 * The bottom bar's measurements, from the mock (design/look-refresh-2.html, direction 4). Measured up from the screen's bottom:
 * the gesture area, then the 56 dp tab row, then (while a song is loaded) the 56 dp mini player and a 4 dp gap, then the fade.
 * The page scrolls under all of it; [reserve] is how far up a list must keep its last row clear.
 */
object FadeBarSpec {
    val TAB_ROW = 56.dp
    val MINI = 56.dp
    val MINI_GAP = 4.dp
    /** Never shorter than 32 dp: a shorter fade brings back a hard edge. */
    val FADE = 36.dp
    /** Opacity of the page colour at the top of the tab row. Never below 0.88 (the selected Ember label then falls to 4.6:1). */
    const val TAB_ROW_ALPHA = 0.92f

    fun miniBlock(playing: Boolean): Dp = if (playing) MINI + MINI_GAP else 0.dp
    /** Solid part: gesture area, tab row and the mini player. Snackbars sit on top of this. */
    fun solid(playing: Boolean, navInset: Dp): Dp = navInset + TAB_ROW + miniBlock(playing)
    /** Solid part plus the fade: the end padding of every list, so its last row rests clear of the fade. 168 dp playing and 108 dp not, on a 16 dp gesture area. */
    fun reserve(playing: Boolean, navInset: Dp): Dp = solid(playing, navInset) + FADE
}

/** Which tab a screen belongs to: Moods and a Page open from Search, a Playlist from Playlists, Settings, the equalizer and Your listening from Home. */
fun tabSelected(tab: String, route: String?): Boolean = route == tab ||
    (tab == Routes.PLAYLISTS && route == Routes.PLAYLIST) ||
    (tab == Routes.SEARCH && (route == Routes.PAGE || route == Routes.MOOD)) ||
    (tab == Routes.LISTEN && (route == Routes.SETTINGS || route == Routes.EQUALIZER || route == Routes.LISTENING))

/**
 * No block and no blur: a gradient of the page colour, solid at the bottom, clear at the top, so the page shows through
 * faintly and has no hard edge. [mini] (the glass capsule) sits inside it above the tabs.
 */
@Composable
fun FadeBar(tabs: List<BarTab>, route: String?, onTab: (String) -> Unit, playing: Boolean, modifier: Modifier = Modifier, mini: @Composable () -> Unit = {}) {
    val page = MaterialTheme.colorScheme.background
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                val h = size.height
                fun up(d: Dp) = (1f - d.toPx() / h).coerceIn(0f, 1f)
                val tabTop = FadeBarSpec.TAB_ROW + navInset
                // with the capsule in it the fade is held a little longer, then clears 96 dp above the tabs
                val hold = if (playing) 28.dp else 0.dp
                val stops = arrayOf(
                    0f to page.copy(alpha = 0f),
                    up(tabTop + hold) to page.copy(alpha = FadeBarSpec.TAB_ROW_ALPHA),
                    up(tabTop) to page.copy(alpha = FadeBarSpec.TAB_ROW_ALPHA),
                    up(navInset.coerceAtLeast(12.dp)) to page,
                    1f to page,
                )
                drawRect(Brush.verticalGradient(colorStops = stops, startY = 0f, endY = h))
            }
            .padding(top = FadeBarSpec.FADE),
    ) {
        if (playing) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = FadeBarSpec.MINI_GAP)) { mini() }
        Row(Modifier.fillMaxWidth().height(FadeBarSpec.TAB_ROW).padding(horizontal = 8.dp), Arrangement.SpaceEvenly) {
            tabs.forEach { t -> Tab(t, tabSelected(t.route, route)) { onTab(t.route) } }
        }
        Box(Modifier.height(navInset))
    }
}

@Composable
private fun RowScope.Tab(tab: BarTab, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).height(FadeBarSpec.TAB_ROW).selectable(selected, role = Role.Tab, onClick = onClick),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        // the glow: the accent at 16% in a soft rounded patch with a wider, fainter halo. Drawn once, nothing animates
        Box(
            Modifier.size(width = 48.dp, height = 32.dp).drawBehind {
                if (!selected) return@drawBehind
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), c, size.width * 0.75f), size.width * 0.75f, c)
                drawRoundRect(accent.copy(alpha = 0.16f), Offset(2.dp.toPx(), 0f), Size(size.width - 4.dp.toPx(), size.height), CornerRadius(16.dp.toPx()))
            },
            contentAlignment = Alignment.Center,
        ) { Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp)) }
        Text(tab.label, Modifier.padding(top = 2.dp), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}
