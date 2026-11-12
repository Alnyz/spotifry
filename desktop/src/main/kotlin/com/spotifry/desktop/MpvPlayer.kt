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
import com.spotifry.music.player.BeatGrid
import com.spotifry.music.player.JoinPlan
import com.spotifry.music.player.planJoin
import com.spotifry.music.player.plainJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The desktop's player: the queue lives here, the sound is made by mpv. Same behaviour as the phone's player for what the
 * desktop screens use. Smart shuffle and smart trims are not here yet.
 */
class MpvPlayer(
    private val resolver: StreamResolver,
    private val scope: CoroutineScope,
    private val lastQueueFile: File? = null,
    private val mpvArgs: List<String> = emptyList(),
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
    /**
     * Where mpv reads a song found online: its link, or replay storage ([ReplayStore]). Alongside: the next song, opened
     * while the one before is still heard.
     */
    private val playFrom: (Song, com.spotifry.music.data.source.ResolvedStream, Boolean) -> String = { _, s, _ -> s.url },
    /** Songs like this one, for Smart shuffle and for when the queue runs out. */
    private val related: suspend (Song) -> List<Song> = { emptyList() },
    /** Songs marked "Not this song": never suggested again. */
    private val notSuggested: () -> Set<String> = { emptySet() },
    /** Whether songs may be joined on the beat; skipping quiet parts moves the music against the clock, so not then. */
    private val beatsAllowed: () -> Boolean = { true },
    /** The beats of a stretch of a song at a link or file, from its start and end in ms. */
    private val beats: suspend (String, Long, Long) -> BeatGrid? = { url, from, to -> kotlinx.coroutines.withContext(Dispatchers.IO) { readBeats(url, from, to) } },
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
    /** The last song similar songs were asked for, so each end of the queue asks once. */
    private var appendedFor: String? = null
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

    private var stage: Stage = Watching
    /** The playing song's place as mpv last said it, and when (nanoTime). */
    private var posNow = 0L
    private var posAt = 0L
    private val grids = HashMap<String, BeatGrid>()
    /** What mpv is reading for the playing song: a file, or replay storage's link. */
    private var playingUrl: String? = null
    private var speed = 1f
    @Volatile private var closed = false
    private var pitchRatio = 1.0

    /** The mpv the listener hears; during a join the next song's mpv takes its place. */
    private var mpv: Mpv = newMpv().also(::observeAll)

    private fun newMpv(): Mpv {
        lateinit var m: Mpv
        // events before m is set can only be mpv starting up; the listener drops them
        m = Mpv(mpvArgs) { name, e -> onMpv(m, name, e) }
        m.observe(1, "time-pos")
        return m
    }

    /** What the screens show of a song; observing sends the value at once, so a song already playing in a voice is caught up. */
    private fun observeAll(m: Mpv) {
        m.observe(2, "duration"); m.observe(3, "paused-for-cache"); m.observe(4, "audio-codec-name"); m.observe(6, "audio-params/samplerate")
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
            while (!closed) {
                val quick = synchronized(lock) { runCatching { joinTick() }.onFailure { log("tick failed: $it") }.getOrDefault(false) }
                delay(if (quick) TICK_MS else 500)
            }
        }
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

    private fun applyVolume() {
        mpv.set("volume", volume * gain * songGain)
        (stage as? Fading)?.let { it.tail.set("volume", volume * gain * it.gain) }
    }

    /** Every mpv sounding or about to: the playing one, the next song's voice and the old song fading out. */
    private fun all(): List<Mpv> = listOfNotNull(mpv, (stage as? Waiting)?.voice?.mpv, (stage as? Fading)?.tail)

    private fun pauseAll(paused: Boolean) {
        mpv.set("pause", paused)
        // a voice not started yet waits for its moment, paused
        when (val s = stage) { is Waiting -> s.voice?.takeIf { it.started }?.mpv?.set("pause", paused); is Fading -> s.tail.set("pause", paused); else -> {} }
    }

    /** Turns a sound filter on ([spec], in mpv's words) or off (null). The whole chain is set again, which mpv does without a gap. */
    fun setFilter(name: String, spec: String?): Unit = synchronized(lock) {
        require(name in filters)
        if (filters[name] == spec) return
        filters[name] = spec
        // a change in the last moments of a song can keep mpv from ever ending it: the next song gets it instead
        if (loaded && !ended && durationMs - _position.value < 2_000) filtersWaiting = true else applyFilters()
    }

    private var filtersWaiting = false
    private fun chain() = filters.mapNotNull { (n, f) -> f?.let { "@$n:$it" } }.joinToString(",")
    private fun applyFilters() { filtersWaiting = false; val c = chain(); all().forEach { it.set("af", c) } }

    /** The trim a song plays with: the listener's own, else the smart one while that is on. */
    private fun trimOf(id: String): Trim? = trimFor(trims?.get(id), smartTrims?.get(id), smartTrimOn())

    override fun songLeftMs(): Long? = synchronized(lock) { if (loaded && !ended) durationMs - _position.value else null }

    /** [speed] 0.5 to 2, [pitch] in semitones; mpv keeps the two apart. */
    fun setSpeedPitch(speed: Float, pitch: Int): Unit = synchronized(lock) {
        this.speed = speed; pitchRatio = Math.pow(2.0, pitch / 12.0)
        all().forEach { it.set("speed", speed.toDouble()); it.set("pitch", pitchRatio) }
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
            else -> { wants = !wants; pauseAll(!wants); publish() }
        }
    }

    override fun pause() = synchronized(lock) { wants = false; pauseAll(true); publish() }

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
        letGo("jump")
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

    /**
     * As on the phone: with one song or none left to play, up to 20 songs like the last one are added. Not on Repeat one, and
     * on Repeat all only for a single song (that song alone is a mix to grow, then the mix repeats).
     */
    private fun appendSimilarIfNeeded() {
        if (queue.isEmpty()) return
        val o = order
        val ahead = if (o != null) o.size - 1 else queue.size - 1 - index
        val last = queue[o?.last() ?: queue.lastIndex]
        val repeats = repeat == RepeatMode.ONE || (repeat == RepeatMode.ALL && queue.size > 1)
        if (repeats || ahead > 1 || appendedFor == last.id) return
        appendedFor = last.id
        scope.launch {
            val similar = try { related(last) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return@launch }
            synchronized(lock) {
                val queued = queue.map { it.id }.toSet()
                val add = similar.distinctBy { it.id }.filter { it.id !in queued }.take(20)
                if (add.isEmpty()) return@launch
                val start = queue.size
                queue = queue + add
                order = order?.let { it + add.indices.map { i -> start + i } }
                publish(); save()
            }
        }
    }

    override fun cycleRepeat() = synchronized(lock) {
        repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; else -> RepeatMode.OFF }
        publish(); save()
        // repeat turned off while the last songs play: fill the queue now, not at the next song
        appendSimilarIfNeeded()
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
        letGo("trim changed")
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
        synchronized(lock) { wants = true; pauseAll(false); publish() }
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

    /** Starts song [i]; a join passes the [voice] already playing it, which becomes the mpv heard. */
    private fun load(i: Int, keepWants: Boolean = false, byListener: Boolean = true, voice: Voice? = null) {
        loading?.cancel()
        if (voice == null) letGo("another song")
        index = i
        order = if (!shuffle) null else order?.let { o -> o.indexOf(i).takeIf { it >= 0 }?.let(o::drop) } ?: newOrder()
        loaded = voice != null; buffering = voice == null; failed = false; ended = false
        codec = null; bitrate = voice?.bitrate; rate = null
        wholeMs = queue[i].durationSec * 1000
        trim = if (voice != null) voice.trim else trimOf(queue[i].id)
        ranOn = voice == null && !byListener && fadeSeconds() > 0
        if (voice != null) { mpv = voice.mpv; observeAll(mpv); posNow = voice.posMs; posAt = voice.posAt; playingUrl = voice.url }
        // through a join the overlap sets the new song's volume, from silent
        songGain = if (ranOn || voice != null) 0f else 1f; applyVolume()
        if (filtersWaiting) applyFilters()
        pendingStart = startAtMs; startAtMs = 0; seekOnStart = null
        _position.value = voice?.posMs ?: pendingStart
        publish(); save()
        val song = queue[i]
        plays.restart(song.id)
        recorder?.let { r -> r.listenLog.write(listens.moved(song.id, byListener)); scope.launch { r.history.onPlayed(song) } }
        lookUpSmartTrim(song)
        fillSuggestions()
        appendSimilarIfNeeded()
        if (voice != null) return
        loading = scope.launch(Dispatchers.IO) {
            try {
                val local = localFile(song)
                val stream = if (local == null) resolver.get(song) else null
                synchronized(lock) {
                    if (queue.getOrNull(index)?.id != song.id) return@launch
                    val url = local ?: playFrom(song, stream!!, false)
                    playingUrl = url
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
        letGo("stopped")
        queue = emptyList(); index = 0; order = null; wants = false; loaded = false; buffering = false; ended = false; failed = false
        mpv.command("stop"); _position.value = 0; publish(); save()
    }

    private fun onMpv(src: Mpv, name: String, e: JSONObject): Unit = synchronized(lock) {
        if (src !== mpv) { onOtherMpv(src, name, e); return }
        when (name) {
            "property-change" -> when (e.optString("name")) {
                "time-pos" -> if (!e.isNull("data")) {
                    val ms = (e.getDouble("data") * 1000).toLong() - startMs; val now = System.currentTimeMillis()
                    posNow = ms; posAt = System.nanoTime()
                    // a join sets the volume itself: the plain fade-out stays off near the join, and the new song comes in with the overlap
                    val held = stage is Fading || (stage is Waiting && durationMs - ms < fadeSeconds() * 1000L)
                    val g = if (held) songGain else fadeGain(ms, durationMs, fadeSeconds() * 1000L, ranOn)
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

    // ---- joins: with Crossfade on, the next song comes in under the end of this one, on the beat when both have one

    /**
     * The next song in its own mpv, loaded early and silent. It starts a little before the overlap and is brought into step
     * while still unheard, then it becomes the mpv heard and the old one fades out as the tail.
     */
    private class Voice(val mpv: Mpv, val trim: Trim?, val url: String, val bitrate: Double?) {
        val startMs get() = trim?.startMs ?: 0
        var ready = false; var failed = false; var started = false
        var posMs = 0L; var posAt = 0L
    }

    private sealed interface Stage
    private data object Watching : Stage
    /** Nothing more for this pair of songs: it ends as it would without joins. */
    private class Done(val key: String) : Stage
    private class Looking(val key: String) : Stage
    private class Waiting(val key: String, val plan: JoinPlan, val next: Int, val song: Song, val trim: Trim?, val url: String, val bitrate: Double?) : Stage {
        var voice: Voice? = null; var making = false; var inStep = false; var ran = 0; val line = Liner()
    }
    private class Fading(val plan: JoinPlan, val tail: Mpv) : Stage { var gain = 1f; var ended = false }

    private fun log(text: String) = System.err.println("join: $text")

    /** Where a song is now: mpv says it every so often, and in between it moves on with the clock. */
    private fun now(ms: Long, at: Long, moving: Boolean) =
        if (moving && at > 0) ms + (minOf((System.nanoTime() - at) / 1_000_000, 500L) * speed).toLong() else ms

    /** This song and the one that follows it on its own, or null when none will (repeat one, the sleep timer, the end). */
    private fun pairKey(): String? {
        if (repeat == RepeatMode.ONE || pauseAtEnd != null || !loaded || ended) return null
        val n = nextIndex(automatic = true) ?: return null
        return if (queue[n].id == queue[index].id) null else "$index ${queue[index].id} $n ${queue[n].id}"
    }

    /** Runs 20 times a second while a join is near or under way, else twice; says which. */
    private fun joinTick(): Boolean {
        if (fadeSeconds() <= 0) { letGo("crossfade off"); return false }
        val pos = now(posNow, posAt, wants && loaded && !buffering)
        when (val s = stage) {
            Watching -> {
                if (!wants || !loaded || ended) return false
                val left = durationMs - pos
                if (left > LOOK_BEFORE_END_MS * speed) return false
                val key = pairKey() ?: return false
                if (left < MIN_LEFT_MS * speed) stage = Done(key) else plan(key)
            }
            is Done -> if (pairKey() != s.key) stage = Watching
            is Looking -> if (pairKey() != s.key) stage = Watching
            is Waiting -> return waiting(s, pos)
            is Fading -> return fading(s, pos)
        }
        return false
    }

    /** Reads the end of this song and the start of the next, and picks the join; until then the songs are [Looking]. */
    private fun plan(key: String) {
        val looking = Looking(key).also { stage = it }
        val n = nextIndex(automatic = true) ?: return
        val next = queue[n]; val nextTrim = trimOf(next.id)
        val outMs = durationMs; val outStart = startMs; val outId = queue[index].id; val outUrl = playingUrl
        val inStart = nextTrim?.startMs ?: 0
        val inMs = nextTrim?.endMs?.let { it - inStart } ?: (next.durationSec * 1000L - inStart).coerceAtLeast(0)
        val fade = fadeSeconds() * 1000L
        val lead = (JOIN_LEAD_MS * speed).toLong()
        val onBeat = beatsAllowed()
        scope.launch(Dispatchers.IO) {
            val waiting = try {
                val local = localFile(next)
                val stream = if (local == null) resolver.get(next) else null
                // opened alongside: the song heard now keeps its copy, and the next one starts coming in
                val url = local ?: synchronized(lock) { if (stage !== looking) return@launch; playFrom(next, stream!!, true) }
                val bitrate = stream?.bitrateKbps?.takeIf { it > 0 }?.let { it * 1000.0 }
                val plan = if (!onBeat) plainJoin(outMs, outMs, inMs, fade, 0) else {
                    // a read cut short can't say where the song's sound ends, and its beats would be guessed far ahead: not used
                    val out = outUrl?.let { look(outId, it, (outStart + outMs - STRETCH_MS).coerceAtLeast(outStart), outStart + outMs) }?.takeIf { it.whole }?.shifted(-outStart)
                    val inn = look(next.id, url, inStart, inStart + STRETCH_MS)?.shifted(-inStart)
                    planJoin(out, inn, outMs, inMs, fade, lead)
                }
                plan?.let { Waiting(key, it, n, next, nextTrim, url, bitrate) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("couldn't plan: $e"); null }
            synchronized(lock) {
                if (stage !== looking) return@launch
                stage = waiting ?: Done(key)
                log("$key: ${waiting?.plan}")
            }
        }
    }

    private suspend fun look(id: String, url: String, from: Long, to: Long): BeatGrid? {
        val key = "$id $from"
        synchronized(lock) { grids[key] }?.let { return it }
        val g = try { beats(url, from, to) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        log("read $id from $from: ${g?.bpm?.toInt()} bpm, sure ${g?.confidence}")
        // a failure isn't kept: a stream that timed out may read fine next time
        if (g != null && g.whole) synchronized(lock) { if (grids.size > 16) grids.clear(); grids[key] = g }
        return g
    }

    private fun waiting(s: Waiting, pos: Long): Boolean {
        if (pairKey() != s.key) { letGo("queue changed"); return false }
        val handover = s.plan.startMs - s.plan.leadMs
        val prime = handover - (PRIME_MS * speed).toLong()
        val v = s.voice
        if (v == null) {
            if (pos >= prime && !s.making) makeVoice(s)
            if (pos > handover + SLACK_MS * speed) { log("voice too late"); stage = Done(s.key) }
            return pos >= prime - 1_000
        }
        if (v.failed) { log("the next song failed in its voice"); drop(v.mpv, 0f); stage = Done(s.key); return false }
        if (!v.started) {
            if (pos < handover) return true
            if (!v.ready) {
                if (pos > handover + SLACK_MS * speed) { log("voice wasn't ready"); drop(v.mpv, 0f); stage = Done(s.key) }
                return true
            }
            v.started = true; s.ran = 0
            v.mpv.set("pause", !wants)
            log("voice starts at $pos")
        }
        // brought into step while unheard: a jump when far off, else running it a little fast or slow
        if (s.plan.onBeat && !s.inStep && wants && ++s.ran >= SETTLE_TICKS) {
            val off = (now(v.posMs, v.posAt, true) - s.plan.cueMs) - (pos - s.plan.startMs)
            if (abs(off) > SEEK_OVER_MS) { v.mpv.command("seek", (pos - s.plan.startMs + s.plan.cueMs + v.startMs) / 1000.0, "absolute"); s.ran = 0 }
            else if (s.line.step(v.mpv, off, speed)) { s.inStep = true; log("in step, off by ${s.line.last} ms") }
        }
        if (pos >= s.plan.startMs) {
            if (s.plan.onBeat && !s.inStep) log("not in step by the overlap, last off by ${s.line.last} ms")
            v.mpv.set("speed", speed.toDouble())
            stage = Fading(s.plan, mpv)
            load(s.next, byListener = false, voice = v)
            log("overlap starts")
        }
        return true
    }

    /** Starts the next song's mpv, paused at where it comes in, so it's ready to go. */
    private fun makeVoice(s: Waiting) {
        s.making = true
        scope.launch(Dispatchers.IO) {
            val m = try { newMpv() } catch (e: Exception) { synchronized(lock) { if (stage === s) stage = Done(s.key) }; return@launch }
            synchronized(lock) {
                if (stage !== s) { m.close(); return@launch }
                val v = Voice(m, s.trim, s.url, s.bitrate)
                s.voice = v
                m.set("volume", 0); m.set("pause", true); m.set("speed", speed.toDouble()); m.set("pitch", pitchRatio); m.set("af", chain())
                val at = (s.plan.cueMs - s.plan.leadMs).coerceAtLeast(0) + v.startMs
                val opts = listOfNotNull("start=${at / 1000.0}", s.trim?.endMs?.let { "end=${it / 1000.0}" })
                m.command("loadfile", s.url, "replace", -1, opts.joinToString(","))
            }
        }
    }

    /** The overlap: the old song (the tail) goes down as the new one comes up, the two together about as loud as one. */
    private fun fading(s: Fading, pos: Long): Boolean {
        val f = ((pos - s.plan.cueMs).toFloat() / s.plan.lengthMs).coerceIn(0f, 1f)
        if (f >= 1f || s.ended) { drop(s.tail, 0f); stage = Watching; songGain = 1f; applyVolume(); log("overlap done"); return false }
        val cut = 1 - OVERLAP_CUT * sin(f * PI).toFloat()
        s.gain = cos(f * PI / 2).toFloat() * cut
        songGain = sin(f * PI / 2).toFloat() * cut
        applyVolume()
        return true
    }

    /** The listener moved (another song, a jump) or something changed: whatever join was under way stops, gently. */
    private fun letGo(why: String) {
        when (val s = stage) {
            Watching, is Done -> return
            is Waiting -> s.voice?.let { drop(it.mpv, 0f) }
            is Fading -> { drop(s.tail, volume * gain * s.gain); songGain = 1f; stage = Watching; applyVolume() }
            is Looking -> {}
        }
        log("let go: $why")
        stage = Watching
    }

    private fun onOtherMpv(src: Mpv, name: String, e: JSONObject) {
        val s = stage
        (s as? Waiting)?.voice?.takeIf { it.mpv === src }?.let { v ->
            when (name) {
                "property-change" -> if (e.optString("name") == "time-pos" && !e.isNull("data")) {
                    v.posMs = (e.getDouble("data") * 1000).toLong() - v.startMs; v.posAt = System.nanoTime()
                }
                "playback-restart" -> v.ready = true
                "end-file" -> if (e.optString("reason") == "error") v.failed = true
            }
        }
        if (s is Fading && s.tail === src && name == "end-file") s.ended = true
    }

    /** A few steps down to silent, so letting a song go doesn't click, then its mpv closes. */
    private fun drop(m: Mpv, from: Float) {
        scope.launch {
            for (k in 2 downTo 0) { runCatching { m.set("volume", from * k / 3) }; delay(TICK_MS) }
            m.close()
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

    fun close() {
        save(); recorder?.listenLog?.write(listens.moved(null, byListener = false))
        synchronized(lock) { closed = true; all().forEach { it.close() } }
    }
}

/** What the player tells the library: a song started, how long it was heard, a play that counts. */
class PlayRecorder(val history: HistoryRecorder, val listenLog: ListenLog, val countPlay: suspend (String) -> Unit)

/** Analysis starts this long before the end: both songs are read in the time left. */
private const val LOOK_BEFORE_END_MS = 45_000L
private const val STRETCH_MS = 30_000L
private const val MIN_LEFT_MS = 6_000L
/** Time for the new song to start and be brought into step, unheard, before the overlap. */
private const val JOIN_LEAD_MS = 2_500L
/** The next song's mpv starts this long before that, paused, so it's loaded when its moment comes. */
private const val PRIME_MS = 6_000L
private const val SLACK_MS = 1_500L
private const val TICK_MS = 50L
/** Ticks a voice must have been running for its position to be worth reading. */
private const val SETTLE_TICKS = 3
/** Ticks one reading is averaged over. */
private const val AVERAGE_TICKS = 6
private const val IN_STEP_MS = 40L
private const val CATCH_UP = .25f
/** Further off than this it's quicker to jump than to catch up. */
private const val SEEK_OVER_MS = 600L
/** Both songs dip this much at the middle of the overlap, as two loud songs together can pass full level. */
private const val OVERLAP_CUT = .2f

/**
 * Closes the gap between two songs by running the unheard one a little fast or slow for just long enough: mpv keeps its
 * pitch. It measures, corrects, then measures again, so it can't start hunting.
 */
private class Liner {
    private val readings = ArrayList<Long>()
    private var slewing = 0
    var last = 0L; private set

    /** Fed one gap per tick ([off] ms, behind when below 0); true once the average is within [IN_STEP_MS]. */
    fun step(m: Mpv, off: Long, speed: Float): Boolean {
        if (slewing > 0) { if (--slewing == 0) m.set("speed", speed.toDouble()); return false }
        readings += off
        if (readings.size < AVERAGE_TICKS) return false
        last = readings.average().toLong()
        readings.clear()
        if (abs(last) <= IN_STEP_MS) return true
        slewing = (abs(last) / CATCH_UP / speed / TICK_MS).toInt().coerceAtLeast(1)
        m.set("speed", speed * (if (last < 0) 1 + CATCH_UP else 1 - CATCH_UP).toDouble())
        return false
    }
}
