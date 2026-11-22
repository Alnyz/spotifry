package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.LISTEN_MS
import com.spotifry.music.player.artistKey
import com.spotifry.music.player.spreadShuffle
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

// The For you shelf: songs scored from listening, a weighted draw of seeds, their similar songs, then a weighted mix.

/** A play counts about half after this many days (e^-1 after exactly this many). */
const val FADE_DAYS = 14.0
/** Plays older than this say nothing about taste now. */
const val WINDOW_DAYS = 90
/** Plays within [HOUR_WINDOW] hours of the time it is now count this much more. */
const val HOUR_BOOST = 1.5
const val HOUR_WINDOW = 2
const val SAVED_BONUS = 1.5
const val PLAYLIST_BONUS = 1.0
/** A skip keeps the song out for this many days; [ARTIST_SKIPS] skips in that time keep the artist out. */
const val SKIP_DAYS = 14
const val ARTIST_SKIPS = 3
/** Length assumed for a song whose length isn't known. */
const val UNKNOWN_LENGTH_SEC = 180L

/** Seeds are drawn from the best this many songs, one per artist, and this many are drawn. */
const val TOP_SEEDS = 14
const val SEEDS = 8
/** Under this many songs with a score, the history is too thin to seed from it alone. */
const val MIN_SCORED = 10
/** Under this many seeds there is no shelf. */
const val MIN_SEEDS = 3
/** The list: this many songs, this many of them new, at most [PER_ARTIST] per artist. */
const val LIST_SIZE = 24
const val NEW_SONGS = 18
const val PER_ARTIST = 2
/** Under this many songs there is no shelf. */
const val MIN_LIST = 8
/** Known songs played this recently are left out of the list. */
const val RECENT_MS = 24 * 3_600_000L

private const val DAY_MS = 86_400_000L

/**
 * Each song's taste score: every play in the last [WINDOW_DAYS] days that wasn't a skip adds how much of the song was
 * heard, faded by age and lifted near this hour. Saved and playlist songs get a bonus, but only when they were played.
 */
fun tasteScores(listens: List<ListenRow>, saved: Set<String>, inPlaylist: Set<String>, now: Long, zone: ZoneId): Map<String, Double> {
    val hourNow = Instant.ofEpochMilli(now).atZone(zone).hour
    val out = HashMap<String, Double>()
    listens.forEach { l ->
        val ageDays = (now - l.startedAt).toDouble() / DAY_MS
        if (l.skipped || ageDays > WINDOW_DAYS || ageDays < 0) return@forEach
        val length = l.song?.durationSec?.takeIf { it > 0 } ?: UNKNOWN_LENGTH_SEC
        val heard = min(1.0, l.secondsHeard.toDouble() / length)
        val d = abs(Instant.ofEpochMilli(l.startedAt).atZone(zone).hour - hourNow)
        val hour = if (min(d, 24 - d) <= HOUR_WINDOW) HOUR_BOOST else 1.0
        out[l.songId] = (out[l.songId] ?: 0.0) + heard * exp(-ageDays / FADE_DAYS) * hour
    }
    out.keys.forEach { id ->
        out[id] = out[id]!! + (if (id in saved) SAVED_BONUS else 0.0) + (if (id in inPlaylist) PLAYLIST_BONUS else 0.0)
    }
    return out
}

/** Songs and artists (by [artistKey]) that must not be suggested. */
data class Blocked(val songs: Set<String>, val artists: Set<String>) {
    fun blocks(song: Song) = song.id in songs || artistKey(song.artist) in artists
}

/** "Don't suggest" songs, songs skipped in the last [SKIP_DAYS] days, artists skipped [ARTIST_SKIPS] times in that time. */
fun blocked(listens: List<ListenRow>, notSuggested: Set<String>, now: Long): Blocked {
    val skips = listens.filter { it.skipped && now - it.startedAt <= SKIP_DAYS * DAY_MS && it.secondsHeard * 1000L < LISTEN_MS }
    val artists = skips.mapNotNull { it.song?.artist?.let(::artistKey) }.groupingBy { it }.eachCount().filterValues { it >= ARTIST_SKIPS }.keys
    return Blocked(notSuggested + skips.map { it.songId }, artists)
}

/**
 * [items] in a weighted random order: heavier ones tend to come first, never always (Efraimidis-Spirakis: each item's
 * key is ln(U)/weight, largest first). Items weighing nothing come last.
 */
fun <T> weightedOrder(items: List<T>, weight: (T) -> Double, random: Random): List<T> =
    items.map { item ->
        val w = weight(item)
        item to if (w > 0) ln(random.nextDouble().coerceAtLeast(Double.MIN_VALUE)) / w else Double.NEGATIVE_INFINITY
    }.sortedByDescending { it.second }.map { it.first }

data class Weighted(val song: Song, val weight: Double)

/** Each artist's taste (by [artistKey]): the sum of its songs' scores. Songs with no known artist are left out. */
fun artistTaste(scores: Map<String, Double>, artistOf: (String) -> String?): Map<String, Double> {
    val out = HashMap<String, Double>()
    scores.forEach { (id, v) -> artistOf(id)?.let(::artistKey)?.let { out[it] = (out[it] ?: 0.0) + v } }
    return out
}

/** The best [TOP_SEEDS] songs, one per artist, then [SEEDS] of them drawn by weight. */
fun pickSeeds(scored: List<Weighted>, random: Random): List<Song> =
    scored.sortedByDescending { it.weight }.distinctBy { artistKey(it.song.artist) }.take(TOP_SEEDS)
        .let { top -> weightedOrder(top, { it.weight }, random) }.take(SEEDS).map { it.song }

/**
 * Songs never heard from the seeds' similar songs ([related], keyed by seed id). Weight: how many seeds list it, plus up
 * to 0.5 for how much its artist is liked, plus up to 0.3 for how early it sits in a list (its best place).
 */
fun newCandidates(related: Map<String, List<Song>>, heard: Set<String>, known: Set<String>, blocked: Blocked, artistTaste: Map<String, Double>): List<Weighted> {
    val maxTaste = artistTaste.values.maxOrNull()?.takeIf { it > 0 }
    val count = HashMap<String, Int>()
    val best = HashMap<String, Double>()
    val songs = LinkedHashMap<String, Song>()
    related.values.forEach { list ->
        list.distinctBy { it.id }.forEachIndexed { i, song ->
            if (song.id in related || song.id in heard || song.id in known || blocked.blocks(song)) return@forEachIndexed
            songs.putIfAbsent(song.id, song)
            count[song.id] = (count[song.id] ?: 0) + 1
            best[song.id] = maxOf(best[song.id] ?: 0.0, 1 - i.toDouble() / list.size)
        }
    }
    return songs.values.map { song ->
        val taste = maxTaste?.let { (artistTaste[artistKey(song.artist)] ?: 0.0) / it } ?: 0.0
        Weighted(song, count[song.id]!! + 0.5 * taste + 0.3 * best[song.id]!!)
    }
}

/**
 * Songs the listener knows: scored, saved or in a playlist, not played since [playedSince] and not blocked. Weight is the
 * score; a song without one (not played lately) weighs its saved and playlist bonus.
 */
fun knownSongs(scores: Map<String, Double>, entities: List<SongEntity>, saved: Set<String>, inPlaylist: Set<String>, playedSince: Set<String>, blocked: Blocked): List<Weighted> =
    entities.distinctBy { it.id }.mapNotNull { e ->
        val song = e.toSong()
        if (e.id in playedSince || blocked.blocks(song)) return@mapNotNull null
        val w = scores[e.id] ?: ((if (e.id in saved) SAVED_BONUS else 0.0) + (if (e.id in inPlaylist) PLAYLIST_BONUS else 0.0))
        if (w > 0) Weighted(song, w) else null
    }

data class ForYouList(val songs: List<Song>, val newCount: Int)

/**
 * [NEW_SONGS] new and the rest known, each drawn by weight, at most [PER_ARTIST] per artist; a short side is filled from
 * the other. Ordered so one artist is never back to back when that can be avoided. Empty under [MIN_LIST] songs.
 */
fun buildList(fresh: List<Weighted>, known: List<Weighted>, random: Random): ForYouList {
    val perArtist = HashMap<String, Int>()
    val taken = HashSet<String>()
    fun take(from: Iterator<Weighted>, n: Int): List<Song> {
        val out = ArrayList<Song>()
        while (out.size < n && from.hasNext()) {
            val s = from.next().song
            val a = artistKey(s.artist)
            if (s.id in taken || (perArtist[a] ?: 0) >= PER_ARTIST) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1; taken += s.id; out += s
        }
        return out
    }
    val newOrder = weightedOrder(fresh, { it.weight }, random).iterator()
    val first = take(newOrder, NEW_SONGS)
    val old = take(weightedOrder(known, { it.weight }, random).iterator(), LIST_SIZE - first.size)
    val more = take(newOrder, LIST_SIZE - first.size - old.size)
    val all = first + old + more
    if (all.size < MIN_LIST) return ForYouList(emptyList(), 0)
    return ForYouList(spreadShuffle(all, emptySet(), random), first.size + more.size)
}
