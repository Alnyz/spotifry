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
import com.spotifry.music.MainActivity
import com.spotifry.music.MusicApp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appendedFor: String? = null
    private var levelFor: String? = null
    private val failures = FailureTracker()
    private val retries = OneRetryPerSong()
    private val plays = PlayCounter()

    override fun onCreate() {
        super.onCreate()
        val c = (application as MusicApp).container
        val http = OkHttpDataSource.Factory(c.client).setUserAgent(OkHttpDownloader.USER_AGENT)
        // songs not saved: kept in the song cache under their id, fetched from YouTube only when not there
        val cached = CacheDataSource.Factory().setCache(c.songCache)
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), SongDataSourceResolver(c.resolver, c.db.songs(), c::streamLow)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // saved songs play from their file and skip the cache, so they aren't stored twice; file:// goes to the file
        // reader, the music:// id of any other song goes to the cache
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, cached), SavedFileResolver(c.db.songs()))
        val loudness = LoudnessProcessor()
        val levels = LoudnessMemory(getSharedPreferences("loudness", MODE_PRIVATE))
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(arrayOf(loudness)).build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(10_000_000L)
        c.sleepTimer.attach(player)
        scope.launch { c.settings.sameLoudness.collect { loudness.enabled = it } }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // also fires when repeat-one starts the song again, which is a new play
                plays.restart(mediaItem?.mediaId)
                // keep the level measured for the song that just ended, start the new one at its remembered level
                levelFor?.let { id -> loudness.lastMeasuredDb?.let { levels.put(id, it) } }
                levelFor = mediaItem?.mediaId
                loudness.startSong(levelFor?.let(levels::get))
                mediaItem?.toSong()?.let { song -> scope.launch(Dispatchers.Default) { c.historyRecorder.onPlayed(song) } }
                // warm the stream URL of the next song so skipping is instant
                if (player.hasNextMediaItem()) {
                    val next = player.getMediaItemAt(player.nextMediaItemIndex).toSong()
                    scope.launch(Dispatchers.Default) { c.resolver.prefetch(next, c.streamLow()) }
                }
                appendSimilarIfNeeded(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    failures.onSuccess()
                    player.currentMediaItem?.mediaId?.let(retries::reset)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId.orEmpty()
                c.resolver.invalidate(id)
                // a link the server refuses (expired, or the phone switched networks): try a fresh one once before giving up on the song
                val refused = generateSequence(error.cause) { it.cause }.any { it is HttpDataSource.InvalidResponseCodeException }
                if (refused && retries.allow(id)) {
                    player.prepare()
                    player.play()
                    return
                }
                // a song that cannot load (blocked, removed, offline and unsaved) must not stall the queue
                player.currentMediaItem?.mediaMetadata?.title?.toString()?.let { c.events.failed.tryEmit(it) }
                if (failures.onFailure(player.mediaItemCount) && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
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
        session = MediaSession.Builder(this, player).setSessionActivity(open).setBitmapLoader(covers).build()
        scope.launch {
            while (true) {
                delay(1_000)
                val id = player.currentMediaItem?.mediaId
                if (plays.tick(id, player.isPlaying, 1_000) && id != null) launch(Dispatchers.IO) { c.db.plays().count(id, System.currentTimeMillis()) }
            }
        }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        (application as MusicApp).container.sleepTimer.detach()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
