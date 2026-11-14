package com.spotifry.desktop

import com.spotifry.music.data.source.Song
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode
import com.spotifry.music.player.Trim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Talks to the player the way the keyboard's media keys do, through the real session bus and the busctl tool. */
class MprisTest {
    private class Fake : MusicPlayer {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(PlayerUiState(current = Song("a-b", "Hujan Pagi", "Kala Biru", 211, "https://x/y.jpg"), wantsPlay = true, isPlaying = true, durationMs = 211_000, queue = listOf(Song("a-b", "Hujan Pagi", "Kala Biru", 211, null))))
        override val position = MutableStateFlow(84_000L)
        override fun playList(songs: List<Song>, startIndex: Int, from: String?) { calls += "playList" }
        override fun toggle() { calls += "toggle" }; override fun pause() { calls += "pause" }; override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }; override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun cycleShuffle() { calls += "shuffle" }; override fun remove(index: Int, id: String) = Unit
        override fun insert(index: Int, song: Song, upcoming: List<Int>?) = Unit; override fun move(from: Int, to: Int) = Unit
        override fun cycleRepeat() { calls += "repeat"; state.value = state.value.copy(repeatMode = (state.value.repeatMode + 1) % 3) }
        override fun skipTo(index: Int) = Unit; override fun setTrim(id: String, trim: Trim?, skip: Boolean) = Unit; override fun listen(atMs: Long, forMs: Long?) = Unit
    }

    private val fake = Fake()
    private var mpris: Mpris? = null
    private val name = "org.mpris.MediaPlayer2.spotifry_test"
    private fun busctl(vararg a: String): String = ProcessBuilder(listOf("busctl", "--user") + a).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
    private fun player(vararg a: String) = busctl(*a.toList().let { listOf(it[0], name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player") + it.drop(1) }.toTypedArray())

    @Before fun start() {
        assumeTrue("no session bus", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null && runCatching { ProcessBuilder("busctl", "--version").start().waitFor() == 0 }.getOrDefault(false))
        mpris = Mpris.start(fake, CoroutineScope(SupervisorJob() + Dispatchers.IO), {}, {}, {}, instance = "spotifry_test")
        assumeTrue("could not join the bus", mpris != null)
    }
    @After fun stop() { mpris?.close() }

    private fun has(tool: String) = runCatching { ProcessBuilder("which", tool).start().waitFor() == 0 }.getOrDefault(false)
    private fun run(vararg cmd: String): String = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }

    /** GLib's client, the one GNOME and playerctl use. */
    @Test fun gdbusReadsTheState() {
        assumeTrue("no gdbus", has("gdbus"))
        fun get(iface: String, prop: String) = run("gdbus", "call", "--session", "--dest", name, "--object-path", "/org/mpris/MediaPlayer2", "--method", "org.freedesktop.DBus.Properties.Get", iface, prop)
        assertEquals("('Playing',)", get("org.mpris.MediaPlayer2.Player", "PlaybackStatus"))
        assertEquals("('Spotifry',)", get("org.mpris.MediaPlayer2", "Identity"))
        assertTrue(get("org.mpris.MediaPlayer2.Player", "Position").contains("84000000"))
        val meta = get("org.mpris.MediaPlayer2.Player", "Metadata")
        assertTrue(meta, "'Hujan Pagi'" in meta && "'Kala Biru'" in meta && "int64 211000000" in meta && "objectpath '/org/spotifry/track/a_b'" in meta && "https://x/y.jpg" in meta)
    }

    /** Qt's client, the one KDE's media controls use: it reads properties through Properties.Get and calls the methods. */
    @Test fun qdbusReadsTheStateAndPressesPlay() {
        assumeTrue("no qdbus6", has("qdbus6"))
        fun get(iface: String, prop: String) = run("qdbus6", name, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties.Get", iface, prop)
        assertEquals("Playing", get("org.mpris.MediaPlayer2.Player", "PlaybackStatus"))
        assertEquals("Spotifry", get("org.mpris.MediaPlayer2", "Identity"))
        run("qdbus6", name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player.PlayPause")
        assertEquals(listOf("toggle"), fake.calls)
    }

    @Test fun mediaKeysReachThePlayer() {
        player("call", "PlayPause"); player("call", "Next"); player("call", "Previous"); player("call", "Pause")
        player("call", "Seek", "x", "5000000"); player("call", "SetPosition", "ox", "/org/x", "30000000")
        assertEquals(listOf("toggle", "next", "previous", "pause", "seek 89000", "seek 30000"), fake.calls)
    }

    @Test fun settingTheLoopAndShuffle() {
        player("set-property", "LoopStatus", "s", "Track")
        assertEquals(RepeatMode.ONE, fake.state.value.repeatMode)
        player("set-property", "Shuffle", "b", "true")
        assertTrue("shuffle" in fake.calls)
    }
}
