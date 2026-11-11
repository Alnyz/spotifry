package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.player.LISTEN_MS
import java.time.Instant
import java.time.ZoneId

/** Under this many different songs played in a part of the day, it has no mix: a handful of songs isn't one yet. */
const val MIX_MIN_SONGS = 8
/** And under this many plays there: eight songs heard once each say little about a morning. */
const val MIX_MIN_PLAYS = 12
/** The longest a mix gets. */
const val MIX_MAX_SONGS = 25
/** Home shows no more than this many. */
const val MIX_SHOWN = 2

/** [kind] is [hourKind]'s word; [plays] is how strong the part of the day is. */
data class TimeMix(val kind: String, val songs: List<SongEntity>, val plays: Int) {
    val title get() = if (kind == "Night") "Your late nights" else "Your ${kind.lowercase()}s"
    val line get() = "${songs.size} songs you play " + if (kind == "Night") "late at night" else "in the ${kind.lowercase()}"
}

/** Every part of the day with enough plays for a mix, the strongest first; each mix is its most played songs. */
fun timeMixes(rows: List<ListenRow>, zone: ZoneId = ZoneId.systemDefault()): List<TimeMix> =
    rows.filter { it.song != null && it.secondsHeard * 1000L >= LISTEN_MS }
        .groupBy { hourKind(Instant.ofEpochMilli(it.startedAt).atZone(zone).hour) }
        .mapNotNull { (kind, plays) ->
            val bySong = plays.groupBy { it.songId }
            if (bySong.size < MIX_MIN_SONGS || plays.size < MIX_MIN_PLAYS) return@mapNotNull null
            val top = bySong.values.sortedWith(compareByDescending<List<ListenRow>> { it.size }.thenByDescending { l -> l.sumOf { it.secondsHeard } })
            TimeMix(kind, top.take(MIX_MAX_SONGS).map { it.last().song!! }, plays.size)
        }
        .sortedByDescending { it.plays }

/** The part of the day it is at [hour] first when it has a mix, then the strongest others, at most [MIX_SHOWN]. */
fun pickTimeMixes(mixes: List<TimeMix>, hour: Int): List<TimeMix> =
    mixes.sortedByDescending { it.kind == hourKind(hour) }.take(MIX_SHOWN)
