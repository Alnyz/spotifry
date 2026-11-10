package com.spotifry.music.data.spotifried

import com.spotifry.music.data.hourKind
import com.spotifry.music.data.hourName
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
    (s.daySeconds.maxOrNull() ?: 0L) > 8 * 3600 -> ListenerType.MARATHONER
    s.earlyShare > 0.5f -> ListenerType.EARLY_BIRD
    else -> ListenerType.REGULAR
}

fun typeTitle(s: SpotifriedStats): String {
    val t = listenerType(s)
    return if (t == ListenerType.REGULAR) "The ${hourKind(peakHour(s))} Regular" else t.title
}

fun typeFacts(s: SpotifriedStats, type: ListenerType): List<String> {
    val peak = "Most of your listening started around ${hourName(peakHour(s))}."
    val dayHours = "%.1f".format(longestDayHours(s))
    return when (type) {
        ListenerType.NIGHT_REPLAYER -> listOf(
            "${pct(s.nightShare)}% of your plays started between midnight and 5 a.m.",
            "You replayed ${s.replayedSongs} songs.",
            "Your busiest hour was ${hourName(peakHour(s))}.",
        )
        ListenerType.TIME_TRAVELLER -> listOf(
            "A typical song of yours came out in ${s.age.typicalYear ?: "an unknown year"}.",
            "${s.age.songsWithYear} of your ${s.age.songsTotal} songs have a known release year.",
            s.age.oldestYear?.let { "Your oldest song is from $it." } ?: "Your listening age is ${s.age.age ?: 0}.",
        )
        ListenerType.STREAK_KEEPER -> listOf(
            "You listened ${s.longestStreak} days in a row.",
            "That is ${s.plays} plays in all.",
            "Your longest day was $dayHours hours of music.",
        )
        ListenerType.EXPLORER -> listOf(
            "${s.newSongs} of your ${s.songs} songs were new to you.",
            "That is ${if (s.songs == 0) 0 else pct(s.newSongs.toFloat() / s.songs)}% fresh music.",
            "Only ${s.replayedSongs} songs got a replay.",
        )
        ListenerType.LOYALIST -> listOf(
            "${s.topArtist?.name ?: "One artist"} took ${pct(s.topArtist?.share ?: 0f)}% of your listening.",
            "You played ${s.songs} different songs in total.",
            "You replayed ${s.replayedSongs} of them.",
        )
        ListenerType.MARATHONER -> listOf(
            "Your longest day was $dayHours hours of music.",
            "In all you listened for ${s.seconds / 3600} hours.",
            "That is ${s.plays} plays.",
        )
        ListenerType.EARLY_BIRD -> listOf(
            "${pct(s.earlyShare)}% of your plays started before 9 a.m.",
            "Your busiest hour was ${hourName(peakHour(s))}.",
            "You played ${s.plays} songs in all.",
        )
        ListenerType.REGULAR -> listOf(
            peak,
            "You played ${s.plays} songs.",
            "That was ${s.songs} different songs, and ${s.replayedSongs} of them came back.",
        )
    }
}
