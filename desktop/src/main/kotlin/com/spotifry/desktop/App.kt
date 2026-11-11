package com.spotifry.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.isFreshInstall
import com.spotifry.music.data.source.artAt
import dev.chrisbanes.haze.hazeSource

/** Where the window is: a page, or a playlist opened from Playlists. */
sealed interface Route {
    val page: Page
    data object Home : Route { override val page = Page.Home }
    data object Search : Route { override val page = Page.Search }
    data object Saved : Route { override val page = Page.Saved }
    data object Playlists : Route { override val page = Page.Playlists }
    data class Playlist(val id: Long) : Route { override val page = Page.Playlists }
    data object Settings : Route { override val page = Page.Settings }
}

enum class Page(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Home), Search("Search", Icons.Search), Saved("Saved", Icons.Saved), Playlists("Playlists", Icons.Playlists), Settings("Settings", Icons.Settings);
}

@Composable
fun App(services: Services, startOn: Route = Route.Home, initialQuery: String = "", restart: () -> Unit = {}, startNowPlaying: Boolean = false) {
    val flow = remember(services) { BackupFlow(services.library, services.scope, restart) }
    // the theme and accent come from the settings, which a restored backup can change
    val theme by services.library.settings.theme.collectAsState()
    val accent by services.library.settings.accent.collectAsState()
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    Palette.dark = when (theme) { com.spotifry.music.data.ThemeMode.DARK -> true; com.spotifry.music.data.ThemeMode.LIGHT -> false; else -> system }
    Palette.accentChoice = AccentChoice.of(accent)
    var route by remember { mutableStateOf(startOn) }
    var nowPlaying by remember { mutableStateOf(startNowPlaying) }
    var query by remember { mutableStateOf(initialQuery) }
    var searchRun by remember { mutableStateOf(if (initialQuery.isBlank()) 0 else 1) }
    // the welcome shows once, on an empty library that never saw it
    val lib = services.library
    var welcomed by remember { mutableStateOf(false) }
    val fresh by produceState<Boolean?>(null, services) { value = !lib.settings.firstLaunchSeen.value && isFreshInstall(true, lib.db.history(), lib.db.songs(), lib.db.playlists()) }
    val state by services.player.state.collectAsState()

    // the window takes the colour of the cover that plays
    val cover = state.current?.let { it.thumbnailUrl ?: it.localThumbPath }
    val tint by produceState<Color?>(null, cover) { value = cover?.let { c -> Covers.load(artAt(c, false))?.tint() } }
    Palette.glow = tint
    val glow by animateColorAsState(tint ?: Palette.accent, tween(600))
    val base by animateColorAsState(lerp(Palette.bg, tint ?: Palette.accent, if (Palette.dark) 0.12f else 0.10f), tween(600))

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val jump = remember { FocusRequester() }
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) { root.requestFocus() }
    fun search() { if (query.isNotBlank()) { route = Route.Search; nowPlaying = false; searchRun++ } }
    val tab = when { nowPlaying -> Tab.NowPlaying; route.page == Page.Home -> Tab.Home; route.page == Page.Saved || route.page == Page.Playlists -> Tab.Saved; else -> null }

    Box(
        Modifier.fillMaxSize().background(base).focusRequester(root).focusable()
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.K) { jump.requestFocus(); true } else false },
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(glow.copy(alpha = if (Palette.dark) 0.42f else 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1500f, -100f), radius = 1100f)))
        if (fresh == true && !welcomed) { FirstLaunch(services, flow) { welcomed = true }; BackupDialogs(flow); return@Box }
        if (fresh == null) return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 1150.dp
            Row(Modifier.fillMaxSize().hazeSource(haze)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        tab, { t -> when (t) { Tab.Home -> { route = Route.Home; nowPlaying = false }; Tab.NowPlaying -> nowPlaying = true; Tab.Saved -> { route = Route.Saved; nowPlaying = false } } },
                        { route = Route.Settings; nowPlaying = false }, route == Route.Settings && !nowPlaying,
                        query, { query = it }, ::search, jump, compact,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth().padding(start = if (compact) 28.dp else 40.dp, end = if (compact) 28.dp else 40.dp)) {
                        val pad = Modifier.widthIn(max = if (nowPlaying) 1400.dp else 1180.dp).fillMaxHeight().padding(top = 10.dp)
                        // pages fade into each other instead of snapping
                        androidx.compose.runtime.CompositionLocalProvider(LocalBottomInset provides (if (state.current != null) 112.dp else 0.dp)) { androidx.compose.animation.Crossfade(if (nowPlaying) "now" else route, animationSpec = tween(350)) { shown ->
                            if (shown == "now") NowPlayingPage(services, pad)
                            else when (val r = shown as Route) {
                                Route.Home -> HomePage(services, { route = it }, pad)
                                Route.Search -> SearchPage(services, state, query, searchRun, pad)
                                Route.Saved -> SavedPage(services, { route = it }, pad)
                                Route.Playlists -> PlaylistsPage(services, { route = it }, pad)
                                is Route.Playlist -> PlaylistPage(services, r.id, { route = it }, pad)
                                Route.Settings -> SettingsPage(services, flow, pad)
                            }
                        } }
                    }
                }
                // on Now playing the song is shown in the page itself, so the side panel slides away
                androidx.compose.animation.AnimatedVisibility(
                    !nowPlaying, enter = androidx.compose.animation.fadeIn(tween(350)) + androidx.compose.animation.expandHorizontally(tween(350)),
                    exit = androidx.compose.animation.fadeOut(tween(350)) + androidx.compose.animation.shrinkHorizontally(tween(350)),
                ) { SidePanel(services, state, if (compact) 330.dp else 380.dp, this@BoxWithConstraints.maxHeight) }
            }
            PlayerBar(services, state, haze, Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 20.dp))
        }
        BackupDialogs(flow)
        ImportDialog(services) { route = Route.Playlist(it) }
    }
}

fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** A thin seek bar: drag or click to set a value between 0 and [max]; [onDone] runs when the finger or the button lets go. */
@Composable
fun Track(value: Float, max: Float, color: Color, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(1) }
    val frac = (value / max).coerceIn(0f, 1f)
    Box(
        modifier.height(20.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(max) { detectTapGestures { onChange(it.x / width * max); onDone() } }
            .pointerInput(max) { detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, _ -> onChange((change.position.x / width).coerceIn(0f, 1f) * max) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.ink.copy(alpha = 0.12f)))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(color))
        Box(Modifier.offset { IntOffset((frac * width).toInt() - 6.dp.roundToPx(), 0) }.size(12.dp).clip(CircleShape).background(Palette.ink))
    }
}
