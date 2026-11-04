package com.spotifry.music.player

import androidx.media3.common.Player

/** A rejected stream link (expired, or the phone changed network) is retried with a fresh link once per song. */
class OneRetryPerSong {
    private val tried = HashSet<String>()
    fun allow(songId: String): Boolean = tried.add(songId)
    fun reset(songId: String) { tried.remove(songId) }
}

/** After an error stops playback the player is idle and ignores play() until it is prepared again. */
fun needsPrepare(playbackState: Int): Boolean = playbackState == Player.STATE_IDLE

/** Songs the player could not load, so the screen can tell the person why the music skipped. */
class PlaybackEvents {
    val failed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
}
