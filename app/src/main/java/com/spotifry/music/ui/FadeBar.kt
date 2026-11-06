package com.spotifry.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotifry.music.Routes
import com.spotifry.music.ui.components.rememberStill
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BarTab(val route: String, val label: String, val icon: ImageVector)

/**
 * The bottom bar's measurements (design/look-refresh-2.html, direction 4, without the labels). Measured up from the screen's bottom:
 * the gesture area, then the 52 dp tab row (icons only, a 56 x 40 dp pill behind the selected one), then, while a song is loaded,
 * the 56 dp mini player and a 4 dp gap, then the fade. The page scrolls under all of it; [reserve] is how far up a list must keep
 * its last row clear.
 */
object FadeBarSpec {
    val TAB_ROW = 52.dp
    val MINI = 56.dp
    val MINI_GAP = 4.dp
    /** Never shorter than 32 dp: a shorter fade brings back a hard edge. */
    val FADE = 36.dp
    /** Opacity of the page colour at the top of the tab row. Never below 0.88: a heading behind the tabs ghosts through below that. */
    const val TAB_ROW_ALPHA = 0.96f
    val PILL_W = 56.dp
    val PILL_H = 40.dp

    fun miniBlock(playing: Boolean): Dp = if (playing) MINI + MINI_GAP else 0.dp
    /** Solid part: gesture area, tab row and the mini player. Snackbars sit on top of this. */
    fun solid(playing: Boolean, navInset: Dp): Dp = navInset + TAB_ROW + miniBlock(playing)
    /** Solid part plus the fade: the end padding of every list, so its last row rests clear of the fade. 164 dp playing and 104 dp not, on a 16 dp gesture area. */
    fun reserve(playing: Boolean, navInset: Dp): Dp = solid(playing, navInset) + FADE

    /**
     * The page colour's opacity at a height above the screen's bottom: solid down in the gesture area, [TAB_ROW_ALPHA] over the tab
     * row, then clear at [reserve]. With the capsule in it the fade is held 28 dp longer, so it clears 96 dp above the tabs.
     */
    fun stops(playing: Boolean, navInset: Dp): List<Pair<Dp, Float>> {
        val tabTop = navInset + TAB_ROW
        return listOf(
            0.dp to 1f,
            navInset.coerceAtLeast(12.dp) to 1f,
            tabTop to TAB_ROW_ALPHA,
            (tabTop + if (playing) 28.dp else 0.dp) to TAB_ROW_ALPHA,
            reserve(playing, navInset) to 0f,
        )
    }
}

/** Which tab a screen belongs to: Moods and a Page open from Search, a Playlist from Playlists, Settings, the equalizer and Your listening from Home. */
fun tabSelected(tab: String, route: String?): Boolean = route == tab ||
    (tab == Routes.PLAYLISTS && route == Routes.PLAYLIST) ||
    (tab == Routes.SEARCH && (route == Routes.PAGE || route == Routes.MOOD)) ||
    (tab == Routes.LISTEN && (route == Routes.SETTINGS || route == Routes.EQUALIZER || route == Routes.LISTENING))

/**
 * Where the selection pill is, as its two edges in pixels. They move on their own springs, the edge the pill is travelling
 * towards the stiffer one, so it stretches across the tabs in between and then settles with a small bounce.
 */
class PillState {
    val left = Animatable(0f)
    val right = Animatable(0f)
    var width by mutableIntStateOf(0)
    internal var placedAt = -1f
    internal var placedWidth = 0
}

@Composable
fun rememberPillState() = remember { PillState() }

private suspend fun PillState.moveTo(l: Float, r: Float, width: Int, still: Boolean) {
    if (still || placedWidth != width) {
        left.snapTo(l); right.snapTo(r)
    } else {
        val right_ = l > left.value
        val leading = spring<Float>(0.7f, 600f)
        val trailing = spring<Float>(0.7f, 230f)
        coroutineScope {
            launch { left.animateTo(l, if (right_) trailing else leading) }
            launch { right.animateTo(r, if (right_) leading else trailing) }
        }
    }
    placedWidth = width
}

/**
 * No block and no blur: a gradient of the page colour, solid at the bottom, clear at the top, so the page shows through
 * faintly and has no hard edge. [mini] (the glass capsule) sits inside it above the tabs. The fade lets touches through to
 * the page; everything below it (capsule, tabs, the gaps and the gesture area) swallows them.
 */
@Composable
fun FadeBar(
    tabs: List<BarTab>, route: String?, onTab: (String) -> Unit, playing: Boolean, modifier: Modifier = Modifier,
    pill: PillState = rememberPillState(), mini: @Composable () -> Unit = {},
) {
    val page = MaterialTheme.colorScheme.background
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                val h = size.height
                val stops = FadeBarSpec.stops(playing, navInset)
                // the stops count up from the bottom; the brush runs from the top down, and the last stop (the fade's end) is the top
                val colorStops = stops.reversed().map { (d, a) -> (1f - d.toPx() / h).coerceIn(0f, 1f) to page.copy(alpha = a) }.toTypedArray()
                drawRect(Brush.verticalGradient(colorStops = colorStops, startY = 0f, endY = h))
            }
            .padding(top = FadeBarSpec.FADE),
    ) {
        Column(Modifier.fillMaxWidth().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
            if (playing) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = FadeBarSpec.MINI_GAP)) { mini() }
            val selected = tabs.indexOfFirst { tabSelected(it.route, route) }
            val still = rememberStill()
            val density = LocalDensity.current
            val accent = MaterialTheme.colorScheme.primary
            LaunchedEffect(selected, pill.width, still) {
                if (selected < 0 || pill.width == 0) return@LaunchedEffect
                val half = with(density) { FadeBarSpec.PILL_W.toPx() / 2 }
                val centre = pill.width.toFloat() / tabs.size * (selected + 0.5f)
                pill.moveTo(centre - half, centre + half, pill.width, still)
            }
            Row(
                Modifier.fillMaxWidth().height(FadeBarSpec.TAB_ROW).padding(horizontal = 8.dp).selectableGroup()
                    .onSizeChanged { pill.width = it.width }
                    .drawBehind {
                        // read here, in the draw step, so a moving pill redraws only this row and an idle one draws nothing
                        val l = pill.left.value.coerceAtLeast(0f)
                        val r = pill.right.value.coerceAtMost(size.width)
                        if (selected >= 0 && pill.placedWidth > 0 && r > l) {
                            val ph = FadeBarSpec.PILL_H.toPx()
                            drawRoundRect(accent.copy(alpha = 0.18f), Offset(l, (size.height - ph) / 2), Size(r - l, ph), CornerRadius(ph / 2))
                        }
                    },
                Arrangement.SpaceEvenly,
            ) {
                tabs.forEachIndexed { i, t -> Tab(t, i == selected, still) { onTab(t.route) } }
            }
            Box(Modifier.height(navInset))
        }
    }
}

@Composable
private fun RowScope.Tab(tab: BarTab, selected: Boolean, still: Boolean, onClick: () -> Unit) {
    val target = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val color by animateColorAsState(target, if (still) tween(0) else tween(180), label = "tab icon")
    // icons only: the name is for screen readers. The whole 52 dp column is the tap target, the pill is just its look
    Box(
        Modifier.weight(1f).height(FadeBarSpec.TAB_ROW)
            .semantics { contentDescription = tab.label }
            .selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp)) }
}
