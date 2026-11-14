package com.spotifry.music.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import android.content.Context
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.source.ShuffleOrder
import android.os.Bundle
import com.google.common.util.concurrent.ListenableFuture
import com.spotifry.music.MainActivity
import com.spotifry.music.MusicApp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appendedFor: String? = null
    private var levelFor: String? = null
    private val failures = FailureTracker()
    private val retries = OneRetryPerSong()
    private val plays = PlayCounter()
    private val listens = ListenTracker()
    /** The next move on is the service stepping past a song that failed, not the listener skipping it. */
    private var movedByFailure = false
    /** The song is being swapped for itself with a new trim, not moved on from. */
    private var replacingSong = false
    /** The song now playing began by running on from the one before, so it fades in. */
    private var fadeIn = false
    /** Smart shuffle is on: one similar song after every three of yours. Turned on only by the Now playing button. */
    private var smart = false
    private var filling = false
    /** Overlapping songs on the beat; null while that setting is off. */
    private var join: BeatJoin? = null
    private val wakeTick = Channel<Unit>(Channel.CONFLATED)
    private var tailEqualizer: EqualizerProcessor? = null
    private var tailLoudness: LoudnessProcessor? = null
    private var tailSpatial: SpatialProcessor? = null
    private var mainSweep: SweepProcessor? = null
    private var tailSweep: SweepProcessor? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as MusicApp).container
        val http = OkHttpDataSource.Factory(c.client).setUserAgent(OkHttpDownloader.USER_AGENT)
        // songs not saved: kept in the song cache under their id, fetched from YouTube only when not there
        val cached = CacheDataSource.Factory().setCache(c.songCache)
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), SongDataSourceResolver(c.resolver, c.db.songs(), c::streamLow, c.songCache)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // saved songs play from their file and skip the cache, so they aren't stored twice; file:// goes to the file
        // reader, the music:// id of any other song goes to the cache
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, cached), SavedFileResolver(c.db.songs()))
        val equalizer = EqualizerProcessor()
        val loudness = LoudnessProcessor(c.spectrum)
        val spatial = SpatialProcessor()
        val sweep = SweepProcessor().also { mainSweep = it }
        val levels = LoudnessMemory(getSharedPreferences("loudness", MODE_PRIVATE))
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessorChain(SilenceChain(soundChain(equalizer, loudness, spatial, sweep)) { c.settings.silenceDb.value to c.settings.silenceCut.value }).build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // start after 0.75 s of music instead of 2.5 s, and after 2 s (not 5) when it ran dry mid-song
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(50_000, 50_000, 750, 2_000)
                // the size limit (12.5 MB for audio) is only ~20 s of Hi-Res FLAC; keep 50 s ahead whatever the size
                .setPrioritizeTimeOverSizeThresholds(true).build())
            .build()
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(10_000_000L)
        c.sleepTimer.attach(player)
        scope.launch { c.settings.sameLoudness.collect { loudness.enabled = it; tailLoudness?.enabled = it } }
        scope.launch { c.settings.skipSilence.collect { player.skipSilenceEnabled = it } }
        scope.launch {
            kotlinx.coroutines.flow.combine(c.settings.speed, c.settings.pitch) { s, p -> s to p }.collect { (s, p) ->
                // pitch in semitones: each one is the twelfth root of 2
                player.playbackParameters = androidx.media3.common.PlaybackParameters(s, 2.0.pow(p / 12.0).toFloat())
            }
        }

        scope.launch {
            kotlinx.coroutines.flow.combine(c.settings.eqOn, c.settings.eqLevels) { on, l -> if (on) l else null }.collect { equalizer.set(it); tailEqualizer?.set(it) }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(c.settings.mono, c.settings.spatial) { m, sp -> m to sp }.collect { (m, sp) -> spatial.set(m, sp); tailSpatial?.set(m, sp) }
        }
        // not immediate: a wake must run after the listener that sent it has finished, never inside it
        scope.launch(Dispatchers.Main) {
            // ponytail: polls the position 20 times a second while a fade is set and music plays; a timer per song end if battery shows it
            while (true) {
                val fadeMs = c.settings.fadeSeconds.value * 1000L
                val matching = fadeMs > 0
                if (matching && join == null) join = newJoin(player, dataSource, equalizer, loudness, levels)
                // null with the setting off, and whenever no join is under way: the fade below goes on as always
                val joined = join?.tick(matching)
                if (!matching && join?.idle == true) { join?.release(); join = null }
                val g = joined ?: if (fadeMs > 0) fadeGain(player.currentPosition, player.duration, fadeMs, fadeIn) else 1f
                if (g != c.sleepTimer.songGain) c.sleepTimer.songGain = g
                withTimeoutOrNull(if ((fadeMs > 0 && player.isPlaying) || join?.quick == true) 50L else 500L) { wakeTick.receive() }
            }
        }

        // one log line per stall (buffering after the song has started), to find out why Hi-Res freezes
        player.addListener(object : Player.Listener {
            private var started = false
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { started = false }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
                else if (state == Player.STATE_BUFFERING && started && player.playWhenReady) {
                    val id = player.currentMediaItem?.mediaId ?: return
                    android.util.Log.w("Stall", "song=$id origin=${c.songOrigin(id)} kbps=${c.streamKbps(id, player.duration.coerceAtLeast(0))} ahead=${(player.bufferedPosition - player.currentPosition) / 1000.0}s total=${player.totalBufferedDuration / 1000.0}s at=${player.currentPosition / 1000.0}s")
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(on: Boolean) {
                if (on) spreadOrder(player) else { smart = false; dropSuggestions(player) }
            }

            // repeat turned off (or to all) while the last songs play: fill the queue now, not at the next song
            override fun onRepeatModeChanged(repeatMode: Int) = appendSimilarIfNeeded(player)

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // a jump within the song or a skip with Next: no fade-in, the sound comes straight in
                if (reason == Player.DISCONTINUITY_REASON_SEEK) fadeIn = false
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // only when one song runs into the next (or repeats); Next and picking a song stay instant
                fadeIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                // also fires when repeat-one starts the song again, which is a new play
                plays.restart(mediaItem?.mediaId)
                // through a join the old song is still what's heard, on the tail voice, and the tick follows it; a new trim is the same listen
                if (join?.handingOver != true && !replacingSong) c.listenLog.write(listens.moved(mediaItem?.mediaId, movedByListener(reason, movedByFailure)))
                movedByFailure = false
                // keep the level measured for the song that just ended, start the new one at its remembered level
                levelFor?.let { id -> loudness.lastMeasuredDb?.let { levels.put(id, it) } }
                levelFor = mediaItem?.mediaId
                loudness.startSong(levelFor?.let(levels::get))
                mediaItem?.toSong()?.let { song -> scope.launch(Dispatchers.Default) { c.historyRecorder.onPlayed(song) } }
                // a better copy of an earlier song is swapped in now, unless it is the song heard or the one coming next
                c.upgrader.upgradeWhenFree(setOfNotNull(mediaItem?.mediaId, player.takeIf { it.hasNextMediaItem() }?.getMediaItemAt(player.nextMediaItemIndex)?.mediaId))
                // warm the stream URLs of the next few songs, so skipping (even twice) is instant: a lossless lookup takes seconds
                var ahead = player.currentMediaItemIndex
                for (n in 1..PREFETCH_AHEAD) {
                    ahead = player.currentTimeline.getNextWindowIndex(ahead, player.repeatMode, player.shuffleModeEnabled)
                    if (ahead == C.INDEX_UNSET) break
                    val song = player.getMediaItemAt(ahead).toSong()
                    scope.launch(Dispatchers.Default) { c.resolver.prefetch(song, c.streamLow()) }
                }
                if (player.hasNextMediaItem()) smartTrim(player, player.getMediaItemAt(player.nextMediaItemIndex).toSong().id)
                mediaItem?.mediaId?.let { smartTrim(player, it) }
                appendSimilarIfNeeded(player)
                fillSuggestions(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    failures.onSuccess()
                    player.currentMediaItem?.mediaId?.let(retries::reset)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId.orEmpty()
                // the player stopped all the same, so carry on; the bytes are gone, so the next song loads clean
                if (sourceChangedOnOtherSong(error, id)) {
                    player.prepare()
                    return
                }
                c.resolver.invalidate(id)
                // the bytes kept for this song are cut short or mixed: forget them and the note of where they came from, and load it again once
                if (savedCopyBroken(error) && retries.allow(id)) {
                    c.songCache.removeResource(id)
                    c.songCache.applyContentMetadataMutations(
                        id, androidx.media3.datasource.cache.ContentMetadataMutations().remove(SONG_SOURCE_KEY).remove(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH),
                    )
                    player.prepare()
                    return
                }
                // a link the server refuses (expired, or the phone switched networks): try a fresh one once before giving up on the song
                val refused = generateSequence(error.cause) { it.cause }.any { it is HttpDataSource.InvalidResponseCodeException || it is SourceChangedException }
                // prepare only: the player keeps wanting to play or not, so a Pause tapped while the song loaded still holds
                if (refused && retries.allow(id)) {
                    player.prepare()
                    return
                }
                // a song that cannot load (blocked, removed, offline and unsaved) must not stall the queue
                player.currentMediaItem?.mediaMetadata?.title?.toString()?.let { c.events.failed.tryEmit(it) }
                if (failures.onFailure(player.mediaItemCount) && player.hasNextMediaItem()) {
                    movedByFailure = true
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // notification and lock-screen cover: fetched like the app's own covers (same large size, same 50 MB cover cache),
        // so a cover already on screen shows at once, even offline. The player's own fetcher timed out and showed the app icon.
        val coverSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(c.imageClient))) { spec ->
            if (spec.uri.scheme == "https") spec.withUri(android.net.Uri.parse(com.spotifry.music.data.source.artAt(spec.uri.toString(), large = true))) else spec
        }
        val covers = androidx.media3.session.CacheBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader.Builder(this).setDataSourceFactory(coverSource).setMaximumOutputDimension(720).build(),
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).setBitmapLoader(covers)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, controller: MediaSession.ControllerInfo) =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(SMART_SHUFFLE, Bundle.EMPTY)).add(SessionCommand(SET_UPCOMING, Bundle.EMPTY)).build(),
                    ).build()

                override fun onCustomCommand(s: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (command.customAction) {
                        SMART_SHUFFLE -> {
                            smart = true
                            // already shuffled (the button steps through Shuffle first): only the suggestions are new
                            if (player.shuffleModeEnabled) fillSuggestions(player) else player.shuffleModeEnabled = true
                        }
                        // a drag (or an Undo) in Queue with shuffle on: the list stays as it is, the play order changes
                        SET_UPCOMING -> if (player.shuffleModeEnabled) {
                            val up = args.getIntArray(UPCOMING)?.toList().orEmpty()
                            withUpcoming(player.order(), player.currentMediaItemIndex, up)?.let {
                                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it.toIntArray(), kotlin.random.Random.nextLong()))
                            }
                        }
                        else -> return super.onCustomCommand(s, controller, command, args)
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Play from the widget (or a headset) while the app was closed: pick up the last queue where it stopped
                override fun onPlaybackResumption(s: MediaSession, controller: MediaSession.ControllerInfo) =
                    runCatching { java.io.File(filesDir, "last_queue.json").readText() }.getOrNull()?.let(LastQueue::decode)?.let { last ->
                        player.shuffleModeEnabled = last.shuffle
                        player.repeatMode = last.repeatMode
                        com.google.common.util.concurrent.Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(last.songs.map { it.toMediaItem() }, last.index, last.positionMs),
                        )
                    } ?: com.google.common.util.concurrent.Futures.immediateFailedFuture(UnsupportedOperationException("nothing played yet"))
            })
            .build()
        // the home screen widget follows the song, play/pause and the chosen colours
        var coverFor: String? = null
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (!events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) return
                val item = p.currentMediaItem ?: return
                val m = p.mediaMetadata
                Widget.show(this@PlaybackService, item.mediaId, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), p.isPlaying)
                if (coverFor == item.mediaId) return
                coverFor = item.mediaId
                covers.loadBitmapFromMetadata(m)?.let { f ->
                    f.addListener({
                        val b = runCatching { f.get() }.getOrNull() ?: return@addListener
                        if (p.currentMediaItem?.mediaId == item.mediaId) Widget.show(this@PlaybackService, item.mediaId, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), p.isPlaying, b)
                    }, androidx.core.content.ContextCompat.getMainExecutor(this@PlaybackService))
                }
            }
        })
        scope.launch {
            kotlinx.coroutines.flow.combine(c.settings.theme, c.settings.accent, c.settings.accentColor, c.settings.pureBlack) { _, _, _, _ -> }
                .collect { Widget.draw(this@PlaybackService) }
        }
        scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                val id = player.currentMediaItem?.mediaId
                if (plays.tick(id, player.isPlaying, 1_000) && id != null) {
                    launch(Dispatchers.IO) { c.db.plays().count(id, System.currentTimeMillis()) }
                    player.currentMediaItem?.toSong()?.let { song -> launch(Dispatchers.IO) { c.upgrader.onHeard(song) } }
                }
                val at = android.os.SystemClock.elapsedRealtime()
                c.listenLog.write(listens.tick(join?.tailSong ?: id, player.isPlaying || join?.tailHeard == true, at - last))
                last = at
            }
        }
    }

    /** Built only when the setting is on, so with it off nothing of this exists. */
    private fun newJoin(player: ExoPlayer, dataSource: androidx.media3.datasource.DataSource.Factory, equalizer: EqualizerProcessor, loudness: LoudnessProcessor, levels: LoudnessMemory): BeatJoin {
        val c = (application as MusicApp).container
        val quiet = Dispatchers.IO.limitedParallelism(1)
        return BeatJoin(
            player,
            newTail = { item, at ->
                // its own sound chain, as a chain can't serve two players. It holds the level the song is at now and remembers nothing.
                val eq = EqualizerProcessor().apply { set(if (c.settings.eqOn.value) c.settings.eqLevels.value else null) }
                val level = LoudnessProcessor().apply { enabled = c.settings.sameLoudness.value; leveler.hold(loudness.gainDb) }
                val spatial = SpatialProcessor().apply { set(c.settings.mono.value, c.settings.spatial.value) }
                tailEqualizer = eq; tailLoudness = level; tailSpatial = spatial
                val sweep = SweepProcessor().also { tailSweep = it }
                val renderers = object : DefaultRenderersFactory(this@PlaybackService) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessorChain(SilenceChain(soundChain(eq, level, spatial, sweep)) { c.settings.silenceDb.value to c.settings.silenceCut.value }).build()
                }
                ExoPlayer.Builder(this, renderers)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                    // no audio focus and no session: to the system and the user there's one player
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                    .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 20_000, 250, 1_000).build())
                    .build().apply {
                        skipSilenceEnabled = player.skipSilenceEnabled
                        playbackParameters = player.playbackParameters
                        volume = 0f
                        setMediaItem(item, at)
                        prepare()
                    }
            },
            look = { item, from, to ->
                val uri = item.localConfiguration?.uri
                if (uri == null) null else kotlinx.coroutines.withContext(quiet) {
                    // never in the way of the song playing; this thread is shared, so it's put back after
                    val was = android.os.Process.getThreadPriority(android.os.Process.myTid())
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    try {
                        // short enough that both songs can be read in the time before the join
                        kotlinx.coroutines.withTimeoutOrNull(15_000) { decodeStretch(dataSource, uri, item.mediaId, from, to) }
                            ?.let { d -> analyseBeats(d.mono, d.count, d.startMs)?.copy(whole = d.reachedEnd) }
                    } finally { android.os.Process.setThreadPriority(was) }
                }
            },
            scope = scope,
            fadeMs = { c.settings.fadeSeconds.value * 1000L },
            // skipping silence moves the music against the clock, so beats can't stay lined up; on data saver, nothing extra is read
            beatsAllowed = { !player.skipSilenceEnabled && !c.streamLow() },
            stopsAfterSong = { player.pauseAtEndOfMediaItems },
            sleepGain = { c.sleepTimer.sleepGain },
            wanted = { androidx.media3.common.PlaybackParameters(c.settings.speed.value, 2.0.pow(c.settings.pitch.value / 12.0).toFloat()) },
            wake = { wakeTick.trySend(Unit) },
            style = { TransitionStyle.of(c.settings.transition.value) },
            filter = { onTail, mode, hz -> (if (onTail) tailSweep else mainSweep)?.set(mode, hz) },
            // the song playing now is still being measured; the next one is only known from an earlier play
            matchDb = { outId, inId -> Leveler.matchDb(levels.get(outId) ?: loudness.lastMeasuredDb, levels.get(inId), c.settings.sameLoudness.value) },
        )
    }

    private fun appendSimilarIfNeeded(player: ExoPlayer) {
        val size = player.mediaItemCount
        if (size == 0) return
        val lastId = player.getMediaItemAt(size - 1).mediaId
        if (!shouldAppend(player.currentMediaItemIndex, size, player.repeatMode, appendedFor, lastId)) return
        appendedFor = lastId
        val c = (application as MusicApp).container
        scope.launch {
            val similar = try { c.source.related(player.getMediaItemAt(size - 1).toSong()) } catch (_: Exception) { return@launch }
            val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            similar.filter { it.id !in queued }.take(20).forEach { c.db.songs().upsert(it.toEntity()) }
            player.addMediaItems(similar.filter { it.id !in queued }.take(20).map { it.toMediaItem() })
        }
    }

    /**
     * Smart trim: asks the shared list about song [id] (once, then kept on the phone) and gives each of its places in the
     * queue the trim it should play with now. The song isn't held back while asking; if the answer comes after it
     * started, it goes on from the same moment, or from the new start if that moment is in the skipped talking.
     */
    private fun smartTrim(player: ExoPlayer, id: String) {
        val c = (application as MusicApp).container
        val song = (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == id }?.toSong() ?: return
        scope.launch {
            if (c.settings.smartTrim.value) c.smartTrims.lookUp(song)
            val trim = trimOf(id)
            // replacing moves a song to a random place in the shuffle order; put the order back after
            val order = if (player.shuffleModeEnabled) player.order() else null
            var changed = false
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                if (item.mediaId != id || item.trim == trim) continue
                changed = true
                val current = i == player.currentMediaItemIndex
                val at = player.currentPosition + (item.trim?.startMs ?: 0)
                replacingSong = current
                player.replaceMediaItem(i, item.toSong().toMediaItem(trim))
                replacingSong = false
                if (current) player.seekTo(i, (at - (trim?.startMs ?: 0)).coerceAtLeast(0))
            }
            if (changed && order != null && order.size == player.mediaItemCount) {
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), kotlin.random.Random.nextLong()))
            }
        }
    }

    /** Queue positions in the order they will play, from [from] on, or the whole order. */
    private fun ExoPlayer.order(from: Int? = null): List<Int> {
        val t = currentTimeline
        val out = mutableListOf<Int>()
        if (t.isEmpty) return out
        var i = from ?: t.getFirstWindowIndex(shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) { out += i; i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffleModeEnabled) }
        return out
    }

    /** Shuffle just turned on: the song playing stays first, the rest follow in the spread-out order (see [spreadShuffle]). */
    private fun spreadOrder(player: ExoPlayer) {
        val c = (application as MusicApp).container
        scope.launch {
            val recent = runCatching { c.recentlyPlayed() }.getOrDefault(emptySet())
            val n = player.mediaItemCount
            if (!player.shuffleModeEnabled || n < 2) return@launch
            val cur = player.currentMediaItemIndex
            val rest = spreadShuffle(
                (0 until n).filter { it != cur }, { player.getMediaItemAt(it).mediaMetadata.artist?.toString().orEmpty() },
                { player.getMediaItemAt(it).mediaId in recent }, kotlin.random.Random.Default,
            )
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder((listOf(cur) + rest).toIntArray(), kotlin.random.Random.nextLong()))
            fillSuggestions(player)
        }
    }

    /**
     * Smart shuffle: after the last suggestion still to come, one similar song after every three of yours. Runs when it
     * is turned on and again when the suggestions ahead run low, so the mix keeps going through a long list.
     * Offline or nothing found: it just plays on like plain Shuffle.
     */
    private fun fillSuggestions(player: ExoPlayer) {
        if (!smart || filling || !player.shuffleModeEnabled || player.mediaItemCount == 0) return
        val ahead = player.order(player.currentMediaItemIndex).drop(1)
        val lastTip = ahead.indexOfLast { player.getMediaItemAt(it).isSuggested }
        if (lastTip >= 8 || ahead.size - lastTip - 1 < 3) return
        filling = true
        val seeds = (listOf(player.currentMediaItemIndex) + ahead.drop(lastTip + 1).take(2)).map { player.getMediaItemAt(it).toSong() }
        val c = (application as MusicApp).container
        scope.launch {
            try {
                val found = seeds.flatMap { s ->
                    try { c.source.related(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                if (!smart || !player.shuffleModeEnabled) return@launch
                // read again after the wait: the queue may have moved on
                val order = player.order()
                val next = player.order(player.currentMediaItemIndex).drop(1)
                val stretch = next.drop(next.indexOfLast { player.getMediaItemAt(it).isSuggested } + 1)
                val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val no = c.notSuggested()
                val tips = found.distinctBy { it.id }.filter { it.id !in queued && it.id !in no }.shuffled().take(stretch.size / 3)
                if (tips.isEmpty()) return@launch
                val start = player.mediaItemCount
                player.addMediaItems(tips.map { it.copy(suggested = true).toMediaItem() })
                val mixed = order.dropLast(stretch.size) + interleave(stretch, tips.indices.map { start + it })
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(mixed.toIntArray(), kotlin.random.Random.nextLong()))
            } finally { filling = false }
        }
    }

    /** Shuffle off: suggestions leave the queue, except the one playing now, which finishes. */
    private fun dropSuggestions(player: ExoPlayer) {
        // ponytail: also drops suggestions already heard, so Previous can't go back to them; keep a heard set if that matters
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i != player.currentMediaItemIndex && player.getMediaItemAt(i).isSuggested) player.removeMediaItem(i)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    companion object {
        private const val PREFETCH_AHEAD = 3
        /** Sent by the Now playing button to turn on Smart shuffle. */
        const val SMART_SHUFFLE = "com.spotifry.music.SMART_SHUFFLE"
        /** Sets what plays after the current song while shuffle is on; [UPCOMING] holds the queue positions in order. */
        const val SET_UPCOMING = "com.spotifry.music.SET_UPCOMING"
        const val UPCOMING = "upcoming"
    }

    override fun onDestroy() {
        (application as MusicApp).container.listenLog.write(listens.moved(null, byListener = false))
        scope.cancel()
        join?.release()
        (application as MusicApp).container.sleepTimer.detach()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
