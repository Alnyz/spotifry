package com.spotifry.music.data.spotifried

import com.spotifry.music.data.hourKind
import com.spotifry.music.data.hourName
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class ListenerType(val title: String) {
    NIGHT_REPLAYER("The 3 a.m. Replayer"),
    TIME_TRAVELLER("The Time Traveller"),
    STREAK_KEEPER("The Streak Keeper"),
    EXPLORER("The Explorer"),
    LOYALIST("The Loyalist"),
    MARATHONER("The Marathoner"),
    EARLY_BIRD("The Early Bird"),
    REGULAR("The Regular"),
}

private fun peakHour(s: SpotifriedStats) = s.hours.indices.maxByOrNull { s.hours[it] } ?: 0
private fun pct(x: Float) = (x * 100).roundToInt()
private fun longestDayHours(s: SpotifriedStats) = (s.daySeconds.maxOrNull() ?: 0L) / 3600.0

fun listenerType(s: SpotifriedStats): ListenerType = when {
    s.nightShare >= 1f / 3 && s.replayedSongs >= 5 -> ListenerType.NIGHT_REPLAYER
    s.age.typicalYear?.let { s.period.to.year - it >= 25 } == true -> ListenerType.TIME_TRAVELLER
    s.longestStreak >= 30 -> ListenerType.STREAK_KEEPER
    s.newSongs * 2 > s.songs -> ListenerType.EXPLORER
    (s.topArtist?.share ?: 0f) >= 1f / 3 -> ListenerType.LOYALIST
    longestDayHours(s) > 8 -> ListenerType.MARATHONER
    s.earlyShare > 0.5f -> ListenerType.EARLY_BIRD
    else -> ListenerType.REGULAR
}

fun typeTitle(s: SpotifriedStats): String {
    val t = listenerType(s)
    return if (t == ListenerType.REGULAR) "The ${hourKind(peakHour(s))} Regular" else t.title
}

private fun count(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"

/** "You replayed N of them" that still reads right for 0 and 1. */
private fun replayedOfThem(n: Int) = if (n == 0) "You never replayed any of them." else "You replayed $n of them."

fun typeFacts(s: SpotifriedStats, type: ListenerType): List<String> {
    val busiest = "Your busiest hour was ${hourName(peakHour(s))}".let { if (it.endsWith(".")) it else "$it." }
    val dayHours = "%.1f".format(Locale.US, longestDayHours(s))
    val playedInAll = "You played ${count(s.plays, "song")} in total."
    return when (type) {
        ListenerType.NIGHT_REPLAYER -> listOf(
            "${pct(s.nightShare)}% of your plays started between midnight and 5 a.m.",
            if (s.replayedSongs == 0) "You never replayed a song." else "You replayed ${count(s.replayedSongs, "song")}.",
            busiest,
        )
        ListenerType.TIME_TRAVELLER -> listOf(
            "A typical song of yours came out in ${s.age.typicalYear ?: "an unknown year"}.",
            "Release years are known for ${s.age.songsWithYear} of ${count(s.age.songsTotal, "song")}.",
            s.age.oldestYear?.let { "Your oldest song is from $it." } ?: "Your listening age is ${s.age.age ?: 0}.",
        )
        ListenerType.STREAK_KEEPER -> listOf(
            "You listened ${count(s.longestStreak, "day")} in a row.",
            playedInAll,
            "Your longest day was $dayHours hours of music.",
        )
        ListenerType.EXPLORER -> listOf(
            "${s.newSongs} of ${count(s.songs, "song")} ${if (s.newSongs == 1) "was" else "were"} new to you.",
            "That is ${if (s.songs == 0) 0 else pct(s.newSongs.toFloat() / s.songs)}% fresh music.",
            when (s.replayedSongs) {
                0 -> "None of your songs got a replay."
                else -> "Only ${count(s.replayedSongs, "song")} got a replay."
            },
        )
        ListenerType.LOYALIST -> listOf(
            "${s.topArtist?.name ?: "One artist"} took ${pct(s.topArtist?.share ?: 0f)}% of your listening.",
            "You played ${count(s.songs, "different song")} in total.",
            replayedOfThem(s.replayedSongs),
        )
        ListenerType.MARATHONER -> listOf(
            "Your longest day was $dayHours hours of music.",
            "In all you listened for ${count((s.seconds / 3600).toInt(), "hour")}.",
            playedInAll,
        )
        ListenerType.EARLY_BIRD -> listOf(
            "${pct(s.earlyShare)}% of your plays started before 9 a.m.",
            busiest,
            playedInAll,
        )
        ListenerType.REGULAR -> listOf(
            busiest,
            "You played ${count(s.plays, "song")}.",
            "That was ${count(s.songs, "different song")}, and " + when (s.replayedSongs) {
                0 -> "none came back."
                else -> "${s.replayedSongs} came back."
            },
        )
    }
}

enum class CardId { INTRO, TOP_SONG, HOURS, AGE, DAY, MONTHS, SUN, TYPE }

fun shownCards(s: SpotifriedStats): List<CardId> {
    val a = s.age
    val ageShown = a.songsWithYear >= 20 || (a.unchecked > 0 && a.songsWithYear + a.unchecked >= 20)
    val shown = CardId.entries.filter {
        when (it) {
            CardId.INTRO, CardId.SUN, CardId.TYPE -> s.plays >= 1
            CardId.TOP_SONG -> s.plays >= 10 && s.topSong != null
            CardId.HOURS -> s.plays >= 10
            CardId.AGE -> ageShown
            CardId.DAY -> (s.obsession?.plays ?: 0) >= 8
            CardId.MONTHS -> s.months.size >= 3
        }
    }
    return if (shown.size < 4) emptyList() else shown
}

fun morePlaysNeeded(s: SpotifriedStats): Int = max(1, 10 - s.plays)
