package com.spotifry.desktop

import com.spotifry.music.data.discord.DISCORD_APP_ID
import com.spotifry.music.data.discord.DiscordActivity
import com.spotifry.music.player.MusicPlayer
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
