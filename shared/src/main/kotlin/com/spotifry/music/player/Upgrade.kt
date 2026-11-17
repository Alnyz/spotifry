package com.spotifry.music.player

import com.spotifry.music.data.source.qualityRank

/**
 * Reads a whole file with [read] (bytes read, or -1 at the end) into [out], waiting while [mayGo] is false. True only when
 * the length is known and matches, so a download cut short is never taken for a whole song.
 */
fun fetchWhole(length: Long, read: (ByteArray) -> Int, out: java.io.File, mayGo: () -> Boolean): Boolean {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    java.io.FileOutputStream(out).use { f ->
        while (true) {
            while (!mayGo()) Thread.sleep(300)
            val n = read(buf)
            if (n < 0) break
            f.write(buf, 0, n); total += n
        }
    }
    return total > 0 && (length < 0 || total == length)
}

/** A copy kept from [origin] (null: YouTube, as before there was a choice) is poorer than what [rank] now allows. */
fun isBelow(origin: String?, rank: Int): Boolean = qualityRank(origin ?: "youtube") < rank

/** Whether to look for a better copy: the stored one isn't the best allowed, none is offered yet, and not asked for [UPGRADE_RETRY_MS]. */
fun shouldLook(storedOrigin: String, bestAllowed: Int, offeredOrigin: String?, lastTried: Long?, now: Long): Boolean =
    qualityRank(storedOrigin) < bestAllowed && qualityRank(offeredOrigin) <= qualityRank(storedOrigin) && (lastTried == null || now - lastTried > UPGRADE_RETRY_MS)

/** How long a song's search for a better copy rests after it was asked for. */
const val UPGRADE_RETRY_MS = 30 * 60 * 1000L

/**
 * When a song from a slow server (lossless, JioSaavn) should go on from YouTube instead: no sound [START_MS] after its
 * link is known, a stall of [STALL_MS], or two stalls within [WINDOW_MS]. The player tells it what happens; it answers.
 */
class StallWatch {
    private var started = false
    private var waitingSince: Long? = null
    private val stalls = ArrayDeque<Long>()

    /** A new song (or the same one from another copy): nothing heard yet. [now]: its link is known and it is waiting for sound. */
    fun newSong(now: Long) { started = false; stalls.clear(); waitingSince = now }

    /** Sound is coming. */
    fun playing() { started = true; waitingSince = null }

    /** The sound stopped to wait for more; true when that is the second stall within [WINDOW_MS]. */
    fun stalled(now: Long): Boolean {
        if (waitingSince == null) waitingSince = now
        if (!started) return false
        stalls.addLast(now); while (stalls.isNotEmpty() && now - stalls.first() > WINDOW_MS) stalls.removeFirst()
        return stalls.size >= 2
    }

    /** True once it has waited too long: [START_MS] before any sound, [STALL_MS] after. */
    fun tooLong(now: Long): Boolean = waitingSince?.let { now - it >= if (started) STALL_MS else START_MS } ?: false

    companion object { const val START_MS = 8_000L; const val STALL_MS = 4_000L; const val WINDOW_MS = 30_000L }
}
