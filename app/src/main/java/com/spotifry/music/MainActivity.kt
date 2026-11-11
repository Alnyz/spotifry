package com.spotifry.music

import androidx.lifecycle.createSavedStateHandle
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import com.spotifry.music.ui.FadeBarSpec
import com.spotifry.music.ui.SlideOverlay
import com.spotifry.music.ui.FadeBar
import com.spotifry.music.ui.BarTab
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
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
import android.net.Uri
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.home.PageActions
import com.spotifry.music.ui.home.MOODS
import com.spotifry.music.ui.home.Mood
import com.spotifry.music.ui.home.MoodScreen
import com.spotifry.music.ui.home.MoodViewModel
import com.spotifry.music.ui.home.PageScreen
import com.spotifry.music.ui.home.PageViewModel
import com.spotifry.music.ui.AddToPlaylistSheet
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.MenuAction
import com.spotifry.music.ui.QueueSheet
import com.spotifry.music.ui.SongMenuSheet
import com.spotifry.music.ui.SleepTimerSheet
import com.spotifry.music.player.SleepState
import com.spotifry.music.player.clockText
import com.spotifry.music.player.Trim
import com.spotifry.music.player.canEndAt
import com.spotifry.music.player.canStartAt
import com.spotifry.music.player.clipText
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.goToTab
import com.spotifry.music.ui.components.MiniPlayer
import com.spotifry.music.ui.home.HomeScreen
import com.spotifry.music.ui.home.HomeViewModel
import com.spotifry.music.ui.home.SearchScreen
import com.spotifry.music.ui.settings.SettingsScreen
import com.spotifry.music.ui.settings.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.ui.nowplaying.KeepScreenOn
import com.spotifry.music.ui.nowplaying.NowPlayingContent
import com.spotifry.music.player.hasOutputPicker
import com.spotifry.music.player.showOutputPicker
import com.spotifry.music.ui.nowplaying.NowPlayingViewModel
import com.spotifry.music.ui.playlists.NameDialog
import com.spotifry.music.ui.playlists.PlaylistDetailScreen
import com.spotifry.music.ui.playlists.PlaylistsScreen
import com.spotifry.music.ui.playlists.PlaylistsViewModel
import com.spotifry.music.ui.playlists.ImportViewModel
import com.spotifry.music.data.SpotifyImport
import com.spotifry.music.data.ShareText
import com.spotifry.music.ui.share.ImportSharedPage
import com.spotifry.music.ui.share.MakePicturePage
import com.spotifry.music.ui.share.SharePlaylistSheet
import com.spotifry.music.ui.share.ShareSongSheet
import com.spotifry.music.ui.share.ShareTarget
import kotlinx.coroutines.launch
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
    const val PAGE = "page?type={type}&url={url}&name={name}&by={by}&art={art}"
    fun page(l: PageLink) = "page?type=${l.type.name}&url=${Uri.encode(l.url)}&name=${Uri.encode(l.name)}&by=${Uri.encode(l.by)}&art=${Uri.encode(l.thumbnailUrl.orEmpty())}"
    const val MOOD = "mood?name={name}"
    const val LISTENING = "listening"
    const val SPOTIFRIED = "spotifried"
    fun mood(m: Mood) = "mood?name=${Uri.encode(m.name)}"
}

private val barTabs = listOf(
    BarTab(Routes.LISTEN, "Home", Icons.Home),
    BarTab(Routes.SEARCH, "Search", Icons.Search),
    BarTab(Routes.SAVED, "Saved", Icons.Saved),
    BarTab(Routes.PLAYLISTS, "Playlists", Icons.List),
)

class MainActivity : ComponentActivity() {
    /** Text another app shared with Spotifry, until the app has read it. */
    private val shared = mutableStateOf<String?>(null)

    private fun sharedText(intent: Intent?) = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedText(intent)?.let { shared.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // only on a fresh start: after turning the phone the same message would be read again
        if (savedInstanceState == null) shared.value = sharedText(intent)
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
            MusicTheme(theme, colors, black) { AppShell(container, shared.value) { shared.value = null } }
        }
    }
}

/** [queueIndex]: where the song sits in the queue, for a suggestion's "Not this song". */
private data class MenuTarget(val song: Song, val playlistId: Long? = null, val fromPlayer: Boolean = false, val queueIndex: Int? = null)

@Composable
fun AppShell(c: AppContainer, sharedText: String? = null, onSharedRead: () -> Unit = {}) {
    val nav = rememberNavController()
    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route

    val home: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(c.source, c.db.history(), c.db.songs(), c.player, c.recentSearches, c.db.plays().observeMostPlayed(5), c.localMusic.songs, c::warm) } })
    val savedVm: SavedViewModel = viewModel(factory = viewModelFactory { initializer { SavedViewModel(c.db.songs(), c.downloads, c.player, c.localMusic, c.db.history(), c.restoreQueue) } })
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val backupActions = com.spotifry.music.ui.settings.rememberBackupActions(viewModel(factory = viewModelFactory { initializer {
        com.spotifry.music.ui.settings.BackupViewModel(c.backups, { appContext.contentResolver.openOutputStream(it, "wt") }, { appContext.contentResolver.openInputStream(it) }) {
            // Settings reads its values once, so the restored ones need a fresh start
            appContext.startActivity(android.content.Intent.makeRestartActivityTask(android.content.ComponentName(appContext, MainActivity::class.java)))
            Runtime.getRuntime().exit(0)
        }
    } }))
    val plVm: PlaylistsViewModel = viewModel(factory = viewModelFactory { initializer { PlaylistsViewModel(c.db.playlists(), c.db.songs(), c.player, com.spotifry.music.data.PlaylistOrders(c.prefs)) } })
    val listeningRow: com.spotifry.music.ui.listening.ListeningRowViewModel = viewModel(factory = viewModelFactory { initializer { com.spotifry.music.ui.listening.ListeningRowViewModel(c.db.listens()) } })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer {
        SettingsViewModel(c.settings, c.recentSearches, c.db.history(), c.db.plays(), c.db.listens(), c.db.songs(), java.io.File(c.filesDir, "songs"), c.imageClient.cache, c.songCache, c.resolver::clear)
    } })
    val importVm: ImportViewModel = viewModel(factory = viewModelFactory { initializer { ImportViewModel({ SpotifyImport.fetch(c.client, it) }, c.source, c.db.playlists(), c.db.songs()) } })
    val artistsVm: com.spotifry.music.ui.home.ArtistsHomeViewModel = viewModel(factory = viewModelFactory { initializer { com.spotifry.music.ui.home.ArtistsHomeViewModel(c.settings, com.spotifry.music.data.ArtistSongs(c.source)) } })
    val npVm: NowPlayingViewModel = viewModel(factory = viewModelFactory { initializer { NowPlayingViewModel(c.player, c.downloads, c.db.songs(), c.lyrics) } })

    val player by c.player.state.collectAsStateWithLifecycle()
    // not read here, only inside the progress bars and lyrics, so its ticks don't redraw the whole app
    val positionState = c.player.position.collectAsStateWithLifecycle()
    val position = remember { { positionState.value } }
    val saved by savedVm.saved.collectAsStateWithLifecycle()
    val playlists by plVm.playlists.collectAsStateWithLifecycle()
    val save by npVm.save.collectAsStateWithLifecycle()
    val keepScreenOn by c.settings.keepScreenOn.collectAsStateWithLifecycle()
    val lyrics by npVm.lyrics.collectAsStateWithLifecycle()
    val sleep by c.sleepTimer.state.collectAsStateWithLifecycle()
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pitch by c.settings.pitch.collectAsStateWithLifecycle()
    val speedLabel = com.spotifry.music.data.speedPitchLabel(speed, pitch)

    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    var newFor by remember { mutableStateOf<Song?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    // "Pick artists for Home" on an empty Home: each tap counts up, so the screen opens fresh
    var pickArtists by remember { mutableStateOf(0) }
    var pickingOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickArtists) { if (pickArtists > 0) pickingOpen = true }
    var shareSong by remember { mutableStateOf<Song?>(null) }
    var sharePlaylist by remember { mutableStateOf<ShareTarget?>(null) }
    var picture by remember { mutableStateOf<ShareTarget?>(null) }
    var incoming by remember { mutableStateOf<ShareText.Shared?>(null) }
    LaunchedEffect(showPlayer) { if (!showPlayer) showLyrics = false }
    val haze = remember { HazeState() }
    val snackbar = remember { SnackbarHostState() }
    // at most once a day: is a newer Spotifry out on GitHub?
    LaunchedEffect(Unit) { c.updates.checkDaily() }
    LaunchedEffect(Unit) {
        c.downloads.failures.collect { snackbar.showSnackbar("Couldn't save ${it.title}. Check your connection, then try again.") }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** Sets a song's trim at once and offers Undo, which puts [old] back. */
    fun trimWithUndo(id: String, trim: Trim?, old: Trim?, message: String, skip: Boolean = false) {
        c.player.setTrim(id, trim, skip)
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            if (snackbar.showSnackbar(message, "Undo", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) c.player.setTrim(id, old)
        }
    }
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        val got = ShareText.read(text)
        // its own scope: marking the text read restarts this effect, which would cut the message short
        if (got == null) scope.launch { snackbar.showSnackbar("Nothing to import in that message.") } else incoming = got
        onSharedRead()
    }
    LaunchedEffect(Unit) {
        c.events.failed.collect { snackbar.showSnackbar("Couldn't load $it. Check your connection, then try again.") }
    }

    // the first time something plays, ask once for permission to show the lock-screen controls
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(player.current != null) { if (player.current != null && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(player.current == null) { if (player.current == null) showPlayer = false }

    // the bar fades over the page, so every list keeps this much clear at its end (164 dp playing, 104 dp not, on a 16 dp gesture area)
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val mini = com.spotifry.music.ui.miniPlayerShown(route, player.current != null)
    val inset = FadeBarSpec.reserve(mini, navInset)
    val suggestionMenu: (Int) -> Unit = { i -> player.queue.getOrNull(i)?.let { menu = MenuTarget(it, queueIndex = i) } }
    val openMenu: (Song) -> Unit = { menu = MenuTarget(it) }
    val context = LocalContext.current
    val canPickOutput = remember { hasOutputPicker(context) }
    val pageActions = remember {
        PageActions(
            open = { nav.navigate(Routes.page(it)) },
            save = { link, songs ->
                scope.launch {
                    val list = songs ?: runCatching { c.source.page(link).songs }.getOrDefault(emptyList())
                    if (list.isEmpty()) { snackbar.showSnackbar("Couldn't load ${link.name}. Check your connection, then try again."); return@launch }
                    plVm.createWith(link.name, list) { id, name ->
                        scope.launch {
                            if (snackbar.showSnackbar("Saved as playlist \"$name\"", "Open", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) nav.navigate(Routes.playlist(id))
                        }
                    }
                }
            },
            share = { com.spotifry.music.ui.share.sendText(context, "${it.name}\n${it.url}") },
        )
    }
    val goTo: (String) -> Unit = { r -> nav.goToTab(r) }

    // the first launch covers everything, mini player included; an update never shows it (see isFreshInstall)
    val firstSeen by c.settings.firstLaunchSeen.collectAsStateWithLifecycle()
    // kept through a rotation: the answer must not change once the screens' own import has made a playlist
    var fresh by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Boolean?>(if (firstSeen) false else null) }
    LaunchedEffect(Unit) {
        if (!firstSeen && fresh == null) {
            // a failed check counts as "not fresh": nobody is shown first launch by mistake
            val isFresh = try { com.spotifry.music.data.isFreshInstall(com.spotifry.music.data.neverUpdated(context), c.db.history(), c.db.songs(), c.db.playlists()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            fresh = isFresh
            if (!isFresh) c.settings.markFirstLaunchSeen()
        }
    }
    val plCovers by plVm.covers.collectAsStateWithLifecycle()
    val covered = (!firstSeen && fresh != false) || (pickArtists > 0 && pickingOpen)

    CompositionLocalProvider(LocalImageClient provides c.imageClient) {
        Box(Modifier.fillMaxSize()) {
            // under the first-launch screens the app is not drawn, so touches and screen readers can't reach it
            if (!covered) Scaffold(
                // the Spotifried story is dark on either theme, up under the status bar too
                containerColor = if (route == Routes.SPOTIFRIED) com.spotifry.music.ui.theme.StoryBlack else MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = FadeBarSpec.solid(mini, navInset))) },
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            ) { pad ->
                if (route != Routes.SPOTIFRIED) TopGlow()
                Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                    val inLists by remember { c.db.playlists().observeSongIdsInPlaylists().map { it.toSet() } }.collectAsStateWithLifecycle(emptySet())
                    CompositionLocalProvider(
                        com.spotifry.music.ui.components.LocalNowPlaying provides com.spotifry.music.ui.components.NowPlayingMark(player.current?.id, player.isPlaying) { showPlayer = true },
                        com.spotifry.music.ui.components.LocalInPlaylist provides inLists,
                    ) {
                    NavHost(nav, startDestination = Routes.LISTEN, modifier = Modifier.fillMaxSize().imePadding().fadeTop().hazeSource(haze)) {
                        composable(Routes.LISTEN) {
                            LaunchedEffect(Unit) { artistsVm.refresh() }
                            val artistsHome by artistsVm.state.collectAsStateWithLifecycle()
                            val homePlaylists by plVm.playlists.collectAsStateWithLifecycle()
                            val homePlaylistsRead by plVm.playlistsRead.collectAsStateWithLifecycle()
                            val homeListensRead by listeningRow.anyRead.collectAsStateWithLifecycle()
                            HomeScreen(home, openMenu, inset, player = player, onToggle = c.player::toggle, onPause = c.player::pause, onOpenPlayer = { showPlayer = true }, playlists = homePlaylists, libraryRead = homePlaylistsRead && homeListensRead, covers = plCovers, onOpenPlaylist = { nav.navigate(Routes.playlist(it)) }, onPlayPlaylist = plVm::playAll, onSearch = { home.onQueryChanged(""); goTo(Routes.SEARCH) }, onImport = {
                                // not restored: a playlist left open on that tab would hide the sheet, which lives on the tab's first page
                                importVm.open()
                                nav.navigate(Routes.PLAYLISTS) { popUpTo(Routes.LISTEN) { saveState = true }; launchSingleTop = true }
                            }, onMood = { nav.navigate(Routes.mood(it)) { launchSingleTop = true } }, artists = artistsHome, onOpenArtist = { nav.navigate(Routes.page(it.link)) }, onPickArtists = { pickArtists++ }, onOpenSaved = { goTo(Routes.SAVED) }, onOpenPlaylists = { goTo(Routes.PLAYLISTS) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, listening = {
                                com.spotifry.music.ui.listening.ListeningRow(listeningRow, player.isPlaying && !showPlayer, { nav.navigate(Routes.LISTENING) { launchSingleTop = true } }, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                            }, top = {
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
                        composable(Routes.LISTENING) {
                            val vm: com.spotifry.music.ui.listening.ListeningViewModel = viewModel(factory = viewModelFactory { initializer { com.spotifry.music.ui.listening.ListeningViewModel(c.db.listens()) } })
                            val savedIds by home.savedIds.collectAsStateWithLifecycle()
                            com.spotifry.music.ui.listening.ListeningScreen(vm, savedIds, player.isPlaying && !showPlayer, { nav.popBackStack() }, plVm::play, openMenu, { name -> home.onQueryChanged(name); goTo(Routes.SEARCH) }, { nav.navigate(Routes.SPOTIFRIED) { launchSingleTop = true } }, inset)
                        }
                        composable(Routes.SPOTIFRIED) {
                            val vm: com.spotifry.music.ui.spotifried.SpotifriedViewModel = viewModel(factory = viewModelFactory { initializer { com.spotifry.music.ui.spotifried.SpotifriedViewModel(c.db.listens().observeAll(), c.db.songYears().observeAll(), name = c.settings.name) } })
                            LaunchedEffect(Unit) { com.spotifry.music.data.spotifried.enqueueYearLookup(context) }
                            com.spotifry.music.ui.spotifried.SpotifriedScreen(vm, { nav.popBackStack() }, inset)
                        }
                        composable(Routes.SETTINGS) { SettingsScreen(settingsVm, { nav.popBackStack() }, inset, updates = c.updates, onOpenEqualizer = { nav.navigate(Routes.EQUALIZER) }, backup = backupActions) }
                        composable(Routes.EQUALIZER) { com.spotifry.music.ui.settings.EqualizerScreen(c.settings, c.spectrum, c.autoEq, player.isPlaying, { nav.popBackStack() }, inset) }
                        composable(Routes.SEARCH) { SearchScreen(home, openMenu, inset, pageActions, onMood = { nav.navigate(Routes.mood(it)) { launchSingleTop = true } }) }
                        composable(Routes.MOOD, listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" })) { e ->
                            // a name no tile has (saved state after a rename): nothing to show, back to Search
                            val mood = MOODS.firstOrNull { it.name == e.arguments!!.getString("name") }
                            if (mood == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
                            val vm: MoodViewModel = viewModel(factory = viewModelFactory { initializer { MoodViewModel(c.source, mood, home.moodLists, c::streamLow) } })
                            MoodScreen(vm, { nav.popBackStack() }, pageActions, inset)
                        }
                        composable(Routes.PAGE, listOf("type", "url", "name", "by", "art").map { navArgument(it) { type = NavType.StringType; defaultValue = "" } }) { e ->
                            val a = e.arguments!!
                            val link = PageLink(SearchType.valueOf(a.getString("type")!!), a.getString("url")!!, a.getString("name")!!, a.getString("by")!!, a.getString("art")!!.ifEmpty { null })
                            val vm: PageViewModel = viewModel(factory = viewModelFactory { initializer { PageViewModel(c.source, link, c::warm) } })
                            val savedIds by home.savedIds.collectAsStateWithLifecycle()
                            PageScreen(vm, savedIds, { nav.popBackStack() }, plVm::play, plVm::shuffle, openMenu, pageActions, inset)
                        }
                        composable(Routes.SAVED) {
                            SavedScreen(savedVm, { goTo(Routes.SEARCH) }, openMenu, inset)
                        }
                        composable(Routes.PLAYLISTS) {
                            PlaylistsScreen(plVm, importVm, { nav.navigate(Routes.playlist(it)) }, { q -> home.onQueryChanged(q); goTo(Routes.SEARCH) }, inset, onShared = { incoming = it })
                        }
                        composable(Routes.PLAYLIST, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                            CompositionLocalProvider(com.spotifry.music.ui.components.LocalInPlaylist provides emptySet()) { PlaylistDetailScreen(plVm, e.arguments!!.getLong("id"), { nav.popBackStack() }, { s, pid -> menu = MenuTarget(s, pid) }, inset, onShare = { name, songs -> sharePlaylist = ShareTarget.of(name, songs) }) }
                        }
                    }
                    }
                    val bar = @Composable {
                        FadeBar(barTabs, route, goTo, mini, Modifier.align(Alignment.BottomCenter)) {
                            MiniPlayer(player, position, haze, c.player::toggle, c.player::next, { showPlayer = true })
                        }
                    }
                    // under the story the bar fades into its black, not into a light page on the light theme
                    if (route == Routes.SPOTIFRIED) com.spotifry.music.ui.theme.AlwaysDark(com.spotifry.music.ui.theme.StoryBlack, systemBars = false) { bar() } else bar()
                }
            }

            SlideOverlay(showPlayer) {
                // one handler for the whole player, added each time it opens so it is the newest and outranks the
                // NavHost's own back handling on every tab: full lyrics close first, then Now playing
                BackHandler(enabled = showPlayer) { if (showLyrics) showLyrics = false else showPlayer = false }
                KeepScreenOn(keepScreenOn && player.wantsPlay)
                NowPlayingContent(
                    player, save,
                    onClose = { showPlayer = false }, onToggle = c.player::toggle, onNext = c.player::next, onPrev = c.player::previous,
                    onSeek = c.player::seekTo, onShuffle = c.player::cycleShuffle, onRepeat = c.player::cycleRepeat,
                    onSave = npVm::onSave, onQueue = { showQueue = true }, onMore = { player.current?.let { menu = MenuTarget(it, fromPlayer = true, queueIndex = player.currentIndex) } },
                    saved = saved.map { it.id }.toSet(), onPick = c.player::skipTo,
                    lyrics = lyrics, onRetryLyrics = npVm::retryLyrics, showLyrics = showLyrics, onShowLyrics = { showLyrics = it },
                    position = position,
                    sleep = sleep, sleepLeft = c.sleepTimer::remainingMs, onSleep = { showSleep = true },
                    speedLabel = speedLabel, onSpeed = { showSpeed = true }, speed = speed,
                    onSuggestionMore = suggestionMenu, onTrim = { showTrim = true },
                )
                // the app's own snackbar sits under Now playing; this one shows the same message on top of it
                Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
            }
            // after Now playing, so a picture made from its menu opens on top of it
            incoming?.let { got ->
                ImportSharedPage(
                    got, onPlay = { songs, i, name -> c.player.playList(songs, i, name); incoming = null },
                    onAdd = { name -> plVm.createWith(name, got.songs) { id, _ -> incoming = null; showPlayer = false; nav.navigate(Routes.playlist(id)) } },
                ) { incoming = null }
            }
            // lyric times count from the whole song's start, the player from the trim's
            picture?.let { t -> MakePicturePage(t, c.lyrics, player.current?.id == t.songs.firstOrNull()?.id && !t.playlist, { position() + (player.trim?.startMs ?: 0) }) { picture = null } }
            if (!firstSeen && fresh == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            if (!firstSeen && fresh == true) {
                val vm: com.spotifry.music.ui.firstlaunch.FirstLaunchViewModel = viewModel(key = "first-launch", factory = viewModelFactory { initializer { com.spotifry.music.ui.firstlaunch.FirstLaunchViewModel(c.source, c.settings, saved = createSavedStateHandle()) } })
                com.spotifry.music.ui.firstlaunch.FirstLaunchScreen(vm, importVm, plCovers, onRestore = backupActions.restore)
            } else if (pickArtists > 0 && pickingOpen) {
                val vm: com.spotifry.music.ui.firstlaunch.FirstLaunchViewModel = viewModel(key = "pick-artists-$pickArtists", factory = viewModelFactory { initializer { com.spotifry.music.ui.firstlaunch.FirstLaunchViewModel(c.source, c.settings, reopen = true, saved = createSavedStateHandle()) } })
                com.spotifry.music.ui.firstlaunch.FirstLaunchScreen(vm, importVm, plCovers, onClose = { pickingOpen = false })
            }
        }

        menu?.let { target ->
            val isSaved = saved.any { it.id == target.song.id }
            val actions = buildList {
                add(MenuAction("Add to playlist", Icons.Plus) { addTo = target.song })
                if (!target.song.isLocal) add(if (isSaved) MenuAction("Remove from saved", Icons.Trash) { savedVm.remove(target.song.id) } else MenuAction("Save on this phone", Icons.Download) { savedVm.save(target.song) })
                add(MenuAction("Share", Icons.Share) { shareSong = target.song })
                target.playlistId?.let { pid -> add(MenuAction("Remove from this playlist", Icons.Trash) { plVm.removeSong(pid, target.song.id) }) }
                if (target.fromPlayer) {
                    val left = when (sleep) {
                        is SleepState.Minutes -> " · ${clockText(c.sleepTimer.remainingMs() ?: 0)} left"
                        SleepState.EndOfSong -> " · after this song"
                        SleepState.Off -> ""
                    }
                    if (canPickOutput) add(MenuAction("Change output", Icons.Output) {
                        if (!showOutputPicker(context)) scope.launch { snackbar.showSnackbar("Can't open the output choice here") }
                    })
                    add(MenuAction("Keep screen on", Icons.ScreenOn, switch = keepScreenOn) { c.settings.setKeepScreenOn(!keepScreenOn) })
                    add(MenuAction("Sleep timer$left", Icons.Clock) { showSleep = true })
                    add(MenuAction("Speed and pitch" + (speedLabel?.let { " · $it" } ?: ""), Icons.Speed) { showSpeed = true })
                    // Trim: only here, as setting a point needs the song playing. Once trimmed, the sheet moves the points.
                    val s = target.song
                    val old = player.trim
                    val at = position() + (old?.startMs ?: 0)
                    if (old == null) {
                        if (canStartAt(at, player.wholeMs)) add(MenuAction("Start this song here · ${clipText(at)}", Icons.StartHere, lineAbove = true) {
                            trimWithUndo(s.id, Trim(at), null, "Starts at ${clipText(at)} from now on")
                        })
                        if (canEndAt(at)) add(MenuAction("End this song here", Icons.EndHere, lineAbove = !canStartAt(at, player.wholeMs)) {
                            trimWithUndo(s.id, Trim(endMs = at), null, "Ends at ${clipText(at)} from now on", skip = true)
                        })
                    } else {
                        add(MenuAction("Adjust trim", Icons.Cut, lineAbove = true) { showTrim = true })
                        add(MenuAction("Play the whole song", Icons.Whole) { trimWithUndo(s.id, null, old, "Plays the whole song again") })
                    }
                }
                val at = target.queueIndex
                if (target.song.suggested && at != null) add(MenuAction("Not this song", Icons.None, lineAbove = true) {
                    // out of the queue (the playing one skips to the next) and never suggested again
                    val s = target.song
                    val upcoming = player.order?.drop(1)
                    c.setNotSuggested(s.id, true)
                    c.player.remove(at, s.id)
                    scope.launch {
                        if (snackbar.showSnackbar("Won't suggest this song again", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            c.setNotSuggested(s.id, false)
                            c.player.insert(at, s, upcoming)
                        }
                    }
                })
            }
            SongMenuSheet(target.song, actions, player.trim?.takeIf { target.fromPlayer }?.note(player.wholeMs)?.let { if (c.trims[target.song.id] == null) "smart trim · $it" else it }) { menu = null }
        }
        addTo?.let { song ->
            val holding by remember(song.id) { plVm.playlistsWith(song.id) }.collectAsStateWithLifecycle(emptySet())
            AddToPlaylistSheet(song.title, playlists, holding, { p, has ->
                snackbar.currentSnackbarData?.dismiss()
                if (!has) {
                    plVm.addSong(p.id, song)
                    scope.launch { snackbar.showSnackbar("Added to \"${p.name}\"", duration = androidx.compose.material3.SnackbarDuration.Short) }
                } else scope.launch {
                    val row = plVm.takeOut(p.id, song.id) ?: return@launch
                    if (snackbar.showSnackbar("Removed from \"${p.name}\"", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) plVm.putBack(row)
                }
            }, { newFor = song }) { addTo = null }
        }
        newFor?.let { song -> NameDialog("New playlist", "Create", "", { newFor = null }) { plVm.create(it, thenAdd = song); newFor = null } }
        if (showSleep) SleepTimerSheet(
            sleep, c.sleepTimer::remainingMs, onMinutes = c.sleepTimer::start, onEndOfSong = c.sleepTimer::startEndOfSong,
            onOff = c.sleepTimer::off, onDismiss = { showSleep = false },
        )
        if (showTrim) player.current?.let { s ->
            val old = player.trim
            com.spotifry.music.ui.TrimSheet(
                s, old, player.wholeMs, { position() + (player.trim?.startMs ?: 0) }, c.player::listen,
                onSave = { t ->
                    if (t != old) trimWithUndo(s.id, t, old, when {
                        t == null -> "Plays the whole song again"
                        t.startMs != (old?.startMs ?: 0) -> "Starts at ${clipText(t.startMs)} from now on"
                        else -> t.endMs?.let { "Ends at ${clipText(it)} from now on" } ?: "Plays to the end again"
                    })
                },
            ) { showTrim = false }
        }
        if (showSpeed) com.spotifry.music.ui.SpeedPitchSheet(speed, pitch, c.settings::setSpeed, c.settings::setPitch) { showSpeed = false }
        shareSong?.let { s -> ShareSongSheet(s, { picture = ShareTarget.of(s) }) { shareSong = null } }
        sharePlaylist?.let { t -> SharePlaylistSheet(t, { picture = t }) { sharePlaylist = null } }
        if (showQueue) QueueSheet(
            player, saved.map { it.id }.toSet(), c.player::skipTo, { showQueue = false }, suggestionMenu,
            onMove = c.player::move, onRemove = c.player::remove, onUndo = c.player::insert,
        )
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
