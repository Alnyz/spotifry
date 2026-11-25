package com.spotifry.desktop

import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.math.abs

/** The id of "Spotifry" on Discord's developer site. Public, not a secret. While empty, the setting is not shown. */
const val DISCORD_APP_ID = "1575113956924588182"

/** The app's logo, which Discord fetches itself, so it has to be a public picture. */
private const val LOGO = "https://raw.githubusercontent.com/Alnyz/spotifry/main/desktop/packaging/spotifry.png"

/** What the profile shows. [startMs] is the clock time the song would have started at, which Discord counts from. */
data class DiscordActivity(val title: String, val artist: String, val cover: String?, val startMs: Long, val endMs: Long?) {
    /** The same card: a few hundred ms of drift in the start is not a change, a seek is. */
    fun sameAs(other: DiscordActivity?) = other != null && copy(startMs = 0, endMs = null) == other.copy(startMs = 0, endMs = null) &&
        abs(startMs - other.startMs) < 3_000 && (endMs == null) == (other.endMs == null)

    fun json(): JSONObject {
        // Discord refuses texts shorter than 2 or longer than 128 characters
        fun fit(text: String) = text.take(128).padEnd(2)
        val o = JSONObject().put("type", 2).put("details", fit(title))
            .put("timestamps", JSONObject().put("start", startMs).apply { endMs?.let { put("end", it) } })
        if (artist.isNotBlank()) o.put("state", fit(artist))
        // the logo sits on the cover's corner, or takes its place when there is no cover to show
        val assets = JSONObject().put("large_image", cover ?: LOGO)
        if (cover != null) assets.put("small_image", LOGO).put("small_text", "Spotifry")
        o.put("assets", assets)
        // Discord shows it to other people only, never on your own card
        o.put("buttons", org.json.JSONArray().put(JSONObject().put("label", "Get Spotifry").put("url", "https://alnyz.github.io/spotifry/")))
        return o
    }

    companion object {
        /** Null when nothing should show: nothing playing, paused or finished. */
        fun of(s: PlayerUiState, positionMs: Long, now: Long): DiscordActivity? {
            val song = s.current ?: return null
            if (!s.wantsPlay || s.ended || s.failed) return null
            val start = now - positionMs
            // covers kept on this computer can't be shown: Discord fetches the picture itself
            return DiscordActivity(song.title, song.artist, song.thumbnailUrl?.takeIf { it.startsWith("https://") }, start, if (s.durationMs > 0) start + s.durationMs else null)
        }
    }
}

/** The local connection to the Discord app on this computer: each message is a type, a length and that much JSON. */
class DiscordIpc private constructor(private val read: (ByteArray) -> Unit, private val write: (ByteArray) -> Unit, private val closer: Closeable) : Closeable {
    private var nonce = 0

    /** Shows [activity] on the profile, or takes it down when null. */
    fun show(activity: DiscordActivity?) {
        val args = JSONObject().put("pid", ProcessHandle.current().pid())
        if (activity != null) args.put("activity", activity.json())
        send(1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", (++nonce).toString()))
    }

    private fun send(op: Int, body: JSONObject) {
        val data = body.toString().toByteArray()
        write(ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(data.size).put(data).array())
        val head = ByteBuffer.wrap(ByteArray(8).also(read)).order(ByteOrder.LITTLE_ENDIAN)
        val answer = head.getInt()
        val length = head.getInt()
        if (length !in 0..64_000) throw IOException("Discord answer too long")
        read(ByteArray(length))
        // 2 is "closing": a wrong app id, for one
        if (answer == 2) throw IOException("Discord closed the connection")
    }

    override fun close() { runCatching { closer.close() } }

    companion object {
        /** The folders the Discord app's socket can be in, the usual one first, then its Flatpak and Snap ones. */
        private fun folders(): List<File> {
            val base = File(System.getenv("XDG_RUNTIME_DIR") ?: System.getenv("TMPDIR") ?: "/tmp")
            return listOf(base, File(base, "app/com.discordapp.Discord"), File(base, "snap.discord"))
        }

        /** Null when Discord isn't running. */
        fun connect(appId: String, dirs: List<File>? = null): DiscordIpc? {
            for (i in 0..9) {
                val open: List<() -> DiscordIpc> = if (Platform.isWindows) listOf({ pipe("\\\\.\\pipe\\discord-ipc-$i") })
                else (dirs ?: folders()).map { dir -> { socket(File(dir, "discord-ipc-$i")) } }
                for (make in open) {
                    val ipc = runCatching(make).getOrNull() ?: continue
                    if (runCatching { ipc.send(0, JSONObject().put("v", 1).put("client_id", appId)) }.isSuccess) return ipc
                    ipc.close()
                }
            }
            return null
        }

        private fun pipe(name: String): DiscordIpc {
            val file = RandomAccessFile(name, "rw")
            return DiscordIpc(file::readFully, file::write, file)
        }

        private fun socket(path: File): DiscordIpc {
            if (!path.exists()) throw IOException("no socket")
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            runCatching { channel.connect(UnixDomainSocketAddress.of(path.path)) }.onFailure { channel.close(); throw it }
            val input = java.io.DataInputStream(Channels.newInputStream(channel))
            return DiscordIpc(input::readFully, { val b = ByteBuffer.wrap(it); while (b.hasRemaining()) channel.write(b) }, channel)
        }
    }
}

/**
 * Keeps the Discord profile in step with the player while [enabled]. It looks every two seconds, which also keeps within
 * Discord's limit on how often the card may change. Closing the connection (or the app) takes the card down.
 * ponytail: if Discord restarts mid-song, the card returns with the next song or seek.
 */
fun CoroutineScope.discordPresence(player: MusicPlayer, enabled: StateFlow<Boolean>, appId: String = DISCORD_APP_ID) = launch(Dispatchers.IO) {
    var ipc: DiscordIpc? = null
    var shown: DiscordActivity? = null
    try {
        while (isActive) {
            val want = if (enabled.value) DiscordActivity.of(player.state.value, player.position.value, System.currentTimeMillis()) else null
            if (want == null) {
                ipc?.close(); ipc = null; shown = null
            } else if (!want.sameAs(shown)) {
                val link = ipc ?: DiscordIpc.connect(appId).also { ipc = it }
                if (link != null) {
                    if (runCatching { link.show(want) }.isSuccess) shown = want
                    else { link.close(); ipc = null; shown = null }
                }
            }
            delay(2_000)
        }
    } finally {
        ipc?.close()
    }
}
