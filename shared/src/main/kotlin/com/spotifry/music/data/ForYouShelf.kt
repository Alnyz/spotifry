package com.spotifry.music.data

import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.RelatedEntity
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.LISTEN_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.random.Random

/** Since the last build: this many skips or this many songs heard make a new one due; so does this much time. */
const val REBUILD_SKIPS = 2
const val REBUILD_PLAYS = 4
const val STALE_MS = 30 * 60_000L
/** Never two builds closer than this. */
const val MIN_GAP_MS = 60_000L
/** A build with fewer new songs than this doesn't replace a stored list that has more (offline, YouTube down). */
const val KEEP_IF_NEW_BELOW = 9
/** A seed's similar songs are asked for again after this many days. */
const val RELATED_MAX_AGE_DAYS = 7

private const val DAY_MS = 86_400_000L

/**
 * What the For you row shows. A new list shows at once unless the row was touched (scrolled, tapped) while Home is open;
 * then it waits until Home is left, so the row never changes under a finger.
 */
class ShelfView {
    private val _shown = MutableStateFlow<List<Song>?>(null)
    /** Null until a list was read or built; empty hides the row. */
    val shown: StateFlow<List<Song>?> = _shown
    private var pending: List<Song>? = null
    private var visible = false
    private var wasTouched = false

    @Synchronized fun offer(list: List<Song>) { if (visible && wasTouched) pending = list else { _shown.value = list; pending = null } }
    @Synchronized fun homeVisible(v: Boolean) {
        visible = v
        if (!v) { wasTouched = false; pending?.let { _shown.value = it }; pending = null }
    }
    @Synchronized fun touched() { wasTouched = true }
    @Synchronized fun remove(id: String) {
        _shown.value = _shown.value?.filterNot { it.id == id }
        pending = pending?.filterNot { it.id == id }
    }
}

/**
 * The For you shelf: builds the list from listening ([tasteScores] and the rest of ForYou.kt), keeps each seed's similar
 * songs in the `related` table, stores the last list in [prefs] under [Settings.FOR_YOU] and rebuilds when the
 * listening has changed enough. Call [start] once.
 */
class ForYouShelf(
    private val db: AppDatabase,
    private val source: MusicSource,
    private val prefs: Prefs,
    private val settings: Settings,
    private val notSuggested: () -> Set<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val random: Random = Random.Default,
) {
    private val view = ShelfView()
    val shown: StateFlow<List<Song>?> = view.shown
    private val building = Mutex()
    private var lastAttempt: Long? = null

    private class Stored(val builtAt: Long, val newCount: Int, val ids: List<String>)

    private fun readStored(): Stored? = prefs.getString(Settings.FOR_YOU, null)?.split('\n')?.takeIf { it.size == 3 }?.let { p ->
        val at = p[0].toLongOrNull() ?: return null
        Stored(at, p[1].toIntOrNull() ?: 0, p[2].split(',').filter { it.isNotEmpty() })
    }
    private fun write(s: Stored) = prefs.edit().putString(Settings.FOR_YOU, "${s.builtAt}\n${s.newCount}\n${s.ids.joinToString(",")}").apply()

    /** Shows the stored list, then rebuilds whenever the listening log changes and a build is due. */
    fun start() {
        scope.launch {
            loadStored()
            db.listens().observeCount().collect {
                // a failed build must never take the app down; the next change tries again
                try { onListensChanged(it) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    /** The stored list, in its order; songs no longer in the database are dropped. Nothing stored: the row stays loading until the first build. */
    suspend fun loadStored() {
        val ids = readStored()?.ids ?: return
        val byId = songsByIds(ids).associateBy { it.id }
        view.offer(ids.mapNotNull { byId[it]?.toSong() })
    }

    /** [count] listens in the log: none means it was cleared, which empties the shelf. */
    suspend fun onListensChanged(count: Int) {
        if (count == 0) { prefs.edit().remove(Settings.FOR_YOU).apply(); view.offer(emptyList()); return }
        rebuildIfDue()
    }

    fun homeVisible(visible: Boolean) {
        view.homeVisible(visible)
        if (visible) scope.launch { runCatching { rebuildIfDue() } }
    }

    fun rowTouched() = view.touched()

    /** Out of the row now and out of the stored list; the "Don't suggest" list keeps it out of later builds. */
    fun dontSuggest(id: String) {
        view.remove(id)
        readStored()?.let { s -> if (id in s.ids) write(Stored(s.builtAt, s.newCount, s.ids - id)) }
    }

    /** Builds a new list when one is due. True when a build ran. */
    suspend fun rebuildIfDue(): Boolean = building.withLock {
        val now = clock()
        if (lastAttempt?.let { now - it < MIN_GAP_MS } == true) return false
        val stored = readStored()
        if (stored != null && now - stored.builtAt <= STALE_MS) {
            val since = db.listens().since(stored.builtAt + 1)
            if (since.count { it.skipped } < REBUILD_SKIPS && since.count { it.secondsHeard * 1000L >= LISTEN_MS } < REBUILD_PLAYS) return false
        }
        lastAttempt = now
        val built = try { build(now) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            if (view.shown.value == null) view.offer(emptyList())
            return false
        }
        // "Not this song" may have been chosen while the build ran
        val no = try { notSuggested() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptySet() }
        val songs = built.list.songs.filterNot { it.id in no }
        val list = if (songs.size < MIN_LIST) ForYouList(emptyList(), 0) else ForYouList(songs, songs.count { it.id !in built.known })
        if (list.newCount < KEEP_IF_NEW_BELOW && stored != null && stored.newCount > list.newCount) return true
        write(Stored(now, list.newCount, list.songs.map { it.id }))
        view.offer(list.songs)
        true
    }

    private class Built(val list: ForYouList, val known: Set<String>)

    /** Room puts one SQL variable per id, and older Android allows 999 in a query. */
    private suspend fun songsByIds(ids: Collection<String>): List<SongEntity> = ids.chunked(500).flatMap { db.songs().byIds(it) }

    private suspend fun build(now: Long): Built {
        val listens = db.listens().since(now - WINDOW_DAYS * DAY_MS)
        val saved = db.songs().savedSongs()
        val savedIds = saved.map { it.id }.toSet()
        val listIds = db.playlists().songIdsInPlaylists().toSet()
        val scores = tasteScores(listens, savedIds, listIds, now, zone)
        val blocked = blocked(listens, notSuggested(), now)

        val entities = LinkedHashMap<String, SongEntity>()
        listens.forEach { l -> l.song?.let { entities[it.id] = it } }
        saved.forEach { entities[it.id] = it }
        songsByIds(listIds - entities.keys).forEach { entities[it.id] = it }

        val scored = scores.mapNotNull { (id, v) -> entities[id]?.toSong()?.let { Weighted(it, v) } }.filterNot { blocked.blocks(it.song) }
        val seeds = if (scored.size >= MIN_SCORED) pickSeeds(scored, random) else pickSeeds(fallbackPool(scored, saved, listIds, entities, blocked), random)
        val knownIds = savedIds + listIds + scores.keys
        if (seeds.size < MIN_SEEDS) return Built(ForYouList(emptyList(), 0), knownIds)

        val related = seeds.associate { it.id to similar(it, now) }
        val heard = db.listens().playCounts().map { it.songId }.toSet()
        val taste = artistTaste(scores) { entities[it]?.artist }
        val fresh = newCandidates(related, heard, knownIds, blocked, taste)
        val playedLately = listens.filter { it.startedAt >= now - RECENT_MS }.map { it.songId }.toSet()
        val known = knownSongs(scores, entities.values.toList(), savedIds, listIds, playedLately, blocked)
        return Built(buildList(fresh, known, random), knownIds)
    }

    /** Thin history: every scored, saved and playlist song and the top song of each picked artist, all equal, shuffled. */
    private suspend fun fallbackPool(scored: List<Weighted>, saved: List<SongEntity>, listIds: Set<String>, entities: Map<String, SongEntity>, blocked: Blocked): List<Weighted> {
        val songs = scored.map { it.song } + saved.map { it.toSong() } + listIds.mapNotNull { entities[it]?.toSong() } +
            settings.artists.value.take(SEEDS).mapNotNull { a ->
                try { source.search(a.name).firstOrNull() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        return songs.distinctBy { it.id }.filterNot { blocked.blocks(it) }.shuffled(random).map { Weighted(it, 1.0) }
    }

    /** [seed]'s similar songs: the kept list while it is under [RELATED_MAX_AGE_DAYS] days old, else asked for again; the old list when that fails. */
    private suspend fun similar(seed: Song, now: Long): List<Song> {
        val kept = db.related().forSeed(seed.id)
        if (kept.isNotEmpty() && now - kept.first().fetchedAt < RELATED_MAX_AGE_DAYS * DAY_MS) return songsOf(kept)
        val fetched = try { source.related(seed) } catch (e: CancellationException) { throw e } catch (_: Exception) { return songsOf(kept) }
        val list = fetched.distinctBy { it.id }
        list.forEach { db.songs().upsert(it.toEntity()) }
        db.related().replace(seed.id, list.mapIndexed { i, s -> RelatedEntity(seed.id, s.id, i, now) })
        return list
    }

    private suspend fun songsOf(rows: List<RelatedEntity>): List<Song> {
        val byId = songsByIds(rows.map { it.songId }).associateBy { it.id }
        return rows.mapNotNull { byId[it.songId]?.toSong() }
    }
}
