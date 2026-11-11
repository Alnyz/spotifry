package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.spotifry.music.data.isFreshInstall
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode
import dev.chrisbanes.haze.HazeState
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
    val route: Route get() = when (this) { Home -> Route.Home; Search -> Route.Search; Saved -> Route.Saved; Playlists -> Route.Playlists; Settings -> Route.Settings }
}

private const val BAR = 84

@Composable
fun App(services: Services, startOn: Route = Route.Home, initialQuery: String = "", restart: () -> Unit = {}, startNowPlaying: Boolean = false) {
    val player = services.player; val setVolume = services.setVolume
    val flow = remember(services) { BackupFlow(services.library, services.scope, restart) }
    // the theme and accent come from the settings, which a restored backup can change
    val theme by services.library.settings.theme.collectAsState()
    val accent by services.library.settings.accent.collectAsState()
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    Palette.dark = when (theme) { com.spotifry.music.data.ThemeMode.DARK -> true; com.spotifry.music.data.ThemeMode.LIGHT -> false; else -> system }
    Palette.accentChoice = AccentChoice.of(accent)
    var route by remember { mutableStateOf(startOn) }
    var nowPlaying by remember { mutableStateOf(startNowPlaying) }
    // the welcome shows once, on an empty library that never saw it
    val lib = services.library
    var welcomed by remember { mutableStateOf(false) }
    val fresh by produceState<Boolean?>(null, services) { value = !lib.settings.firstLaunchSeen.value && isFreshInstall(true, lib.db.history(), lib.db.songs(), lib.db.playlists()) }
    val page = route.page
    val haze = remember { HazeState() }
    val state by player.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        // the ember glow behind the page, like the phone's top glow
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Palette.accent.copy(alpha = 0.13f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1500f, 0f), radius = 900f)))
        if (fresh == true && !welcomed) { FirstLaunch(services, flow) { welcomed = true }; BackupDialogs(flow); return@Box }
        if (fresh == null) return@Box
        Row(Modifier.fillMaxSize().hazeSource(haze)) {
            Spacer(Modifier.width(230.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val pad = Modifier.padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = (BAR + 24).dp)
                if (nowPlaying) NowPlayingPage(services, Modifier.padding(start = 40.dp, end = 24.dp, top = 24.dp, bottom = (BAR + 24).dp))
                else when (val r = route) {
                    Route.Home -> HomePage(services, { route = it }, pad)
                    Route.Search -> SearchPage(services, state, initialQuery, pad)
                    Route.Saved -> SavedPage(services, pad)
                    Route.Playlists -> PlaylistsPage(services, { route = it }, pad)
                    is Route.Playlist -> PlaylistPage(services, r.id, { route = it }, pad)
                    Route.Settings -> SettingsPage(services, flow, pad)
                }
            }
        }
        Sidebar(page, { route = it.route; nowPlaying = false }, haze, Modifier.align(Alignment.TopStart).fillMaxHeight().padding(bottom = BAR.dp))
        BackupDialogs(flow)
        ImportDialog(services) { route = Route.Playlist(it) }
        PlayerBar(player, setVolume, state, haze, nowPlaying, { nowPlaying = it }, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BAR.dp))
    }
}

@Composable
private fun Sidebar(page: Page, onPage: (Page) -> Unit, haze: HazeState, modifier: Modifier) {
    Column(modifier.width(230.dp).glass(haze, RoundedCornerShape(0.dp), tint = 0.62f).padding(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Page.entries.filter { it != Page.Settings }.forEach { NavItem(it, it == page) { onPage(it) } } }
        Spacer(Modifier.weight(1f))
        NavItem(Page.Settings, page == Page.Settings) { onPage(Page.Settings) }
    }
}

@Composable
private fun NavItem(p: Page, on: Boolean, onClick: () -> Unit) {
    val color = if (on) Palette.accent else Palette.ink2
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) Palette.accent.copy(alpha = 0.14f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(p.icon, null, Modifier.size(24.dp), tint = color)
        Text(p.label, color = color, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun PlayerBar(player: MusicPlayer, setVolume: (Int) -> Unit, s: PlayerUiState, haze: HazeState, nowPlaying: Boolean, setNowPlaying: (Boolean) -> Unit, modifier: Modifier) {
    val position by player.position.collectAsState()
    var volume by remember { mutableStateOf(100f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(modifier.glass(haze, RoundedCornerShape(0.dp), tint = 0.66f).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = s.current != null) { setNowPlaying(!nowPlaying) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.current != null) {
                Cover(s.current!!.thumbnailUrl, 56.dp, RoundedCornerShape(12.dp))
                Column {
                    Text(s.current!!.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    Text(s.current!!.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundButton(Icons.Shuffle, if (s.shuffle) Palette.accent else Palette.ink2, 40) { player.cycleShuffle() }
                RoundButton(Icons.Prev, Palette.ink, 40) { player.previous() }
                Box(Modifier.size(46.dp).clip(CircleShape).background(Palette.accent).clickable { player.toggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (s.wantsPlay) Icons.Pause else Icons.Play, if (s.wantsPlay) "Pause" else "Play", Modifier.size(28.dp), tint = Palette.onAccent)
                }
                RoundButton(Icons.Next, Palette.ink, 40) { player.next() }
                RoundButton(Icons.Repeat, if (s.repeatMode != RepeatMode.OFF) Palette.accent else Palette.ink2, 40) { player.cycleRepeat() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shown = dragging ?: position.toFloat()
                Text(clock(shown.toLong()), color = Palette.ink2, fontSize = 12.sp)
                Track(
                    shown.coerceIn(0f, s.durationMs.toFloat().coerceAtLeast(1f)), s.durationMs.toFloat().coerceAtLeast(1f), Palette.accent, { dragging = it },
                    { dragging?.let { player.seekTo(it.toLong()) }; dragging = null }, Modifier.weight(1f),
                )
                Text(clock(s.durationMs), color = Palette.ink2, fontSize = 12.sp)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Lyrics, if (nowPlaying) Palette.accent else Palette.ink2, 40) { if (s.current != null) setNowPlaying(!nowPlaying) }
            RoundButton(Icons.Queue, if (nowPlaying) Palette.accent else Palette.ink2, 40) { if (s.current != null) setNowPlaying(!nowPlaying) }
            Icon(Icons.Volume, "Volume", Modifier.size(22.dp), tint = Palette.ink2)
            Track(volume, 100f, Palette.ink2, { volume = it; setVolume(it.toInt()) }, {}, Modifier.width(110.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, tint: Color, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = tint) }
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
