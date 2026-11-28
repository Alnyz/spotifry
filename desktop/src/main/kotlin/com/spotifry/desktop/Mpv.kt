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
class Mpv(
    extraArgs: List<String> = emptyList(),
    binary: String = Platform.tool("mpv"),
    /** Set for the Discord bot: mpv writes its sound, raw, to this path instead of playing it. */
    private val pcmPath: String? = null,
    private val onEvent: (name: String, event: JSONObject) -> Unit,
) : AutoCloseable {
    private val dir = Files.createTempDirectory("spotifry-mpv")
    private val socket = dir.resolve("mpv.sock")
    /** mpv's control address: a socket file on Linux, a named pipe on Windows. */
    private val endpoint = if (Platform.isWindows) "\\\\.\\pipe\\spotifry-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}" else socket.toString()
    private var pipe: AsynchronousFileChannel? = null
    private var process: Process
    /** mpv's own messages (warnings and errors only): read when it stops at start, so the reason can be shown; gone on close. */
    private val log = dir.resolve("mpv.log")
    private var retried = false
    private val out: OutputStream
    private val reader: java.io.BufferedReader
    /** Set by [close]: the socket then breaks on purpose, and the event reader ends without a word. */
    @Volatile private var closing = false

    /** The raw sound when [pcmPath] is mpv's own output ("/dev/stdout"): 48 kHz, 16-bit, stereo, little-endian. */
    val pcm: java.io.InputStream? get() = if (pcmPath == STDOUT) process.inputStream else null

    init {
        process = launch(binary, extraArgs)
        // the socket (or pipe) shows up a moment after mpv starts
        val until = System.currentTimeMillis() + 7_000
        if (Platform.isWindows) {
            // a pipe can't be asked whether it exists, only opened; opened without blocking, so a read waiting for an event never holds up a write
            while (pipe == null) {
                if (!process.isAlive) startAgain(binary, extraArgs)
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
                if (!process.isAlive) startAgain(binary, extraArgs)
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

    private fun launch(binary: String, extraArgs: List<String>): Process = try { ProcessBuilder(
        // --no-config: a listener's own mpv.conf (keep-open=yes, say) must not change how the player behaves.
        // No --no-terminal: that would hide why mpv refused to start; its warnings and errors go to [log] instead.
        listOf(binary, "--no-config", "--idle=yes", "--keep-open=no", "--no-video", "--input-terminal=no", "--term-osd=no", "--msg-level=all=warn",
            "--audio-display=no", "--ytdl=no", "--audio-client-name=Spotifry", "--input-ipc-server=$endpoint",
            // the whole song is fetched ahead: a lossless file's server takes seconds per request, and a jump into a
            // part not yet fetched could need several (mpv has to search the file for the spot)
            "--cache=yes", "--demuxer-max-bytes=200MiB", "--demuxer-readahead-secs=3600") + extraArgs + pcmArgs(),
    ).let { pb ->
        // with raw sound on mpv's output, its messages keep going to the log through the error stream alone
        if (pcmPath == STDOUT) pb.redirectError(log.toFile()) else pb.redirectErrorStream(true).redirectOutput(log.toFile())
    }.start() } catch (e: java.io.IOException) { throw MpvMissing(e) }

    /** mpv stopped before it opened its socket: once more a second later (an antivirus checking a new mpv.exe can stop it the first time), then the reason. */
    private fun startAgain(binary: String, extraArgs: List<String>) {
        val code = process.waitFor()
        if (!retried) { retried = true; Thread.sleep(1000); process = launch(binary, extraArgs); return }
        val said = runCatching { Files.readAllLines(log).filter { it.isNotBlank() }.takeLast(20) }.getOrDefault(emptyList())
        runCatching { Files.deleteIfExists(log); Files.deleteIfExists(dir) }
        throw IllegalStateException(mpvStopped(binary, code, said, Platform.isWindows))
    }

    private fun listen() {
        while (true) {
            val line = try { reader.readLine() } catch (e: java.io.IOException) { if (closing) return else throw e } ?: return
            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
            // one bad event must not end the listener, or the player would go deaf
            event.optString("event").takeIf { it.isNotEmpty() }?.let { runCatching { onEvent(it, event) }.onFailure { e -> System.err.println("mpv event $it: $e") } }
        }
    }

    fun command(vararg args: Any?) {
        val line = JSONObject().put("command", JSONArray(args.toList())).toString() + "\n"
        synchronized(out) { out.write(line.toByteArray()); out.flush() }
    }

    private fun pcmArgs(): List<String> = if (pcmPath == null) emptyList() else listOf(
        "--ao=pcm", "--ao-pcm-file=$pcmPath", "--ao-pcm-waveheader=no", "--audio-samplerate=48000", "--audio-channels=stereo", "--audio-format=s16",
    )

    fun observe(id: Int, property: String) = command("observe_property", id, property)
    fun set(property: String, value: Any) = command("set_property", property, value)

    override fun close() {
        closing = true
        runCatching { command("quit") }
        process.destroy()
        runCatching { pipe?.close() }
        runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); Files.deleteIfExists(log) }
        runCatching { Files.deleteIfExists(socket); Files.deleteIfExists(dir) }
    }
}

/** Why mpv stopped at start, for the start screen and the log: its exit code, its last words, and on Windows the usual causes. */
internal fun mpvStopped(binary: String, code: Int, said: List<String>, windows: Boolean): String = buildString {
    append("mpv stopped at start (exit code $code")
    if (code < 0 || code > 0xFFFF) append(", 0x%08X".format(code))
    append(").")
    when (code) {
        0xC000001D.toInt() -> append(" This PC's processor can't run this mpv.")
        0xC0000135.toInt() -> append(" A file mpv needs is missing.")
    }
    if (windows) append("\nAn antivirus may be blocking mpv.exe, or this Windows or PC is too old for it. mpv tried: $binary")
    if (said.isNotEmpty()) append("\n\nmpv said:\n").append(said.joinToString("\n"))
}

/** mpv's own output, as a path its sound can be written to (Linux and macOS). */
const val STDOUT = "/dev/stdout"
