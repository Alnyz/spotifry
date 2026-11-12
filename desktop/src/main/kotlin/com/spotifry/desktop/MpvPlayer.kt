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
import com.spotifry.music.player.Trims
import com.spotifry.music.player.SmartTrims
import com.spotifry.music.player.fadeGain
import com.spotifry.music.player.trimFor
import com.spotifry.music.player.spreadShuffle
import com.spotifry.music.player.interleave
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
 * desktop screens use. Smart shuffle and smart trims are not here yet.
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
    /** Each song's own start and end, the same store the phone backup fills. */
    private val trims: Trims? = null,
    /** Talking at the start and end of music videos, from SponsorBlock, used while [smartTrimOn]. */
    private val smartTrims: SmartTrims? = null,
    private val smartTrimOn: () -> Boolean = { false },
    /** Seconds a song fades out at its end and the next fades in; 0 is off. */
    private val fadeSeconds: () -> Int = { 0 },
    /** Where mpv reads a song found online: its link, or replay storage ([ReplayStore]). */
    private val playFrom: (Song, com.spotifry.music.data.source.ResolvedStream) -> String = { _, s -> s.url },
    /** Songs like this one, for Smart shuffle. */
    private val related: suspend (Song) -> List<Song> = { emptyList() },
    /** Songs marked "Not this song": never suggested again. */
    private val notSuggested: () -> Set<String> = { emptySet() },
) : MusicPlayer, SleepTimer.Target {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state
    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position

    private val lock = Any()
    private var queue = emptyList<Song>()
    private var index = 0
    private var from: String? = null
    private var shuffle = false
    /** Smart shuffle: shuffle with a similar song after every three of yours. */
    private var smart = false
    private var filling = false
    private var repeat = RepeatMode.OFF
    /** Queue positions in play order, the playing one first. Null when the list plays as it is. */
    private var order: List<Int>? = null

    private var wants = false
    private var loaded = false
    private var buffering = false
    private var failed = false
    private var ended = false
    /** Length of the whole song; [trim] says which part of it plays. Positions outside mpv count from the trim's start. */
    private var wholeMs = 0L
    private var trim: Trim? = null
    private val startMs get() = trim?.startMs ?: 0
    private val durationMs get() = ((trim?.endMs ?: wholeMs) - startMs).coerceAtLeast(0)
    private var volume = 100
    private var gain = 1f
    /** The fade between songs, apart from the sleep timer's [gain]. */
    private var songGain = 1f
    /** The playing song began by running on from the one before, so it fades in. */
    private var ranOn = false
    /** mpv's sound filters by name, in the order the sound goes through them. */
    private val filters = linkedMapOf<String, String?>("loud" to null, "eq" to null, "quiet" to null)
    override var pauseAtEnd: (() -> Unit)? = null
    /** Where to start the next load, from a restored queue. */
    private var startAtMs = 0L
    /** Where the song being started begins, until mpv is told; a jump while starting moves it. */
    private var pendingStart = 0L
    /** A jump made after mpv was told to start the song but before it plays: done when it plays. */
    private var seekOnStart: Long? = null
    private var failsOfThisSong = 0
    private var loading: Job? = null
    private var lastTick = 0L
    private var codec: String? = null
    private var bitrate: Double? = null
    private var rate: Int? = null
    private val listens = ListenTracker()
    private val plays = PlayCounter()

    private val mpv = Mpv(mpvArgs, onEvent = ::onMpv).also {
        it.observe(1, "time-pos"); it.observe(2, "duration"); it.observe(3, "paused-for-cache")
        it.observe(4, "audio-codec-name"); it.observe(6, "audio-params/samplerate")
    }

    init {
        lastQueueFile?.let { f -> runCatching { f.readText() }.getOrNull()?.let(LastQueue::decode) }?.let { last ->
            queue = last.songs; index = last.index; from = last.from; shuffle = last.shuffle; repeat = last.repeatMode
            startAtMs = last.positionMs; _position.value = last.positionMs
            wholeMs = queue[index].durationSec * 1000; trim = trimOf(queue[index].id)
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

    fun setVolume(percent: Int): Unit = synchronized(lock) { volume = percent.coerceIn(0, 100); applyVolume() }

    override fun fade(gain: Float): Unit = synchronized(lock) { if (gain != this.gain) { this.gain = gain; applyVolume() } }

    private fun applyVolume(): Unit = mpv.set("volume", volume * gain * songGain)

    /** Turns a sound filter on ([spec], in mpv's words) or off (null). The whole chain is set again, which mpv does without a gap. */
    fun setFilter(name: String, spec: String?): Unit = synchronized(lock) {
        require(name in filters)
        if (filters[name] == spec) return
        filters[name] = spec
        // a change in the last moments of a song can keep mpv from ever ending it: the next song gets it instead
        if (loaded && !ended && durationMs - _position.value < 2_000) filtersWaiting = true else applyFilters()
    }

    private var filtersWaiting = false
    private fun applyFilters() { filtersWaiting = false; mpv.set("af", filters.mapNotNull { (n, f) -> f?.let { "@$n:$it" } }.joinToString(",")) }

    /** The trim a song plays with: the listener's own, else the smart one while that is on. */
    private fun trimOf(id: String): Trim? = trimFor(trims?.get(id), smartTrims?.get(id), smartTrimOn())

    override fun songLeftMs(): Long? = synchronized(lock) { if (loaded && !ended) durationMs - _position.value else null }

    /** [speed] 0.5 to 2, [pitch] in semitones; mpv keeps the two apart. */
    fun setSpeedPitch(speed: Float, pitch: Int) {
        mpv.set("speed", speed.toDouble())
        mpv.set("pitch", Math.pow(2.0, pitch / 12.0))
    }

    // ---- commands

    override fun playList(songs: List<Song>, startIndex: Int, from: String?) = synchronized(lock) {
        if (songs.isEmpty()) return
        queue = songs; this.from = from
        // shuffle stays on for a new list, but starts plain, as on the phone
        smart = false
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

    override fun seekTo(ms: Long): Unit = synchronized(lock) {
        _position.value = ms
        if (loaded) mpv.command("seek", (ms + startMs) / 1000.0, "absolute")
        else if (loading == null) startAtMs = ms
        // still starting: the song starts there, or jumps there as soon as it can if mpv already has it
        else { pendingStart = ms; seekOnStart = ms }
    }

    /** The button steps through off, Shuffle, Smart shuffle, off. */
    override fun cycleShuffle() = synchronized(lock) {
        when {
            !shuffle -> { shuffle = true; order = newOrder() }
            !smart -> smart = true
            else -> { shuffle = false; smart = false; dropSuggestions(); order = null }
        }
        publish(); save()
        fillSuggestions()
    }

    /** Shuffle off: suggestions leave the queue, except the one playing now, which finishes. */
    private fun dropSuggestions() {
        val cur = queue.getOrNull(index)
        val kept = queue.withIndex().filter { (i, s) -> !s.suggested || i == index }
        queue = kept.map { it.value }
        index = kept.indexOfFirst { it.index == index }.coerceAtLeast(0)
        if (cur == null) index = 0
    }

    /**
     * Smart shuffle: after the last suggestion still to come, one similar song after every three of yours. Runs when it is
     * turned on and at each song, so the mix keeps going through a long list. Offline or nothing found: plain Shuffle.
     */
    private fun fillSuggestions() {
        if (!smart || filling || !shuffle || queue.isEmpty()) return
        val ahead = (order ?: return).drop(1)
        val lastTip = ahead.indexOfLast { queue[it].suggested }
        if (lastTip >= 8 || ahead.size - lastTip - 1 < 3) return
        filling = true
        val seeds = (listOf(index) + ahead.drop(lastTip + 1).take(2)).map { queue[it] }
        scope.launch {
            try {
                val found = seeds.flatMap { s ->
                    try { related(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                synchronized(lock) {
                    // read again after the wait: the queue may have moved on
                    if (!smart || !shuffle) return@launch
                    val o = order ?: return@launch
                    val next = o.drop(1)
                    val stretch = next.drop(next.indexOfLast { queue[it].suggested } + 1)
                    val queued = queue.map { it.id }.toSet()
                    val no = notSuggested()
                    val tips = found.distinctBy { it.id }.filter { it.id !in queued && it.id !in no }.shuffled(random).take(stretch.size / 3)
                    if (tips.isEmpty()) return@launch
                    val start = queue.size
                    queue = queue + tips.map { it.copy(suggested = true) }
                    order = o.dropLast(stretch.size) + interleave(stretch, tips.indices.map { start + it })
                    publish(); save()
                }
            } finally { synchronized(lock) { filling = false } }
        }
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

    override fun setTrim(id: String, trim: Trim?, skip: Boolean) = synchronized(lock) {
        trims?.set(id, trim)
        // "Play the whole song" is meant for good: a smart trim doesn't come back
        if (trim == null) smartTrims?.turnOff(id)
        if (queue.getOrNull(index)?.id == id) applyTrim(trim, skip)
    }

    /** Gives the playing song [trim]; it goes on from the same moment unless that is now cut. */
    private fun applyTrim(trim: Trim?, skip: Boolean = false) {
        val at = _position.value + startMs
        this.trim = trim
        val end = trim?.endMs
        if (skip || (end != null && at >= end)) {
            val n = nextIndex(automatic = false)
            if (n != null) load(n) else { wants = false; mpv.set("pause", true); seekTo(0); publish() }
            return
        }
        // the same moment of the song goes on, or the new start when that is later
        if (loaded) {
            mpv.set("file-local-options/end", end?.let { "${it / 1000.0}" } ?: "none")
            if (at < startMs) mpv.command("seek", startMs / 1000.0, "absolute")
        }
        _position.value = (at - startMs).coerceAtLeast(0)
        if (!loaded) startAtMs = _position.value
        publish()
    }

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
        codec = null; bitrate = null; rate = null
        wholeMs = queue[i].durationSec * 1000
        trim = trimOf(queue[i].id)
        ranOn = !byListener && fadeSeconds() > 0
        songGain = if (ranOn) 0f else 1f; applyVolume()
        if (filtersWaiting) applyFilters()
        pendingStart = startAtMs; startAtMs = 0; seekOnStart = null
        _position.value = pendingStart
        publish(); save()
        val song = queue[i]
        plays.restart(song.id)
        recorder?.let { r -> r.listenLog.write(listens.moved(song.id, byListener)); scope.launch { r.history.onPlayed(song) } }
        lookUpSmartTrim(song)
        fillSuggestions()
        loading = scope.launch(Dispatchers.IO) {
            try {
                val local = localFile(song)
                val stream = if (local == null) resolver.get(song) else null
                synchronized(lock) {
                    if (queue.getOrNull(index)?.id != song.id) return@launch
                    val url = local ?: playFrom(song, stream!!)
                    // the source's own bitrate figure: mpv's live reading of a stream keeps moving, so it makes a restless label
                    bitrate = stream?.bitrateKbps?.takeIf { it > 0 }?.let { it * 1000.0 }
                    mpv.set("pause", !wants)
                    val at = pendingStart; seekOnStart = null
                    val opts = listOfNotNull(
                        (at + startMs).takeIf { it > 0 }?.let { "start=${it / 1000.0}" },
                        trim?.endMs?.let { "end=${it / 1000.0}" },
                    )
                    if (opts.isNotEmpty()) mpv.command("loadfile", url, "replace", -1, opts.joinToString(",")) else mpv.command("loadfile", url, "replace")
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

    private fun onMpv(name: String, e: JSONObject): Unit = synchronized(lock) {
        when (name) {
            "property-change" -> when (e.optString("name")) {
                "time-pos" -> if (!e.isNull("data")) {
                    val ms = (e.getDouble("data") * 1000).toLong() - startMs; val now = System.currentTimeMillis()
                    val g = fadeGain(ms, durationMs, fadeSeconds() * 1000L, ranOn)
                    if (kotlin.math.abs(g - songGain) > .01f || (g == 1f) != (songGain == 1f)) { songGain = g; applyVolume() }
                    if (now - lastTick >= 400) { lastTick = now; _position.value = ms }
                }
                "duration" -> if (!e.isNull("data")) { wholeMs = (e.getDouble("data") * 1000).toLong(); publish() }
                "paused-for-cache" -> { buffering = loaded && e.optBoolean("data"); publish() }
                "audio-codec-name" -> { codec = if (e.isNull("data")) null else e.optString("data"); publish() }
                "audio-params/samplerate" -> { rate = if (e.isNull("data")) null else e.optInt("data"); publish() }
            }
            "playback-restart" -> {
                if (!loaded) {
                    loaded = true; buffering = false; failsOfThisSong = 0
                    seekOnStart?.let { mpv.command("seek", (it + startMs) / 1000.0, "absolute"); seekOnStart = null }
                    publish()
                    nextIndex(automatic = true)?.let { n -> scope.launch { resolver.prefetch(queue[n]) }; lookUpSmartTrim(queue[n]) }
                }
            }
            "end-file" -> when (e.optString("reason")) {
                "eof" -> {
                    val n = nextIndex(automatic = true)
                    // the sleep timer's "end of this song": the next one waits, paused, at its start
                    val stop = pauseAtEnd?.also { pauseAtEnd = null; wants = false }
                    if (n != null) load(n, byListener = false) else { ended = true; wants = false; loaded = false; publish() }
                    stop?.invoke()
                }
                "error" -> queue.getOrNull(index)?.let { fail(it) }
            }
        }
    }

    /** Asks SponsorBlock about [song] once; a trim found for the song playing now is used at once. */
    private fun lookUpSmartTrim(song: Song) {
        val st = smartTrims ?: return
        if (!smartTrimOn() || trims?.get(song.id) != null) return
        scope.launch {
            runCatching { st.lookUp(song) }
            synchronized(lock) { if (queue.getOrNull(index)?.id == song.id && trim == null) st[song.id]?.let { applyTrim(it) } }
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
            durationMs = durationMs, wholeMs = wholeMs, trim = trim,
            queue = queue, currentIndex = index, shuffle = shuffle, smart = smart && shuffle, repeatMode = repeat, order = order, from = from,
            quality = if (loaded) qualityLabel(codec, bitrate, rate) else null,
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
