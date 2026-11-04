package com.spotifry.music.player

import com.spotifry.music.data.source.Song
import kotlin.random.Random

/** The shuffle every Shuffle button uses: [spreadShuffle] for a list of songs, with the songs [recentIds] heard in the last day. */
fun spreadShuffle(songs: List<Song>, recentIds: Set<String>, random: Random = Random.Default): List<Song> =
    spreadShuffle(songs, { it.artist }, { it.id in recentIds }, random)

/**
 * Random, but feels more random: songs by one artist are spread as far apart as the list allows (never back to back
 * when that can be avoided), and songs heard in the last day only come in the second half, still mixed in. That second
 * rule is skipped when more than half the list was heard today.
 */
fun <T> spreadShuffle(items: List<T>, artistOf: (T) -> String, isRecent: (T) -> Boolean, random: Random): List<T> {
    val n = items.size
    if (n < 2) return items
    val holdBack = items.count(isRecent) in 1..n / 2
    // Spotify's way: each artist's songs sit evenly over 0..1 from a random start, with a little wiggle
    val score = DoubleArray(n)
    val groups = items.indices.groupBy { artistKey(artistOf(items[it])) }.values.map { group ->
        val k = group.size
        val start = random.nextDouble() / k
        group.shuffled(random).forEachIndexed { i, idx ->
            val v = start + i.toDouble() / k + (random.nextDouble() - .5) * .2 / k
            // heard today: moved into the second half of the scale, so it mixes in with what is left there
            score[idx] = if (holdBack && isRecent(items[idx])) .5 + v / 2 else v
        }
        // within one artist the songs not heard today go first, so the first half never runs out of them
        ArrayDeque(group.sortedWith(compareBy({ holdBack && isRecent(items[it]) }, { score[it] })))
    }
    val out = ArrayList<T>(n)
    var last = -1
    for (step in 0 until n) {
        val left = n - step - 1
        val open = !holdBack || step >= n / 2
        // the two biggest artists left, to tell in one step whether a pick still lets the rest avoid back to back
        var top = -1; var second = -1
        groups.indices.forEach { g ->
            if (top < 0 || groups[g].size > groups[top].size) { second = top; top = g }
            else if (second < 0 || groups[g].size > groups[second].size) second = g
        }
        fun feasible(g: Int): Boolean {
            val other = if (g == top) second else top
            return groups[g].size - 1 <= left / 2 && (other < 0 || groups[other].size <= (left + 1) / 2)
        }
        // best = lowest score, from the strictest rule that anything meets
        val best = IntArray(4) { -1 }
        groups.indices.forEach { g ->
            val head = groups[g].firstOrNull() ?: return@forEach
            val allowed = open || !isRecent(items[head])
            val tier = when {
                allowed && g != last && feasible(g) -> 0
                allowed && g != last -> 1
                allowed -> 2
                else -> 3
            }
            if (best[tier] < 0 || score[head] < score[groups[best[tier]].first()]) best[tier] = g
        }
        val g = best.first { it >= 0 }
        out += items[groups[g].removeFirst()]
        last = g
    }
    return out
}

/** "Juno Reyes - Topic" and "juno reyes" are the same artist. */
private fun artistKey(artist: String) = artist.lowercase().removeSuffix(" - topic").trim()

/** [own] with one of [extra] after every [every] of them; extras that don't fit are left out. */
fun <T> interleave(own: List<T>, extra: List<T>, every: Int = 3): List<T> {
    val out = ArrayList<T>(own.size + extra.size)
    var e = 0
    own.forEachIndexed { i, x ->
        out += x
        if ((i + 1) % every == 0 && e < extra.size) out += extra[e++]
    }
    return out
}
