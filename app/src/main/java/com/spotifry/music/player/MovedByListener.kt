package com.spotifry.music.player

/** Whether a move to another song came from the listener: not a song ending or repeating, and not stepping past a failure. */
fun movedByListener(reason: Int, afterFailure: Boolean) =
    !afterFailure && reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
