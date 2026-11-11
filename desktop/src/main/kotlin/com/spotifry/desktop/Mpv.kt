package com.spotifry.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * One mpv process with no window, driven over its JSON socket. [onEvent] gets every event mpv sends (property changes
 * we asked for, start-file, end-file, playback-restart) on a background thread.
 */
class Mpv(extraArgs: List<String> = emptyList(), private val onEvent: (name: String, event: JSONObject) -> Unit) : AutoCloseable {
    private val dir = Files.createTempDirectory("spotifry-mpv")
    private val socket = dir.resolve("mpv.sock")
    private val process: Process
    private val out: OutputStream
    private val reader: java.io.BufferedReader

    init {
        process = ProcessBuilder(
            // --no-config: a listener's own mpv.conf (keep-open=yes, say) must not change how the player behaves
            listOf("mpv", "--no-config", "--idle=yes", "--keep-open=no", "--no-video", "--no-terminal", "--audio-display=no", "--ytdl=no",
                "--audio-client-name=Spotifry", "--input-ipc-server=$socket") + extraArgs,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // the socket shows up a moment after mpv starts
        val until = System.currentTimeMillis() + 5_000
        while (!Files.exists(socket)) {
            check(process.isAlive) { "mpv stopped at start. Is mpv installed?" }
            check(System.currentTimeMillis() < until) { "mpv did not open its control socket" }
            Thread.sleep(20)
        }
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socket)) }
        out = Channels.newOutputStream(channel)
        reader = Channels.newInputStream(channel).bufferedReader()
        Thread(::listen, "mpv-events").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    private fun listen() {
        while (true) {
            val line = reader.readLine() ?: return
            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
            // one bad event must not end the listener, or the player would go deaf
            event.optString("event").takeIf { it.isNotEmpty() }?.let { runCatching { onEvent(it, event) }.onFailure { e -> System.err.println("mpv event $it: $e") } }
        }
    }

    fun command(vararg args: Any?) {
        val line = JSONObject().put("command", JSONArray(args.toList())).toString() + "\n"
        synchronized(out) { out.write(line.toByteArray()); out.flush() }
    }

    fun observe(id: Int, property: String) = command("observe_property", id, property)
    fun set(property: String, value: Any) = command("set_property", property, value)

    override fun close() {
        runCatching { command("quit") }
        process.destroy()
        runCatching { Files.deleteIfExists(socket); Files.deleteIfExists(dir) }
    }
}
