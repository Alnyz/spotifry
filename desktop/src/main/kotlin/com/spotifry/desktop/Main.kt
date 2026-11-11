package com.spotifry.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.NewPipeSource
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

class Services {
    // idle connections are dropped after 10 s: some networks silently end them, as on the phone
    val client = OkHttpClient.Builder().connectionPool(okhttp3.ConnectionPool(5, 10, TimeUnit.SECONDS)).build()
    init {
        Covers.client = client
        NewPipe.init(OkHttpDownloader(client), Localization("en", "US"))
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val source = NewPipeSource()
    val resolver = StreamResolver(source, scope)
    // SPOTIFRY_MPV_ARGS="--ao=null" turns the sound off, for tests
    val player = MpvPlayer(resolver, scope, File(dataDir(), "last_queue.json"), System.getenv("SPOTIFRY_MPV_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty())
}

fun main() = application {
    val services = androidx.compose.runtime.remember { Services() }
    Window(
        onCloseRequest = { services.player.close(); exitApplication() }, title = "Spotifry",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) { SpotifryTheme { App(services.player, services.source, services.player::setVolume) } }
}

/** Runs [block] when Enter is pressed in a text field. */
fun Modifier.onEnter(block: () -> Unit): Modifier = onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { block(); true } else false }
