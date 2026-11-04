package com.spotifry.music.player

import androidx.media3.common.Player

/** True when the queue is nearly finished and similar songs should be added for [lastId] (once per tail). */
fun shouldAppend(index: Int, size: Int, repeatMode: Int, alreadyFor: String?, lastId: String): Boolean =
    size > 0 && repeatMode == Player.REPEAT_MODE_OFF && index >= size - 2 && alreadyFor != lastId
