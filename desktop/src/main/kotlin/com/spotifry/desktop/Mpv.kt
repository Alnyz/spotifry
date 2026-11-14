package com.spotifry.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** mpv is not installed (or won't start): the app can't play anything and says so at the start. */
class MpvMissing(cause: Throwable) : Exception("Spotifry plays music through mpv, and mpv isn't installed.", cause)

/**
 * One mpv process with no window, driven over its JSON socket. [onEvent] gets every event mpv sends (property changes
 * we asked for, start-file, end-file, playback-restart) on a background thread.
 */
class Mpv(extraArgs: List<String> = emptyList(), binary: String = Platform.tool("mpv"), private val onEvent: (name: String, event: JSONObject) -> Unit) : AutoCloseable {
    private val dir = Files.createTempDirectory("spotifry-mpv")
    private val socket = dir.resolve("mpv.sock")
    /** mpv's control address: a socket file on Linux, a named pipe on Windows. */
    private val endpoint = if (Platform.isWindows) "\\\\.\\pipe\\spotifry-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}" else socket.toString()
    private var pipe: AsynchronousFileChannel? = null
    private val process: Process
    private val out: OutputStream
    private val reader: java.io.BufferedReader

    init {
        process = try { ProcessBuilder(
            // --no-config: a listener's own mpv.conf (keep-open=yes, say) must not change how the player behaves
            listOf(binary, "--no-config", "--idle=yes", "--keep-open=no", "--no-video", "--no-terminal", "--audio-display=no", "--ytdl=no",
                "--audio-client-name=Spotifry", "--input-ipc-server=$endpoint",
                // the whole song is fetched ahead: a lossless file's server takes seconds per request, and a jump into a
                // part not yet fetched could need several (mpv has to search the file for the spot)
                "--cache=yes", "--demuxer-max-bytes=200MiB", "--demuxer-readahead-secs=3600") + extraArgs,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() } catch (e: java.io.IOException) { throw MpvMissing(e) }
        // the socket (or pipe) shows up a moment after mpv starts
        val until = System.currentTimeMillis() + 5_000
        if (Platform.isWindows) {
            // a pipe can't be asked whether it exists, only opened; opened without blocking, so a read waiting for an event never holds up a write
            while (pipe == null) {
                check(process.isAlive) { "mpv stopped at start. Is mpv installed?" }
                check(System.currentTimeMillis() < until) { "mpv did not open its control pipe" }
                pipe = runCatching { AsynchronousFileChannel.open(Path.of(endpoint), StandardOpenOption.READ, StandardOpenOption.WRITE) }.getOrNull() ?: run { Thread.sleep(20); null }
            }
            val ch = pipe!!
            out = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) { ch.write(ByteBuffer.wrap(b, off, len), 0).get() }
            }
            reader = java.io.InputStreamReader(object : java.io.InputStream() {
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
                // a closed pipe is the end of the stream, not an error
                override fun read(b: ByteArray, off: Int, len: Int): Int = try { ch.read(ByteBuffer.wrap(b, off, len), 0).get().takeIf { it > 0 } ?: -1 } catch (e: Exception) { -1 }
            }, Charsets.UTF_8).buffered()
        } else {
            while (!Files.exists(socket)) {
                check(process.isAlive) { "mpv stopped at start. Is mpv installed?" }
                check(System.currentTimeMillis() < until) { "mpv did not open its control socket" }
                Thread.sleep(20)
            }
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socket)) }
            out = Channels.newOutputStream(channel)
            reader = Channels.newInputStream(channel).bufferedReader()
        }
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
        runCatching { pipe?.close() }
        runCatching { Files.deleteIfExists(socket); Files.deleteIfExists(dir) }
    }
}
