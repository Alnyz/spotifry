package com.spotifry.music.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How good a source's sound is: lossless, then JioSaavn's 320 kbps, then YouTube. 0 for a song not kept anywhere. */
fun qualityRank(origin: String?): Int = when (origin) { "monochrome" -> 3; "saavn" -> 2; "youtube" -> 1; else -> 0 }

/**
 * Every place a song can come from is asked at once, the lossless and JioSaavn ones given [BACKGROUND_WAIT_MS] to answer.
 * A song being played now starts from whichever answers first (usually YouTube); if a better copy arrives later,
 * [onBetter] hears of it so the stored copy can be upgraded between plays. A song looked up ahead of time
 * ([patient]) waits for the best answer instead, because nobody is waiting for it yet.
 * [prefer]: the origin of bytes already kept for the song, which it carries on from so one song never mixes two sources.
 * Low quality and files on the phone never ask.
 */
class ChoosingSource(
    private val youtube: MusicSource,
    private val saavn: suspend (Song) -> ResolvedStream?,
    private val enabled: () -> Boolean,
    private val lossless: suspend (Song) -> ResolvedStream? = { null },
    private val losslessEnabled: () -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Set after construction: the storage that upgrades songs is built from things this is built with. */
    var onBetter: (Song, ResolvedStream) -> Unit = { _, _ -> },
) : MusicSource by youtube {
    /** The best [qualityRank] the switches allow right now. */
    override val waitsForBest: Boolean get() = losslessEnabled() || enabled()

    fun bestRank(): Int = if (losslessEnabled()) 3 else if (enabled()) 2 else 1

    override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = resolve(song, low, patient = false, prefer = null)

    override suspend fun resolve(song: Song, low: Boolean, patient: Boolean, prefer: String?): ResolvedStream {
        val llOn = losslessEnabled()
        val jioOn = enabled()
        if (low || song.isLocal || (!llOn && !jioOn) || prefer == "youtube") return youtube.resolve(song, low)
        if (prefer != null) return carryOn(song, prefer, llOn, jioOn)
        val r = races.computeIfAbsent(song.id) { start(song, low, llOn, jioOn) }
        if (patient) {
            val chosen = r.ll?.let { withTimeoutOrNull(WAIT_MS) { it.await() } }
                ?: r.jio?.let { withTimeoutOrNull(WAIT_MS) { it.await() } }
                ?: r.yt.await().getOrThrow()
            // a lookup ahead settles for YouTube after 5 s, and that link is what plays: a lossless answer coming later must still be heard of
            offerLater(song, r, chosen)
            return chosen
        }
        val first = CompletableDeferred<ResolvedStream>()
        r.ll?.let { scope.launch { it.await()?.let(first::complete) } }
        r.jio?.let { scope.launch { it.await()?.let(first::complete) } }
        scope.launch {
            r.yt.await().onSuccess(first::complete).onFailure { e -> if (r.ll?.await() == null && r.jio?.await() == null) first.completeExceptionally(e) }
        }
        val chosen = first.await()
        offerLater(song, r, chosen)
        return chosen
    }

    /** The best copy better than [chosen] that answers later is passed to [onBetter], for the next play. */
    private fun offerLater(song: Song, r: Race, chosen: ResolvedStream) = scope.launch {
        listOfNotNull(r.ll, r.jio).mapNotNull { it.await() }.filter { qualityRank(it.origin) > qualityRank(chosen.origin) }
            .maxByOrNull { qualityRank(it.origin) }?.let { onBetter(song, it) }
    }

    private class Race(val ll: Deferred<ResolvedStream?>?, val jio: Deferred<ResolvedStream?>?, val yt: Deferred<Result<ResolvedStream>>)

    /** The lookups running for a song, shared: a song tapped while one is running takes whatever answers first, not the whole wait. */
    private val races = ConcurrentHashMap<String, Race>()

    private fun start(song: Song, low: Boolean, llOn: Boolean, jioOn: Boolean): Race {
        val race = Race(
            // a late answer still counts: the song starts from whoever is first, and a better copy arriving later is offered as the upgrade
            if (llOn) scope.async { ask(lossless, song, BACKGROUND_WAIT_MS) } else null,
            if (jioOn) scope.async { ask(saavn, song, BACKGROUND_WAIT_MS) } else null,
            scope.async { runCatching { youtube.resolve(song, low) } },
        )
        scope.launch { race.ll?.await(); race.jio?.await(); race.yt.await(); races.remove(song.id) }
        return race
    }

    /** A better copy of a song kept at quality [than] (an origin), or null. For a song already playing; YouTube is not asked. */
    suspend fun findBetter(song: Song, than: String?): ResolvedStream? {
        if (song.isLocal) return null
        // nobody waits for this one, so the slow lossless server gets longer than a tap would give it
        val ll = if (losslessEnabled() && qualityRank(than) < 3) scope.async { ask(lossless, song, BACKGROUND_WAIT_MS) } else null
        val jio = if (enabled() && qualityRank(than) < 2) scope.async { ask(saavn, song, BACKGROUND_WAIT_MS) } else null
        return ll?.await() ?: jio?.await()
    }

    /** Bytes of [prefer] are already kept: ask that one first, then the ones below it, the rest being YouTube. */
    private suspend fun carryOn(song: Song, prefer: String, llOn: Boolean, jioOn: Boolean): ResolvedStream {
        if (prefer == "monochrome" && llOn) ask(lossless, song)?.let { return it }
        if (prefer != "youtube" && jioOn) ask(saavn, song)?.let { return it }
        return youtube.resolve(song, false)
    }

    private suspend fun ask(source: suspend (Song) -> ResolvedStream?, song: Song, waitMs: Long = WAIT_MS): ResolvedStream? = try {
        withTimeoutOrNull(waitMs) { source(song) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    private companion object { const val WAIT_MS = 5_000L; const val BACKGROUND_WAIT_MS = 25_000L }
}
