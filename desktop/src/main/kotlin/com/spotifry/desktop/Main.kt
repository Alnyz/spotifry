package com.spotifry.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the desktop app keeps its files: $XDG_DATA_HOME/spotifry, else ~/.local/share/spotifry. */
fun dataDir(): File = File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "spotifry").also { it.mkdirs() }

/** What the screens use. The real one is built by [productionServices]; tests build their own. */
class Services(
    val scope: CoroutineScope, val source: MusicSource, val library: Library, val player: MusicPlayer, val setVolume: (Int) -> Unit,
    /** Lyrics for a song; null when no source has them. */
    val lyrics: suspend (com.spotifry.music.data.source.Song) -> com.spotifry.music.data.Lyrics? = { null },
    /** Stops the player and closes the database, before the window is built again. */
    val close: () -> Unit = {},
) {
    /** Set by the window: bring it to the front (the media widget's "open"), and quit. */
    var raise: () -> Unit = {}
    var quit: () -> Unit = {}
    /** Media keys and the desktop's player widget; null without a D-Bus session. */
    var mpris: Mpris? = null

    /** Playlists brought in from Spotify or YouTube. Lives with the window, so changing pages doesn't stop an import. */
    val importer = com.spotifry.music.ui.playlists.PlaylistImport(scope, { com.spotifry.music.data.SpotifyImport.fetch(library.client, it) }, source, library.db.playlists(), library.db.songs())
}

fun productionServices(): Services {
    // idle connections are dropped after 10 s: some networks silently end them, as on the phone
    val client = OkHttpClient.Builder().connectionPool(okhttp3.ConnectionPool(5, 10, TimeUnit.SECONDS)).build()
    Covers.client = client
    NewPipe.init(OkHttpDownloader(client), Localization("en", "US"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val source = NewPipeSource()
    val resolver = StreamResolver(source, scope)
    val dir = dataDir()
    val library = Library.open(dir, client, resolver)
    // SPOTIFRY_MPV_ARGS="--ao=null" turns the sound off, for tests
    val args = System.getenv("SPOTIFRY_MPV_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    val player = MpvPlayer(resolver, scope, File(dir, "last_queue.json"), args, localFile = library::localFile, recorder = library.recorder)
    lateinit var services: Services
    services = Services(scope, source, library, player, player::setVolume, lyrics = library.lyrics::get, close = { services.mpris?.close(); player.close(); library.db.close() })
    services.mpris = Mpris.start(player, scope, player::setVolume, raise = { services.raise() }, quit = { services.quit() })
    return services
}

fun main() = application {
    // a restored backup changes every setting: the window is built again from the files
    var generation by remember { mutableStateOf(0) }
    key(generation) {
        val started = remember { runCatching { productionServices() } }
        val services = started.getOrNull()
        if (services == null) StartFailedWindow(started.exceptionOrNull())
        else MainWindow(services, restart = { services.close(); generation++ })
    }
}

@androidx.compose.runtime.Composable
private fun ApplicationScope.StartFailedWindow(error: Throwable?) {
    Window(onCloseRequest = ::exitApplication, title = "Spotifry", state = rememberWindowState(size = DpSize(640.dp, 360.dp))) {
        SpotifryTheme { StartFailed(error) }
    }
}

@androidx.compose.runtime.Composable
private fun ApplicationScope.MainWindow(services: Services, restart: () -> Unit) {
    Window(
        onCloseRequest = { services.close(); exitApplication() }, title = "Spotifry", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Logo.image),
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        LaunchedEffect(services) {
            services.raise = { window.toFront(); window.requestFocus() }
            services.quit = { services.close(); exitApplication() }
        }
        SpotifryTheme { App(services, restart = restart) }
    }
}

/** What the window says when the app can't start, in plain words with the one thing to do. */
@androidx.compose.runtime.Composable
fun StartFailed(error: Throwable?) {
    val missing = error is MpvMissing || error?.cause is MpvMissing
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSize().background(Palette.bg).padding(40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Text(if (missing) "Spotifry needs mpv" else "Spotifry couldn't start", fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        androidx.compose.material3.Text(
            if (missing) "Spotifry plays music through mpv, and mpv isn't installed. Install it with your package manager, for example:\n\n    sudo pacman -S mpv\n    sudo apt install mpv\n\nThen open Spotifry again."
            else "Something went wrong while starting: ${error?.message ?: "unknown error"}",
            color = Palette.ink2, fontSize = 15.sp, lineHeight = 22.sp, modifier = androidx.compose.ui.Modifier.padding(top = 14.dp),
        )
    }
}

/** Runs [block] when Enter is pressed in a text field. */
fun Modifier.onEnter(block: () -> Unit): Modifier = onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { block(); true } else false }
