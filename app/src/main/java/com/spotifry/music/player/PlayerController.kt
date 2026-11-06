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
    /** Playing or about to: also true while it buffers, when [isPlaying] is false. */
    val wantsPlay: Boolean = false,
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
    /** The playing song's own start and end, null when it plays whole. [durationMs] and the position count the kept part only. */
    val trim: Trim? = null,
    /** Length of the whole song, trimmed parts included. */
    val wholeMs: Long = 0,
    /** The playing song's real sound, e.g. "Opus 160 kbps" or "FLAC 24-bit · 96 kHz". Null until the player knows. */
    val quality: String? = null,
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
    private val saveTrim: (String, Trim?) -> Unit = { _, _ -> },
    private val knownKbps: (String, Long) -> Int? = { _, _ -> null },
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
        val trim = c.currentMediaItem?.trim
        val whole = (c.currentMediaItem?.toSong()?.durationSec ?: 0) * 1000
        val wholeMs = if (trim?.endMs == null && c.duration > 0) c.duration + (trim?.startMs ?: 0) else whole
        _state.value = PlayerUiState(
            current = c.currentMediaItem?.toSong(),
            isPlaying = c.isPlaying,
            wantsPlay = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE,
            // before the first play after a restore the player doesn't know the length yet; the song does
            durationMs = c.duration.takeIf { it > 0 } ?: trim?.keptMs(whole) ?: whole,
            trim = trim,
            // the player's own length is the most exact, but it only covers the whole song when no end is set
            wholeMs = wholeMs,
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() },
            currentIndex = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            smart = smart,
            repeatMode = c.repeatMode,
            order = playOrder(c),
            from = from,
            quality = c.currentMediaItem?.let { qualityLabel(audioFormat(c), knownKbps(it.mediaId, wholeMs)) },
        )
    }

    /** The format of the audio track the player picked for the current song. */
    private fun audioFormat(c: MediaController): androidx.media3.common.Format? =
        c.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }

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

    /**
     * Gives song [id] a new start and end ([trim] null: the whole song), kept on this phone, and rebuilds its places in
     * the queue. If it is playing it goes on from the same moment of the song, unless that moment is now cut off (or
     * [skip]): then the next song starts.
     */
    // ponytail: the rebuilt item reloads, so the music stops for a moment (instant when cached); a seamless swap needs Media3 to update clipping in place
    fun setTrim(id: String, trim: Trim?, skip: Boolean = false) = run { c ->
        saveTrim(id, trim)
        val cur = c.currentMediaItemIndex
        val playing = c.currentMediaItem?.mediaId == id
        val at = c.currentPosition + (c.currentMediaItem?.trim?.startMs ?: 0)
        // a replaced song gets a random place in the shuffle order; put back what was coming
        val upcoming = playOrder(c)?.drop(1)
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            if (item.mediaId == id) c.replaceMediaItem(i, item.toSong().toMediaItem(trim))
        }
        if (upcoming != null) setUpcoming(c, upcoming)
        if (!playing) return@run
        val end = trim?.endMs
        if (skip || (end != null && at >= end)) {
            if (c.hasNextMediaItem()) c.seekToNextMediaItem() else { c.pause(); c.seekTo(cur, 0) }
        } else c.seekTo(cur, (at - (trim?.startMs ?: 0)).coerceAtLeast(0))
    }

    private var listening: kotlinx.coroutines.Job? = null

    /** The Trim sheet's ▶: plays from [atMs] of the whole song; with [forMs], pauses again after that long. */
    fun listen(atMs: Long, forMs: Long? = null) = run { c ->
        listening?.cancel()
        val start = c.currentMediaItem?.trim?.startMs ?: 0
        // only the part the player has now can play: an earlier start or later end is heard from Save on
        var pos = (atMs - start).coerceAtLeast(0)
        if (c.duration > 0) pos = pos.coerceAtMost(maxOf(0, c.duration - (forMs ?: 1_000)))
        c.seekTo(pos)
        if (needsPrepare(c.playbackState)) c.prepare()
        c.play()
        if (forMs != null) listening = scope.launch { delay((forMs / c.playbackParameters.speed).toLong()); c.pause() }
    }
}
