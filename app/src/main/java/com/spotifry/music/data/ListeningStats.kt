package com.spotifry.music.data

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.player.LISTEN_MS
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LISTEN_SEC = (LISTEN_MS / 1000).toInt()
private const val TOP = 5
/** Under this many, a list isn't shown: a top one says nothing. */
const val MIN_TOP = 3

enum class PeriodId { WEEK, MONTH, ALL }

data class TopSong(val song: SongEntity, val seconds: Long, val plays: Int) { val minutes get() = minutesOf(seconds) }
data class TopArtist(val name: String, val seconds: Long) { val minutes get() = minutesOf(seconds) }

data class Period(
    val id: PeriodId, val label: String, val seconds: Long, val songs: Int,
    /** Listens of at least 30 seconds, a song heard three times counting three. */
    val plays: Int,
    val artistCount: Int,
    /** Songs whose first listen is in this period; 0 for All time, where every song is new. */
    val newSongs: Int,
    /** Days of the period with any listening, as the days grid counts them. */
    val activeDays: Int,
    /** Seconds by the hour of the day each listen started, 24 of them. */
    val hours: List<Long>,
    /** The song with the most seconds in each hour. */
    val hourSongs: List<SongEntity?>,
    val artists: List<TopArtist>, val topSongs: List<TopSong>,
) {
    val minutes get() = minutesOf(seconds)
    val peakHour: Int get() = hours.indices.maxBy { hours[it] }
}

data class ListeningStats(
    val periods: List<Period>,
    /** The first listen was today: one small flame, and no days to show yet. */
    val firstDay: Boolean,
    /** Seconds per day from the first listen's day to today. */
    val firstDate: LocalDate, val days: List<Long>,
    val longestRun: Int,
    val first: SongEntity?, val firstAt: String,
) {
    val daysPlayed get() = days.count { it > 0 }
    val opensOn: PeriodId get() = periods.firstOrNull { it.seconds > 0 }?.id ?: PeriodId.ALL
}

/** A little listening still reads as a minute, never as 0. */
fun minutesOf(seconds: Long): Int = if (seconds <= 0) 0 else maxOf(1, (seconds / 60.0).roundToInt())
fun weekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun cleanArtist(name: String) = name.removeSuffix(" - Topic").trim()
fun hourName(h: Int) = "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "a.m." else "p.m."}"
fun hourKind(h: Int) = when (h) { in 5..10 -> "Morning"; in 11..16 -> "Afternoon"; in 17..21 -> "Evening"; else -> "Night" }

/** "4 min", "6 h 12 min", "12 days, 16 hours". */
fun longDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 1440 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "${minutes / 1440} ${if (minutes / 1440 == 1) "day" else "days"}, ${minutes % 1440 / 60} ${if (minutes % 1440 / 60 == 1) "hour" else "hours"}"
}

/** The sun's size: full at about 2 hours of listening, so a few minutes can't look like years. */
fun sunScale(minutes: Int): Float = if (minutes <= 0) 0f else minOf(1f, sqrt(minutes / 120f) * .82f + .18f)

/** Null when the log is empty. */
fun listeningStats(rows: List<ListenRow>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ListeningStats? {
    if (rows.isEmpty()) return null
    fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = dateOf(now)
    val week = weekStart(today)
    val month = today.withDayOfMonth(1)
    val firstRow = rows.minBy { it.startedAt }
    // lines can be dated ahead of today: the phone moved west, or its clock was set back
    val firstDate = minOf(dateOf(firstRow.startedAt), today)
    class Line(val row: ListenRow, val date: LocalDate, val hour: Int)
    val lines = rows.map { r -> Instant.ofEpochMilli(r.startedAt).atZone(zone).let { Line(r, it.toLocalDate(), it.hour) } }

    val firstHeard = lines.filter { it.row.secondsHeard >= LISTEN_SEC }.groupBy { it.row.songId }.mapValues { (_, l) -> l.minOf { it.date } }
    fun period(id: PeriodId, label: String, from: LocalDate?): Period {
        val within = if (from == null) lines else lines.filter { !it.date.isBefore(from) }
        val inside = within.map { it.row }
        val named = inside.filter { it.song != null }
        val heard = inside.filter { it.secondsHeard >= LISTEN_SEC }
        val hours = LongArray(24).also { a -> within.forEach { a[it.hour] += it.row.secondsHeard.toLong() } }.toList()
        val byHour = within.filter { it.row.song != null }.groupBy { it.hour }
        val hourSongs = (0 until 24).map { h ->
            byHour[h].orEmpty().groupBy { it.row.songId }.maxByOrNull { (_, l) -> l.sumOf { it.row.secondsHeard.toLong() } }?.value?.first()?.row?.song
        }
        return Period(
            id, label, inside.sumOf { it.secondsHeard.toLong() },
            heard.distinctBy { it.songId }.size, heard.size,
            heard.mapNotNull { it.song?.artist?.let(::cleanArtist)?.takeIf { a -> a.isNotBlank() } }.distinct().size,
            if (from == null) 0 else firstHeard.count { !it.value.isBefore(from) },
            within.map { it.date }.distinct().size,
            hours, hourSongs,
            named.filter { it.song!!.artist.isNotBlank() }.groupBy { cleanArtist(it.song!!.artist) }
                .map { (n, l) -> TopArtist(n, l.sumOf { it.secondsHeard.toLong() }) }.sortedByDescending { it.seconds }.take(TOP),
            named.groupBy { it.songId }
                .map { (_, l) -> TopSong(l.last().song!!, l.sumOf { it.secondsHeard.toLong() }, l.count { it.secondsHeard >= LISTEN_SEC }) }
                .sortedByDescending { it.seconds }.take(TOP),
        )
    }
    // a period that holds all of the log would only repeat All time, and one holding the same as the week would repeat that
    val periods = buildList {
        if (firstDate.isBefore(week)) add(period(PeriodId.WEEK, "This week", week))
        if (firstDate.isBefore(month)) period(PeriodId.MONTH, "This month", month).let { m -> if (m.seconds != firstOrNull()?.seconds) add(m) }
        add(period(PeriodId.ALL, if (isEmpty()) "So far" else "All time", null))
    }

    val dayCount = ChronoUnit.DAYS.between(firstDate, today).toInt() + 1
    val days = LongArray(dayCount).also { a -> lines.forEach { l -> a[ChronoUnit.DAYS.between(firstDate, l.date).toInt().coerceIn(0, dayCount - 1)] += l.row.secondsHeard.toLong() } }.toList()
    var run = 0; var longest = 0
    days.forEach { if (it > 0) { run++; longest = maxOf(longest, run) } else run = 0 }

    val when_ = DateTimeFormatter.ofPattern("HH.mm", Locale.ENGLISH).format(Instant.ofEpochMilli(firstRow.startedAt).atZone(zone))
    val firstAt = if (firstDate == today) "Today, $when_" else DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(firstDate) + ", $when_"
    return ListeningStats(periods, firstDate == today, firstDate, days, longest, firstRow.song, firstAt)
}
