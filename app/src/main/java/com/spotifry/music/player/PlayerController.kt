package com.spotifry.music.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUiState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Queue positions in the order they will play, starting at the current song. Null means plain list order. */
    val order: List<Int>? = null,
    /** Where the queue came from, e.g. "Saved" or a playlist name. Null after the app restarts. */
    val from: String? = null,
) {
    fun upNextIndices(): List<Int> = order ?: (currentIndex until queue.size).toList()
}

/** UI-facing handle on the playback service. Commands sent before the connection is ready are queued. */
/** What screens need to start playback; lets view models be tested without a service. */
interface SongPlayer { fun playList(songs: List<Song>, startIndex: Int, from: String? = null) }

class PlayerController(context: Context, private val lastQueueFile: File = File(context.filesDir, "last_queue.json")) : SongPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    // kept apart from [state]: it ticks twice a second, and only the progress bars and lyrics need it
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var controller: MediaController? = null
    private var from: String? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publish(c); save(c) }
            })
            // the service is fresh (app was closed): bring back the last queue, paused, before anything else runs
            if (c.mediaItemCount == 0 && pending.isEmpty()) restore(c)
            pending.forEach { it(c) }
            pending.clear()
            publish(c)
        }, context.mainExecutor)
        scope.launch {
            var ticks = 0
            while (true) {
                delay(500)
                controller?.takeIf { it.isPlaying }?.let { _position.value = it.currentPosition.coerceAtLeast(0); if (++ticks % 20 == 0) save(it) }
            }
        }
    }

    private fun restore(c: MediaController) {
        val last = runCatching { lastQueueFile.readText() }.getOrNull()?.let(LastQueue::decode) ?: return
        from = last.from
        c.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        c.shuffleModeEnabled = last.shuffle
        c.repeatMode = last.repeatMode
        // not prepared: nothing loads or plays until the play button, which prepares it (see toggle)
    }

    /** Written on every change and every 10 s while playing, so a closed app loses at most 10 s of position. */
    private fun save(c: MediaController) {
        if (c.mediaItemCount == 0) return
        val text = LastQueue(
            (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }, c.currentMediaItemIndex,
            c.currentPosition.coerceAtLeast(0), c.shuffleModeEnabled, c.repeatMode, from,
        ).encode()
        scope.launch(Dispatchers.IO) { runCatching { lastQueueFile.writeText(text) } }
    }

    private fun publish(c: MediaController) {
        _position.value = c.currentPosition.coerceAtLeast(0)
        _state.value = PlayerUiState(
            current = c.currentMediaItem?.toSong(),
            isPlaying = c.isPlaying,
            // before the first play after a restore the player doesn't know the length yet; the song does
            durationMs = c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.toSong()?.durationSec ?: 0) * 1000),
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() },
            currentIndex = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            order = playOrder(c),
            from = from,
        )
    }

    /** From the current song on, in the order the player will really use (follows shuffle). */
    private fun playOrder(c: MediaController): List<Int>? {
        if (!c.shuffleModeEnabled || c.mediaItemCount == 0) return null
        val t = c.currentTimeline
        val out = mutableListOf<Int>()
        var i = c.currentMediaItemIndex
        while (i != C.INDEX_UNSET && i !in out && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    private fun run(block: (MediaController) -> Unit) { controller?.let(block) ?: pending.add(block) }

    override fun playList(songs: List<Song>, startIndex: Int, from: String?) = run { c ->
        this.from = from
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
        c.prepare()
        c.play()
    }
    fun toggle() = run {
        if (it.isPlaying) it.pause()
        else { if (needsPrepare(it.playbackState)) it.prepare(); it.play() }
    }
    fun next() = run { it.seekToNextMediaItem() }
    fun previous() = run { it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun setShuffle(on: Boolean) = run { it.shuffleModeEnabled = on }
    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun skipTo(index: Int) = run { it.seekToDefaultPosition(index) }
}
