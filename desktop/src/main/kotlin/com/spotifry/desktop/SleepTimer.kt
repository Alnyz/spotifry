package com.spotifry.desktop

import com.spotifry.music.player.SleepState
import com.spotifry.music.player.fadeVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Fades the music out and pauses it after a while or at the end of the song, like the phone's. */
class SleepTimer(private val scope: CoroutineScope, private val target: Target, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** What the timer needs from the player. */
    interface Target {
        fun pause()
        /** Volume as a share of what the listener set, 0 to 1. */
        fun fade(gain: Float)
        /** Run once when the playing song reaches its end, instead of going on to the next one. */
        var pauseAtEnd: (() -> Unit)?
        /** Time left in the playing song, null when nothing plays. */
        fun songLeftMs(): Long?
    }

    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state
    private var job: Job? = null

    fun start(minutes: Int) = run(SleepState.Minutes(minutes, now() + minutes * 60_000L))
    fun startEndOfSong() = run(SleepState.EndOfSong)

    /** Time left, or null for "end of this song" and off. */
    fun remainingMs(): Long? = (_state.value as? SleepState.Minutes)?.let { it.endsAt - now() }

    fun off() = synchronized(this) {
        job?.cancel(); job = null
        target.pauseAtEnd = null
        target.fade(1f)
        _state.value = SleepState.Off
    }

    private fun run(s: SleepState) = synchronized(this) {
        off()
        _state.value = s
        if (s == SleepState.EndOfSong) target.pauseAtEnd = { off() }
        job = scope.launch {
            while (true) {
                synchronized(this@SleepTimer) {
                    // off() from the player's thread may have come in between
                    if (_state.value !== s) return@launch
                    val left = if (s is SleepState.Minutes) s.endsAt - now() else target.songLeftMs() ?: Long.MAX_VALUE
                    if (s is SleepState.Minutes && left <= 0) { target.pause(); off(); return@launch }
                    target.fade(fadeVolume(left))
                }
                delay(250)
            }
        }
    }
}
