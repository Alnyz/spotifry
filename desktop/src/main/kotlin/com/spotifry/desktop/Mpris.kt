package com.spotifry.desktop

import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

private const val PATH = "/org/mpris/MediaPlayer2"
private const val ROOT = "org.mpris.MediaPlayer2"
private const val PLAYER = "org.mpris.MediaPlayer2.Player"

@DBusInterfaceName(ROOT)
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
    @DBusBoundProperty(name = "CanQuit", access = Access.READ) fun canQuit(): Boolean
    @DBusBoundProperty(name = "CanRaise", access = Access.READ) fun canRaise(): Boolean
    @DBusBoundProperty(name = "HasTrackList", access = Access.READ) fun hasTrackList(): Boolean
    @DBusBoundProperty(name = "Identity", access = Access.READ) fun identity(): String
    @DBusBoundProperty(name = "DesktopEntry", access = Access.READ) fun desktopEntry(): String
    @DBusBoundProperty(name = "SupportedUriSchemes", access = Access.READ) fun supportedUriSchemes(): Array<String>
    @DBusBoundProperty(name = "SupportedMimeTypes", access = Access.READ) fun supportedMimeTypes(): Array<String>
}

/** Tells dbus-java the Metadata property is a{sv}: its generics are not read from the method. */
interface MetadataType : org.freedesktop.dbus.TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName(PLAYER)
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offsetMicros: Long)
    fun SetPosition(trackId: DBusPath, positionMicros: Long)
    fun OpenUri(uri: String)
    @DBusBoundProperty(name = "PlaybackStatus", access = Access.READ) fun playbackStatus(): String
    @DBusBoundProperty(name = "LoopStatus", access = Access.READ) fun loopStatus(): String
    @DBusBoundProperty(name = "LoopStatus", access = Access.WRITE) fun loopStatus(status: String)
    @DBusBoundProperty(name = "Rate", access = Access.READ) fun rate(): Double
    @DBusBoundProperty(name = "Rate", access = Access.WRITE) fun rate(value: Double)
    @DBusBoundProperty(name = "Shuffle", access = Access.READ) fun shuffle(): Boolean
    @DBusBoundProperty(name = "Shuffle", access = Access.WRITE) fun shuffle(on: Boolean)
    @DBusBoundProperty(name = "Metadata", access = Access.READ, type = MetadataType::class) fun metadata(): Map<String, Variant<*>>
    @DBusBoundProperty(name = "Volume", access = Access.READ) fun volume(): Double
    @DBusBoundProperty(name = "Volume", access = Access.WRITE) fun volume(value: Double)
    @DBusBoundProperty(name = "Position", access = Access.READ) fun position(): Long
    @DBusBoundProperty(name = "MinimumRate", access = Access.READ) fun minimumRate(): Double
    @DBusBoundProperty(name = "MaximumRate", access = Access.READ) fun maximumRate(): Double
    @DBusBoundProperty(name = "CanGoNext", access = Access.READ) fun canGoNext(): Boolean
    @DBusBoundProperty(name = "CanGoPrevious", access = Access.READ) fun canGoPrevious(): Boolean
    @DBusBoundProperty(name = "CanPlay", access = Access.READ) fun canPlay(): Boolean
    @DBusBoundProperty(name = "CanPause", access = Access.READ) fun canPause(): Boolean
    @DBusBoundProperty(name = "CanSeek", access = Access.READ) fun canSeek(): Boolean
    @DBusBoundProperty(name = "CanControl", access = Access.READ) fun canControl(): Boolean
}

/**
 * Lets the desktop control the player: the keyboard's media keys, KDE and GNOME's media widgets and `playerctl`
 * talk to it as "Spotifry" over D-Bus (the MPRIS standard). Does nothing when there is no session bus.
 */
class Mpris private constructor(
    private val player: MusicPlayer, private val conn: DBusConnection, private val busName: String, private val setVolume: (Int) -> Unit, private val raise: () -> Unit, private val quit: () -> Unit,
) : MprisRoot, MprisPlayer, AutoCloseable {
    private var volume = 1.0

    override fun getObjectPath() = PATH
    override fun isRemote() = false

    // ---- org.mpris.MediaPlayer2
    override fun Raise() = raise()
    override fun Quit() = quit()
    override fun canQuit() = true
    override fun canRaise() = true
    override fun hasTrackList() = false
    override fun identity() = "Spotifry"
    override fun desktopEntry() = "spotifry"
    override fun supportedUriSchemes() = arrayOf<String>()
    override fun supportedMimeTypes() = arrayOf<String>()

    // ---- org.mpris.MediaPlayer2.Player
    override fun Next() = player.next()
    override fun Previous() = player.previous()
    override fun Pause() = player.pause()
    override fun PlayPause() = player.toggle()
    override fun Stop() = player.pause()
    override fun Play() { if (!player.state.value.wantsPlay) player.toggle() }
    override fun Seek(offsetMicros: Long) = player.seekTo((player.position.value + offsetMicros / 1000).coerceAtLeast(0))
    override fun SetPosition(trackId: DBusPath, positionMicros: Long) = player.seekTo((positionMicros / 1000).coerceAtLeast(0))
    override fun OpenUri(uri: String) = Unit

    override fun playbackStatus() = statusOf(player.state.value)
    override fun loopStatus() = loopOf(player.state.value.repeatMode)
    override fun loopStatus(status: String) {
        val want = when (status) { "Track" -> RepeatMode.ONE; "Playlist" -> RepeatMode.ALL; else -> RepeatMode.OFF }
        // the player only steps through off, all, one: step until it is where it was asked to be
        repeat(3) { if (player.state.value.repeatMode != want) player.cycleRepeat() }
    }
    override fun rate() = 1.0
    override fun rate(value: Double) = Unit
    override fun shuffle() = player.state.value.shuffle
    override fun shuffle(on: Boolean) { if (player.state.value.shuffle != on) player.cycleShuffle() }
    override fun metadata() = metadataOf(player.state.value)
    override fun volume() = volume
    override fun volume(value: Double) { volume = value.coerceIn(0.0, 1.0); setVolume((volume * 100).toInt()) }
    override fun position() = player.position.value * 1000
    override fun minimumRate() = 1.0
    override fun maximumRate() = 1.0
    override fun canGoNext() = player.state.value.queue.isNotEmpty()
    override fun canGoPrevious() = player.state.value.queue.isNotEmpty()
    override fun canPlay() = player.state.value.current != null
    override fun canPause() = player.state.value.current != null
    override fun canSeek() = true
    override fun canControl() = true

    private fun changed(props: Map<String, Variant<*>>) {
        if (props.isEmpty()) return
        runCatching { conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, props, emptyList())) }
    }

    override fun close() { runCatching { conn.releaseBusName(busName) }; runCatching { conn.disconnect() } }

    companion object {
        /** Null when there is no D-Bus session (a bare terminal, a container): the app just runs without media keys. */
        fun start(player: MusicPlayer, scope: CoroutineScope, setVolume: (Int) -> Unit, raise: () -> Unit, quit: () -> Unit, instance: String = "spotifry"): Mpris? = runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            val name = "org.mpris.MediaPlayer2.$instance"
            val mpris = Mpris(player, conn, name, setVolume, raise, quit)
            conn.requestBusName(name)
            conn.exportObject(PATH, mpris)
            scope.launch {
                var last: PlayerUiState? = null
                player.state.collect { s ->
                    val before = last; last = s
                    val diff = mutableMapOf<String, Variant<*>>()
                    if (before == null || statusOf(before) != statusOf(s)) diff["PlaybackStatus"] = Variant(statusOf(s))
                    if (before == null || before.current?.id != s.current?.id || before.durationMs != s.durationMs) diff["Metadata"] = Variant(metadataOf(s), "a{sv}")
                    if (before == null || before.repeatMode != s.repeatMode) diff["LoopStatus"] = Variant(loopOf(s.repeatMode))
                    if (before == null || before.shuffle != s.shuffle) diff["Shuffle"] = Variant(s.shuffle)
                    if (before == null || (before.queue.isEmpty() != s.queue.isEmpty()) || (before.current == null) != (s.current == null)) {
                        diff["CanGoNext"] = Variant(s.queue.isNotEmpty()); diff["CanGoPrevious"] = Variant(s.queue.isNotEmpty())
                        diff["CanPlay"] = Variant(s.current != null); diff["CanPause"] = Variant(s.current != null)
                    }
                    mpris.changed(diff)
                }
            }
            mpris
        }.getOrNull()

        fun statusOf(s: PlayerUiState) = when { s.current == null || s.ended -> "Stopped"; s.wantsPlay -> "Playing"; else -> "Paused" }
        fun loopOf(mode: Int) = when (mode) { RepeatMode.ONE -> "Track"; RepeatMode.ALL -> "Playlist"; else -> "None" }

        fun metadataOf(s: PlayerUiState): Map<String, Variant<*>> {
            val song = s.current ?: return mapOf("mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")))
            val m = linkedMapOf<String, Variant<*>>(
                "mpris:trackid" to Variant(DBusPath("/org/spotifry/track/" + song.id.replace(Regex("[^A-Za-z0-9_]"), "_"))),
                "mpris:length" to Variant(s.durationMs * 1000, "x"),
                "xesam:title" to Variant(song.title),
                "xesam:artist" to Variant(arrayOf(song.artist), "as"),
            )
            song.thumbnailUrl?.let { m["mpris:artUrl"] = Variant(it) }
            return m
        }
    }
}
