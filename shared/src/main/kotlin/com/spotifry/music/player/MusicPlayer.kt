package com.spotifry.music.player

import com.spotifry.music.data.source.Song
import kotlinx.coroutines.flow.StateFlow

/** Repeat modes as numbers; they match Media3's, so the phone passes them straight through. */
object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

data class PlayerUiState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    /** Playing or about to: also true while it buffers, when [isPlaying] is false. */
    val wantsPlay: Boolean = false,
    /** The queue ran out: [current] is still set, but it has finished rather than paused. */
    val ended: Boolean = false,
    /** Waiting for sound to arrive. [wantsPlay] without this and without [isPlaying] is another app holding the sound, such as a call. */
    val buffering: Boolean = false,
    /** The player gave up on the song; [toggle][PlayerController.toggle] tries it again. */
    val failed: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    /** Shuffle with a similar song from YouTube after every three of yours. Only while [shuffle] is on. */
    val smart: Boolean = false,
    val repeatMode: Int = RepeatMode.OFF,
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

/** What screens need to start playback; lets view models be tested without a service. */
interface SongPlayer {
    fun playList(songs: List<Song>, startIndex: Int, from: String? = null)
    /** A list's Shuffle button: the songs spread out by [spreadShuffle], always plain shuffle. */
    fun shuffleList(songs: List<Song>, from: String? = null) = playList(spreadShuffle(songs, emptySet()), 0, from)
}

/**
 * Everything the screens ask of the player. The phone plays through Media3 in a service; the desktop plays through mpv.
 * Commands sent before the player is ready wait and run when it is.
 */
interface MusicPlayer : SongPlayer {
    val state: StateFlow<PlayerUiState>
    /** Kept apart from [state]: it ticks twice a second, and only the progress bars and lyrics need it. */
    val position: StateFlow<Long>

    fun toggle()
    /** Unlike [toggle], this also stops a song that is still loading. */
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    /** The Now playing button: off, Shuffle, Smart shuffle, off. */
    fun cycleShuffle()
    /** Takes a song out of the queue; [index] is checked against [id] in case the queue moved meanwhile. */
    fun remove(index: Int, id: String)
    /** Undo of a removal; [upcoming] (what was still to come before it) puts a shuffled order back as it was. */
    fun insert(index: Int, song: Song, upcoming: List<Int>? = null)
    /** A drag in Queue: the song [from] places after the playing one now plays [to] places after it (1 = next). */
    fun move(from: Int, to: Int)
    fun cycleRepeat()
    fun skipTo(index: Int)
    /** Gives song [id] a new start and end ([trim] null: the whole song). If it is playing it goes on from the same moment. */
    fun setTrim(id: String, trim: Trim?, skip: Boolean = false)
    /** The Trim sheet's play button: plays from [atMs] of the whole song; with [forMs], pauses again after that long. */
    fun listen(atMs: Long, forMs: Long? = null)
}
