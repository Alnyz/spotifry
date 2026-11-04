package com.spotifry.music.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
    /** Shuffle with a similar song from YouTube after every three of yours. Only while [shuffle] is on. */
    val smart: Boolean = false,
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
interface SongPlayer {
    fun playList(songs: List<Song>, startIndex: Int, from: String? = null)
    /** A list's Shuffle button: the songs spread out by [spreadShuffle], always plain shuffle. */
    fun shuffleList(songs: List<Song>, from: String? = null) = playList(spreadShuffle(songs, emptySet()), 0, from)
}

class PlayerController(
    context: Context,
    private val lastQueueFile: File = File(context.filesDir, "last_queue.json"),
    private val recentlyPlayed: suspend () -> Set<String> = { emptySet() },
) : SongPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    // kept apart from [state]: it ticks twice a second, and only the progress bars and lyrics need it
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var controller: MediaController? = null
    private var from: String? = null
    // the service knows too; kept here so the button shows the step at once
    private var smart = false
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
        if (!c.shuffleModeEnabled) smart = false
        _position.value = c.currentPosition.coerceAtLeast(0)
        _state.value = PlayerUiState(
            current = c.currentMediaItem?.toSong(),
            isPlaying = c.isPlaying,
            // before the first play after a restore the player doesn't know the length yet; the song does
            durationMs = c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.toSong()?.durationSec ?: 0) * 1000),
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() },
            currentIndex = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            smart = smart,
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
        // shuffle stays on for a new list but starts fresh: plain (never smart), spread out again by the service
        val shuffled = c.shuffleModeEnabled
        smart = false
        if (shuffled) c.shuffleModeEnabled = false
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
        if (shuffled) c.shuffleModeEnabled = true
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
    override fun shuffleList(songs: List<Song>, from: String?) {
        scope.launch { playList(spreadShuffle(songs, runCatching { recentlyPlayed() }.getOrDefault(emptySet())), 0, from) }
    }

    /** The Now playing button: off, Shuffle, Smart shuffle, off. The service sets the order and adds the suggestions. */
    fun cycleShuffle() = run {
        when {
            !it.shuffleModeEnabled -> it.shuffleModeEnabled = true
            !smart -> { smart = true; it.sendCustomCommand(SessionCommand(PlaybackService.SMART_SHUFFLE, Bundle.EMPTY), Bundle.EMPTY); publish(it) }
            else -> { smart = false; it.shuffleModeEnabled = false }
        }
    }

    /** Takes a song out of the queue; [index] is checked against [id] in case the queue moved meanwhile. */
    fun remove(index: Int, id: String) = run { c ->
        val at = index.takeIf { it < c.mediaItemCount && c.getMediaItemAt(it).mediaId == id }
            ?: (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return@run
        c.removeMediaItem(at)
    }

    /**
     * Undo of a removal. With shuffle on the player puts the song back at a random spot in the play order, so
     * [upcoming] (what was still to come before the removal) sets the order back as it was.
     */
    // ponytail: [upcoming] holds queue positions; a second removal before this Undo shifts them and mixes the order a little
    fun insert(index: Int, song: Song, upcoming: List<Int>? = null) = run {
        it.addMediaItem(index.coerceIn(0, it.mediaItemCount), song.toMediaItem())
        if (upcoming != null && it.shuffleModeEnabled) setUpcoming(it, upcoming)
    }

    /** A drag in Queue: the song [from] places after the playing one now plays [to] places after it (1 = next). */
    fun move(from: Int, to: Int) = run { c ->
        if (from < 1 || to < 1 || from == to) return@run
        if (!c.shuffleModeEnabled) {
            val cur = c.currentMediaItemIndex
            if (cur + maxOf(from, to) < c.mediaItemCount) c.moveMediaItem(cur + from, cur + to)
            return@run
        }
        // shuffle on: moving in the list wouldn't change what plays next, so the service sets the play order instead
        val up = playOrder(c)?.drop(1)?.toMutableList() ?: return@run
        if (maxOf(from, to) > up.size) return@run
        up.add(to - 1, up.removeAt(from - 1))
        setUpcoming(c, up)
    }

    private fun setUpcoming(c: MediaController, upcoming: List<Int>) =
        c.sendCustomCommand(SessionCommand(PlaybackService.SET_UPCOMING, Bundle.EMPTY), Bundle().apply { putIntArray(PlaybackService.UPCOMING, upcoming.toIntArray()) })
    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun skipTo(index: Int) = run { it.seekToDefaultPosition(index) }
}
