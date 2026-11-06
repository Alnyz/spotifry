package com.spotifry.music.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * YouTube for everything, except that a song's audio comes from [saavn] when [enabled] and it has a sure match. Any miss,
 * error or slow answer goes to YouTube without a word. Low quality and files on the phone never ask.
 */
class ChoosingSource(
    private val youtube: MusicSource,
    private val saavn: suspend (Song) -> ResolvedStream?,
    private val enabled: () -> Boolean,
) : MusicSource by youtube {
    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
        if (enabled() && !low && !song.isLocal) {
            try {
                withTimeoutOrNull(MAX_WAIT_MS) { saavn(song) }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
        return youtube.resolve(song, low)
    }

    private companion object { const val MAX_WAIT_MS = 2_000L }
}
