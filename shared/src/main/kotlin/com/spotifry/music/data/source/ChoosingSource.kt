package com.spotifry.music.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * YouTube for everything, except that a song's audio comes from [lossless] when [losslessEnabled], else from [saavn]
 * when [enabled], if they have a sure match (lossless first). Any miss, error or slow answer goes to the next one and
 * at the end to YouTube, without a word. Low quality and files on the phone never ask.
 */
class ChoosingSource(
    private val youtube: MusicSource,
    private val saavn: suspend (Song) -> ResolvedStream?,
    private val enabled: () -> Boolean,
    private val lossless: suspend (Song) -> ResolvedStream? = { null },
    private val losslessEnabled: () -> Boolean = { false },
) : MusicSource by youtube {
    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
        if (!low && !song.isLocal) {
            if (losslessEnabled()) ask(lossless, song, LOSSLESS_WAIT_MS)?.let { return it }
            if (enabled()) ask(saavn, song, MAX_WAIT_MS)?.let { return it }
        }
        return youtube.resolve(song, low)
    }

    private suspend fun ask(source: suspend (Song) -> ResolvedStream?, song: Song, waitMs: Long): ResolvedStream? = try {
        withTimeoutOrNull(waitMs) { source(song) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    /** Lossless asks twice (search, then the file), so it gets longer than JioSaavn. */
    private companion object { const val MAX_WAIT_MS = 2_000L; const val LOSSLESS_WAIT_MS = 5_000L }
}
