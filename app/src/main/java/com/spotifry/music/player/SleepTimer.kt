package com.spotifry.music.player

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SleepState {
    data object Off : SleepState
    /** [endsAt] is on the [SystemClock.elapsedRealtime] clock, so it keeps counting while the phone sleeps. */
    data class Minutes(val minutes: Int, val endsAt: Long) : SleepState
    data object EndOfSong : SleepState
}

/** Volume over the last [FADE_MS] before the music stops: full until then, down to silent at the end. */
fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

/** "28:41", or "1:00:00" for an hour or more. */
fun clockText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

const val FADE_MS = 10_000L

/**
 * Fades the music out and pauses it after a while or at the end of the song. Lives as long as the app, and the
 * playback service hands it the player, so it keeps going with the screen off or the app closed.
 */
class SleepTimer(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state.asStateFlow()
    private var player: ExoPlayer? = null
    private var job: Job? = null
    /** The sleep fade alone, so a second voice can follow it too. */
    var sleepGain = 1f
        private set
    /** The fade between songs, multiplied with the sleep fade so the two never fight over the volume. */
    var songGain = 1f
        set(v) { field = v; player?.volume = sleepGain * v }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _state.value == SleepState.EndOfSong) off()
        }
    }

    fun attach(p: ExoPlayer) { player = p; p.addListener(listener) }
    fun detach() { off(); player?.removeListener(listener); player = null }

    fun start(minutes: Int) = run(SleepState.Minutes(minutes, now() + minutes * 60_000L))
    fun startEndOfSong() = run(SleepState.EndOfSong)

    /** Time left, or null for "end of this song" and off. */
    fun remainingMs(): Long? = (_state.value as? SleepState.Minutes)?.let { it.endsAt - now() }

    fun off() {
        job?.cancel(); job = null
        sleepGain = 1f
        player?.run { volume = songGain; pauseAtEndOfMediaItems = false }
        _state.value = SleepState.Off
    }

    private fun run(s: SleepState) {
        off()
        val p = player ?: return
        _state.value = s
        // the player's own stop at the end of the song; the loop below only fades
        p.pauseAtEndOfMediaItems = s == SleepState.EndOfSong
        job = scope.launch {
            while (true) {
                val left = when (s) {
                    is SleepState.Minutes -> s.endsAt - now()
                    else -> if (p.duration > 0) p.duration - p.currentPosition else Long.MAX_VALUE
                }
                if (s is SleepState.Minutes && left <= 0) { p.pause(); off(); return@launch }
                sleepGain = fadeVolume(left)
                p.volume = sleepGain * songGain
                delay(250)
            }
        }
    }
}
