package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.LISTEN_MS
import com.spotifry.music.player.artistKey
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
