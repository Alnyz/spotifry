package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What Home shows for the picked artists: each one's page looked up if needed, and a few songs from each. */
class ArtistSongs(
    private val source: MusicSource,
    private val now: () -> Long = System::currentTimeMillis,
    parallel: Int = PARALLEL,
    /** Which songs of a page are picked; injectable so tests are stable. */
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    class Loaded(val artists: List<PickedArtist>, val songs: List<Song>)

    private val gate = Semaphore(parallel)
    private class Entry(val artist: PickedArtist, val songs: List<Song>)
    private val cache = HashMap<String, Entry>()
    private val failedAt = HashMap<String, Long>()

    /**
     * Songs come from the artist pages, at most [PARALLEL] requests at a time and only for the first [MAX_ARTISTS] artists.
     * An answer is kept until the app closes, so Home shows the same songs all run and opening it again costs nothing; a failed
     * one isn't asked again for [RETRY_MS].
     */
    suspend fun load(artists: List<PickedArtist>): Loaded = coroutineScope {
        val got = artists.take(MAX_ARTISTS).map { async { one(it) } }.awaitAll()
        Loaded(got.map { it.first } + artists.drop(MAX_ARTISTS), mix(got.map { it.second }))
    }

    // one from each artist in turn, so the row isn't five songs of the first artist
    private fun mix(lists: List<List<Song>>) = (0 until PER_ARTIST).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }

    /** The songs already known for [artists], with no request, so Home can drop a removed artist's songs at once. */
    fun known(artists: List<PickedArtist>): List<Song> = mix(artists.take(MAX_ARTISTS).map { cache[it.key]?.songs.orEmpty() })

    /** Some of the first [MAX_ARTISTS] of [artists] have no songs yet (their page failed or timed out). */
    fun missing(artists: List<PickedArtist>): Boolean = artists.take(MAX_ARTISTS).any { it.key !in cache }

    /** Forget every artist not in [artists] (they were removed); added ones are asked for on the next [load]. */
    fun keepOnly(artists: List<PickedArtist>) {
        val keys = artists.map { it.key }.toSet()
        cache.keys.retainAll(keys); failedAt.keys.retainAll(keys)
    }

    private suspend fun one(a: PickedArtist): Pair<PickedArtist, List<Song>> {
        val k = a.key
        // picked once for the whole run: Home never swaps shown songs for others
        cache[k]?.let { return it.artist to it.songs }
        val failed = failedAt[k]
        if (failed != null && now() - failed < RETRY_MS) return a to emptyList()
        return gate.withPermit {
            try {
                val link = if (a.url.isNotBlank()) a.link else source.lookupArtist(a.name) ?: throw NoSuchElementException(a.name)
                val artist = PickedArtist(a.name, link.url, link.thumbnailUrl ?: a.thumbnailUrl)
                // a different few each app start; kept in the cache, so Home doesn't reshuffle while the app runs
                val songs = source.page(link).songs.shuffled(random).take(PER_ARTIST)
                if (songs.isEmpty()) throw NoSuchElementException(a.name)
                cache[k] = Entry(artist, songs); failedAt.remove(k)
                artist to songs
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                failedAt[k] = now()
                a to emptyList()
            }
        }
    }

    companion object {
        const val PARALLEL = 2
        const val MAX_ARTISTS = 6
        const val PER_ARTIST = 3
        const val RETRY_MS = 5 * 60 * 1000L
    }
}

/** The artist page whose name is [name], or the first result when none matches exactly. */
suspend fun MusicSource.lookupArtist(name: String): PageLink? =
    searchPages(name, SearchType.ARTISTS).let { r -> r.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: r.firstOrNull() }
