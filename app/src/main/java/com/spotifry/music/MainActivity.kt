package com.spotifry.music

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.AddToPlaylistSheet
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.MenuAction
import com.spotifry.music.ui.QueueSheet
import com.spotifry.music.ui.SongMenuSheet
import com.spotifry.music.ui.SleepTimerSheet
import com.spotifry.music.player.SleepState
import com.spotifry.music.player.clockText
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.MiniPlayer
import com.spotifry.music.ui.home.HomeScreen
import com.spotifry.music.ui.home.HomeViewModel
import com.spotifry.music.ui.home.SearchScreen
import com.spotifry.music.ui.settings.SettingsScreen
import com.spotifry.music.ui.settings.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.ui.nowplaying.NowPlayingContent
import com.spotifry.music.ui.nowplaying.NowPlayingViewModel
import com.spotifry.music.ui.playlists.NameDialog
import com.spotifry.music.ui.playlists.PlaylistDetailScreen
import com.spotifry.music.ui.playlists.PlaylistsScreen
import com.spotifry.music.ui.playlists.PlaylistsViewModel
import com.spotifry.music.ui.playlists.ImportViewModel
import com.spotifry.music.data.SpotifyImport
import com.spotifry.music.data.LyricsSource
import com.spotifry.music.ui.saved.SavedScreen
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.ui.saved.SavedViewModel
import com.spotifry.music.ui.theme.MusicTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

object Routes {
    const val LISTEN = "listen"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EQUALIZER = "settings/equalizer"
    const val SAVED = "saved"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST = "playlist/{id}"
    fun playlist(id: Long) = "playlist/$id"
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)
private val dests = listOf(
    Dest(Routes.LISTEN, "Home", Icons.Home),
    Dest(Routes.SEARCH, "Search", Icons.Search),
    Dest(Routes.SAVED, "Saved", Icons.Saved),
    Dest(Routes.PLAYLISTS, "Playlists", Icons.List),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MusicApp).container
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            val accent by container.settings.accent.collectAsStateWithLifecycle()
            val accentColor by container.settings.accentColor.collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current
            val colors = androidx.compose.runtime.remember(accent, accentColor) {
                com.spotifry.music.ui.theme.accentFor(com.spotifry.music.ui.theme.AccentChoice.of(accent), accentColor, context)
            }
            val black by container.settings.pureBlack.collectAsStateWithLifecycle()
            MusicTheme(theme, colors, black) { AppShell(container) }
        }
    }
}

private data class MenuTarget(val song: Song, val playlistId: Long? = null, val fromPlayer: Boolean = false)

@Composable
fun AppShell(c: AppContainer) {
    val nav = rememberNavController()
    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route

    val home: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(c.source, c.db.history(), c.db.songs(), c.player, c.recentSearches, c.db.plays().observeMostPlayed(5), c.localMusic.songs) } })
    val savedVm: SavedViewModel = viewModel(factory = viewModelFactory { initializer { SavedViewModel(c.db.songs(), c.downloads, c.player, c.localMusic) } })
    val plVm: PlaylistsViewModel = viewModel(factory = viewModelFactory { initializer { PlaylistsViewModel(c.db.playlists(), c.db.songs(), c.player, com.spotifry.music.data.PlaylistOrders(c.prefs)) } })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer {
        SettingsViewModel(c.settings, c.recentSearches, c.db.history(), c.db.plays(), c.db.songs(), java.io.File(c.filesDir, "songs"), c.imageClient.cache, c.songCache)
    } })
    val importVm: ImportViewModel = viewModel(factory = viewModelFactory { initializer { ImportViewModel({ SpotifyImport.fetch(c.client, it) }, c.source, c.db.playlists(), c.db.songs()) } })
    val npVm: NowPlayingViewModel = viewModel(factory = viewModelFactory { initializer { NowPlayingViewModel(c.player, c.downloads, c.db.songs(), LyricsSource(c.client)) } })

    val player by c.player.state.collectAsStateWithLifecycle()
    // not read here, only inside the progress bars and lyrics, so its ticks don't redraw the whole app
    val positionState = c.player.position.collectAsStateWithLifecycle()
    val position = remember { { positionState.value } }
    val saved by savedVm.saved.collectAsStateWithLifecycle()
    val playlists by plVm.playlists.collectAsStateWithLifecycle()
    val save by npVm.save.collectAsStateWithLifecycle()
    val lyrics by npVm.lyrics.collectAsStateWithLifecycle()
    val sleep by c.sleepTimer.state.collectAsStateWithLifecycle()
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pitch by c.settings.pitch.collectAsStateWithLifecycle()
    val speedLabel = com.spotifry.music.data.speedPitchLabel(speed, pitch)

    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    var newFor by remember { mutableStateOf<Song?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(showPlayer) { if (!showPlayer) showLyrics = false }
    val haze = remember { HazeState() }
    val snackbar = remember { SnackbarHostState() }
    // at most once a day: is a newer Spotifry out on GitHub?
    LaunchedEffect(Unit) { c.updates.checkDaily() }
    LaunchedEffect(Unit) {
        c.downloads.failures.collect { snackbar.showSnackbar("Couldn't save ${it.title}. Check your connection, then try again.") }
    }
    LaunchedEffect(Unit) {
        c.events.failed.collect { snackbar.showSnackbar("Couldn't load $it. Check your connection, then try again.") }
    }

    // the first time something plays, ask once for permission to show the lock-screen controls
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(player.current != null) { if (player.current != null) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(player.current == null) { if (player.current == null) showPlayer = false }

    val inset = if (player.current != null) 80.dp else 0.dp
    val openMenu: (Song) -> Unit = { menu = MenuTarget(it) }
    val goTo: (String) -> Unit = { r ->
        nav.navigate(r) {
            popUpTo(Routes.LISTEN) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalImageClient provides c.imageClient) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        dests.forEach { d ->
                            NavigationBarItem(
                                selected = route == d.route || (d.route == Routes.PLAYLISTS && route == Routes.PLAYLIST) || (d.route == Routes.LISTEN && (route == Routes.SETTINGS || route == Routes.EQUALIZER)),
                                onClick = { goTo(d.route) },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                },
            ) { pad ->
                TopGlow()
                Box(Modifier.padding(pad).fillMaxSize()) {
                    NavHost(nav, startDestination = Routes.LISTEN, modifier = Modifier.fillMaxSize().fadeTop().hazeSource(haze)) {
                        composable(Routes.LISTEN) {
                            HomeScreen(home, openMenu, inset, onOpenSaved = { goTo(Routes.SAVED) }, onOpenPlaylists = { goTo(Routes.PLAYLISTS) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, top = {
                                val update by c.updates.state.collectAsStateWithLifecycle()
                                val later by c.updates.laterFor.collectAsStateWithLifecycle()
                                val u = update
                                // "Later" hides the card for that version only; a download in progress always shows
                                if (u != com.spotifry.music.data.UpdateState.None && !(u is com.spotifry.music.data.UpdateState.Available && u.release.version == later)) {
                                    com.spotifry.music.ui.components.UpdateCard(
                                        u, c.updates::update, { (u as? com.spotifry.music.data.UpdateState.Available)?.let { c.updates.later(it.release) } },
                                        Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                                    )
                                }
                            })
                        }
                        composable(Routes.SETTINGS) { SettingsScreen(settingsVm, { nav.popBackStack() }, inset, updates = c.updates, onOpenEqualizer = { nav.navigate(Routes.EQUALIZER) }) }
                        composable(Routes.EQUALIZER) { com.spotifry.music.ui.settings.EqualizerScreen(c.settings, c.eqInfo, c.spectrum, player.isPlaying, { nav.popBackStack() }, inset) }
                        composable(Routes.SEARCH) { SearchScreen(home, openMenu, inset) }
                        composable(Routes.SAVED) {
                            SavedScreen(savedVm, { goTo(Routes.SEARCH) }, openMenu, inset)
                        }
                        composable(Routes.PLAYLISTS) {
                            PlaylistsScreen(plVm, importVm, { nav.navigate(Routes.playlist(it)) }, { q -> home.onQueryChanged(q); goTo(Routes.SEARCH) }, inset)
                        }
                        composable(Routes.PLAYLIST, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                            PlaylistDetailScreen(plVm, e.arguments!!.getLong("id"), { nav.popBackStack() }, { s, pid -> menu = MenuTarget(s, pid) }, inset)
                        }
                    }
                    MiniPlayer(
                        player, position, haze, c.player::toggle, c.player::next, { showPlayer = true },
                        Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    )
                }
            }

            AnimatedVisibility(showPlayer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                // one handler for the whole player, added each time it opens so it is the newest and outranks the
                // NavHost's own back handling on every tab: full lyrics close first, then Now playing
                BackHandler { if (showLyrics) showLyrics = false else showPlayer = false }
                NowPlayingContent(
                    player, save,
                    onClose = { showPlayer = false }, onToggle = c.player::toggle, onNext = c.player::next, onPrev = c.player::previous,
                    onSeek = c.player::seekTo, onShuffle = { c.player.setShuffle(!player.shuffle) }, onRepeat = c.player::cycleRepeat,
                    onSave = npVm::onSave, onQueue = { showQueue = true }, onMore = { player.current?.let { menu = MenuTarget(it, fromPlayer = true) } },
                    saved = saved.map { it.id }.toSet(), onPick = c.player::skipTo,
                    lyrics = lyrics, onRetryLyrics = npVm::retryLyrics, showLyrics = showLyrics, onShowLyrics = { showLyrics = it },
                    position = position,
                    sleep = sleep, sleepLeft = c.sleepTimer::remainingMs, onSleep = { showSleep = true },
                    speedLabel = speedLabel, onSpeed = { showSpeed = true },
                )
            }
        }

        menu?.let { target ->
            val isSaved = saved.any { it.id == target.song.id }
            val actions = buildList {
                add(MenuAction("Add to playlist", Icons.Plus) { addTo = target.song })
                if (!target.song.isLocal) add(if (isSaved) MenuAction("Remove from saved", Icons.Trash) { savedVm.remove(target.song.id) } else MenuAction("Save on this phone", Icons.Download) { savedVm.save(target.song) })
                target.playlistId?.let { pid -> add(MenuAction("Remove from this playlist", Icons.Trash) { plVm.removeSong(pid, target.song.id) }) }
                if (target.fromPlayer) {
                    val left = when (sleep) {
                        is SleepState.Minutes -> " · ${clockText(c.sleepTimer.remainingMs() ?: 0)} left"
                        SleepState.EndOfSong -> " · after this song"
                        SleepState.Off -> ""
                    }
                    add(MenuAction("Sleep timer$left", Icons.Clock) { showSleep = true })
                    add(MenuAction("Speed and pitch" + (speedLabel?.let { " · $it" } ?: ""), Icons.Speed) { showSpeed = true })
                }
            }
            SongMenuSheet(target.song, actions) { menu = null }
        }
        addTo?.let { song ->
            AddToPlaylistSheet(playlists, { plVm.addSong(it, song) }, { newFor = song }) { addTo = null }
        }
        newFor?.let { song -> NameDialog("New playlist", "Create", "", { newFor = null }) { plVm.create(it, thenAdd = song); newFor = null } }
        if (showSleep) SleepTimerSheet(
            sleep, c.sleepTimer::remainingMs, onMinutes = c.sleepTimer::start, onEndOfSong = c.sleepTimer::startEndOfSong,
            onOff = c.sleepTimer::off, onDismiss = { showSleep = false },
        )
        if (showSpeed) com.spotifry.music.ui.SpeedPitchSheet(speed, pitch, c.settings::setSpeed, c.settings::setPitch) { showSpeed = false }
        if (showQueue) QueueSheet(player, saved.map { it.id }.toSet(), c.player::skipTo) { showQueue = false }
    }
}

/** A soft wash of the accent in the top right corner, behind the status bar and the screen title. */
/** Fades the page out over its top 16 dp, so content scrolled up softens away under the status bar instead of being cut flat. */
private fun Modifier.fadeTop() = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black), endY = 16.dp.toPx(),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

@Composable
private fun TopGlow() {
    // light mode uses the brighter orange and more of it, so the glow reads about as strongly as on the dark background
    val glow = com.spotifry.music.ui.theme.LocalAccent.current.dark.copy(alpha = if (com.spotifry.music.ui.theme.isDark()) .30f else .40f)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(glow, androidx.compose.ui.graphics.Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * .85f, 0f), radius = size.width * .9f,
                ),
            )
        },
    )
}
