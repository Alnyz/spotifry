package com.spotifry.desktop

import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.HistoryRecorder
import com.spotifry.music.data.ListenLog
import com.spotifry.music.player.LastQueue
import com.spotifry.music.player.ListenTracker
import com.spotifry.music.player.PlayCounter
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode
import com.spotifry.music.player.Trim
import com.spotifry.music.player.spreadShuffle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * The desktop's player: the queue lives here, the sound is made by mpv. Same behaviour as the phone's player for what the
 * 0.1 screens use. Smart shuffle, trims, speed and pitch, the sleep timer and the sound quality label come later.
 */
class MpvPlayer(
    private val resolver: StreamResolver,
    private val scope: CoroutineScope,
    private val lastQueueFile: File? = null,
    mpvArgs: List<String> = emptyList(),
    private val recentlyPlayed: suspend () -> Set<String> = { emptySet() },
    private val random: Random = Random.Default,
    /** Where a song's file is when it was saved on this computer, so it plays from there. */
    private val localFile: suspend (Song) -> String? = { null },
    private val recorder: PlayRecorder? = null,
) : MusicPlayer {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state
    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position

    private val lock = Any()
    private var queue = emptyList<Song>()
    private var index = 0
    private var from: String? = null
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    /** Queue positions in play order, the playing one first. Null when the list plays as it is. */
    private var order: List<Int>? = null

    private var wants = false
    private var loaded = false
    private var buffering = false
    private var failed = false
    private var ended = false
    private var durationMs = 0L
    /** Where to start the next load, from a restored queue. */
    private var startAtMs = 0L
    private var failsOfThisSong = 0
    private var loading: Job? = null
    private var lastTick = 0L
    private val listens = ListenTracker()
    private val plays = PlayCounter()

    private val mpv = Mpv(mpvArgs, onEvent = ::onMpv).also {
        it.observe(1, "time-pos"); it.observe(2, "duration"); it.observe(3, "paused-for-cache")
    }

    init {
        lastQueueFile?.let { f -> runCatching { f.readText() }.getOrNull()?.let(LastQueue::decode) }?.let { last ->
            queue = last.songs; index = last.index; from = last.from; shuffle = last.shuffle; repeat = last.repeatMode
            startAtMs = last.positionMs; _position.value = last.positionMs
            durationMs = queue[index].durationSec * 1000
            order = newOrder()
            publish()
        }
        scope.launch { while (true) { delay(10_000); if (wants) save() } }
        scope.launch {
            var last = System.nanoTime() / 1_000_000
            while (true) {
                delay(1_000)
                val (id, playing) = synchronized(lock) { queue.getOrNull(index)?.id to state.value.isPlaying }
                if (plays.tick(id, playing, 1_000) && id != null) recorder?.let { r -> launch { r.countPlay(id) } }
                val at = System.nanoTime() / 1_000_000
                recorder?.listenLog?.write(listens.tick(id, playing, at - last))
                last = at
            }
        }
    }

    fun setVolume(percent: Int) = mpv.set("volume", percent.coerceIn(0, 100))

    // ---- commands

    override fun playList(songs: List<Song>, startIndex: Int, from: String?) = synchronized(lock) {
        if (songs.isEmpty()) return
        queue = songs; this.from = from
        wants = true; startAtMs = 0
        load(startIndex.coerceIn(songs.indices))
    }

    override fun shuffleList(songs: List<Song>, from: String?) {
        scope.launch { playList(spreadShuffle(songs, runCatching { recentlyPlayed() }.getOrDefault(emptySet())), 0, from) }
    }

    override fun toggle() = synchronized(lock) {
        if (queue.isEmpty()) return
        when {
            ended || failed -> { wants = true; startAtMs = 0; load(index) }
            !loaded && loading == null -> { wants = true; load(index) }
            else -> { wants = !wants; mpv.set("pause", !wants); publish() }
        }
    }

    override fun pause() = synchronized(lock) { wants = false; mpv.set("pause", true); publish() }

    override fun next() = synchronized(lock) {
        nextIndex(automatic = false)?.let { wants = true; load(it) }
        Unit
    }

    override fun previous() = synchronized(lock) {
        if (queue.isEmpty()) return
        val prev = if (_position.value > 3_000) null else previousIndex()
        if (prev == null) seekTo(0) else { wants = true; load(prev) }
    }

    override fun seekTo(ms: Long) {
        _position.value = ms
        if (loaded) mpv.command("seek", ms / 1000.0, "absolute") else startAtMs = ms
    }

    override fun cycleShuffle() = synchronized(lock) {
        shuffle = !shuffle
        order = newOrder()
        publish(); save()
    }

    override fun cycleRepeat() = synchronized(lock) {
        repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; else -> RepeatMode.OFF }
        publish(); save()
    }

    override fun skipTo(index: Int) = synchronized(lock) {
        if (index in queue.indices) { wants = true; load(index) }
        Unit
    }

    override fun remove(index: Int, id: String) = synchronized(lock) {
        val at = index.takeIf { it in queue.indices && queue[it].id == id } ?: queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val playing = at == this.index
        val following = if (playing) nextIndex(automatic = true)?.let { it - if (it > at) 1 else 0 } else null
        queue = queue.toMutableList().also { it.removeAt(at) }
        when {
            queue.isEmpty() -> { stopAll(); return }
            playing -> { this.index = (following ?: 0).coerceIn(queue.indices); order = newOrder(); load(this.index, keepWants = true) }
            else -> { if (at < this.index) this.index--; order = order?.let { o -> o.filter { it != at }.map { if (it > at) it - 1 else it } }; publish(); save() }
        }
    }

    override fun insert(index: Int, song: Song, upcoming: List<Int>?) = synchronized(lock) {
        val at = index.coerceIn(0, queue.size)
        queue = queue.toMutableList().also { it.add(at, song) }
        if (at <= this.index && queue.size > 1) this.index++
        order = order?.map { if (it >= at) it + 1 else it }?.let { it + at }
        publish(); save()
    }

    override fun move(from: Int, to: Int) = synchronized(lock) {
        if (from < 1 || to < 1 || from == to) return
        val up = (order ?: (index until queue.size).toList()).drop(1).toMutableList()
        if (maxOf(from, to) > up.size) return
        up.add(to - 1, up.removeAt(from - 1))
        if (order != null) order = listOf(index) + up
        else queue = queue.toMutableList().also { q -> val cur = index; q.add(cur + to, q.removeAt(cur + from)) }
        publish(); save()
    }

    /** 0.1 has no trim screen, so a trim is not kept or applied. */
    override fun setTrim(id: String, trim: Trim?, skip: Boolean) = Unit

    override fun listen(atMs: Long, forMs: Long?) {
        seekTo(atMs)
        synchronized(lock) { wants = true; mpv.set("pause", false); publish() }
        if (forMs != null) scope.launch { delay(forMs); pause() }
    }

    // ---- order

    private fun newOrder(): List<Int>? {
        if (!shuffle || queue.isEmpty()) return null
        val rest = (queue.indices - index).let { spreadShuffle(it, { i -> queue[i].artist }, { false }, random) }
        return listOf(index) + rest
    }

    private fun nextIndex(automatic: Boolean): Int? {
        if (queue.isEmpty()) return null
        if (automatic && repeat == RepeatMode.ONE) return index
        val o = order
        val after = if (o != null) o.getOrNull(o.indexOf(index) + 1) else (index + 1).takeIf { it < queue.size }
        if (after != null) return after
        if (repeat != RepeatMode.ALL) return null
        // ponytail: a shuffled list on Repeat all plays the same shuffled order again; reshuffle each round if that is noticed
        return if (o != null) o.first() else 0
    }

    private fun previousIndex(): Int? {
        val o = order
        return if (o != null) o.getOrNull(o.indexOf(index) - 1) else (index - 1).takeIf { it >= 0 }
    }

    // ---- loading

    private fun load(i: Int, keepWants: Boolean = false, byListener: Boolean = true) {
        loading?.cancel()
        index = i
        order = if (!shuffle) null else order?.let { o -> o.indexOf(i).takeIf { it >= 0 }?.let(o::drop) } ?: newOrder()
        loaded = false; buffering = true; failed = false; ended = false
        durationMs = queue[i].durationSec * 1000
        val at = startAtMs; startAtMs = 0
        _position.value = at
        publish(); save()
        val song = queue[i]
        plays.restart(song.id)
        recorder?.let { r -> r.listenLog.write(listens.moved(song.id, byListener)); scope.launch { r.history.onPlayed(song) } }
        loading = scope.launch(Dispatchers.IO) {
            try {
                val url = localFile(song) ?: resolver.get(song).url
                synchronized(lock) {
                    if (queue.getOrNull(index)?.id != song.id) return@launch
                    mpv.set("pause", !wants)
                    if (at > 0) mpv.command("loadfile", url, "replace", -1, "start=${at / 1000.0}") else mpv.command("loadfile", url, "replace")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { synchronized(lock) { fail(song) } }
        }
    }

    private fun fail(song: Song) {
        if (queue.getOrNull(index)?.id != song.id) return
        if (++failsOfThisSong == 1) { resolver.invalidate(song.id); load(index, keepWants = true); return } // a link that went stale gets one more try
        failsOfThisSong = 0
        failed = true; buffering = false
        publish()
        // a song that can't play moves on at once, unless it was the last
        nextIndex(automatic = false)?.let { if (wants) load(it, byListener = false) }
    }

    private fun stopAll() {
        queue = emptyList(); index = 0; order = null; wants = false; loaded = false; buffering = false; ended = false; failed = false
        mpv.command("stop"); _position.value = 0; publish(); save()
    }

    private fun onMpv(name: String, e: JSONObject) = synchronized(lock) {
        when (name) {
            "property-change" -> when (e.optString("name")) {
                "time-pos" -> if (!e.isNull("data")) {
                    val ms = (e.getDouble("data") * 1000).toLong(); val now = System.currentTimeMillis()
                    if (now - lastTick >= 400) { lastTick = now; _position.value = ms }
                }
                "duration" -> if (!e.isNull("data")) { durationMs = (e.getDouble("data") * 1000).toLong(); publish() }
                "paused-for-cache" -> { buffering = loaded && e.optBoolean("data"); publish() }
            }
            "playback-restart" -> {
                if (!loaded) {
                    loaded = true; buffering = false; failsOfThisSong = 0
                    publish()
                    nextIndex(automatic = true)?.let { n -> scope.launch { resolver.prefetch(queue[n]) } }
                }
            }
            "end-file" -> when (e.optString("reason")) {
                "eof" -> {
                    val n = nextIndex(automatic = true)
                    if (n != null) load(n, byListener = false) else { ended = true; wants = false; loaded = false; publish() }
                }
                "error" -> queue.getOrNull(index)?.let { fail(it) }
            }
        }
    }

    // ---- state

    private fun publish() {
        val cur = queue.getOrNull(index)
        _state.value = PlayerUiState(
            current = cur,
            isPlaying = wants && loaded && !buffering && !ended,
            wantsPlay = wants && !ended,
            ended = ended, buffering = buffering && wants, failed = failed,
            durationMs = durationMs, wholeMs = durationMs,
            queue = queue, currentIndex = index, shuffle = shuffle, repeatMode = repeat, order = order, from = from,
        )
    }

    private fun save() {
        val f = lastQueueFile ?: return
        if (queue.isEmpty()) { runCatching { f.delete() }; return }
        val text = LastQueue(queue, index, _position.value, shuffle, repeat, from).encode()
        scope.launch(Dispatchers.IO) { runCatching { f.parentFile?.mkdirs(); f.writeText(text) } }
    }

    fun close() { save(); recorder?.listenLog?.write(listens.moved(null, byListener = false)); mpv.close() }
}

/** What the player tells the library: a song started, how long it was heard, a play that counts. */
class PlayRecorder(val history: HistoryRecorder, val listenLog: ListenLog, val countPlay: suspend (String) -> Unit)
