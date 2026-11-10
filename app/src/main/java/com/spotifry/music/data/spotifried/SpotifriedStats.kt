package com.spotifry.music.data.spotifried

import com.spotifry.music.data.TopSong
import com.spotifry.music.data.cleanArtist
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.player.LISTEN_MS
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val LISTEN_SEC = (LISTEN_MS / 1000).toInt()

/** Both ends inclusive. */
data class WrappedPeriod(val from: LocalDate, val to: LocalDate, val label: String)

fun calendarYear(year: Int, today: LocalDate) =
    WrappedPeriod(LocalDate.of(year, 1, 1), minOf(LocalDate.of(year, 12, 31), today), year.toString())

fun periodChoices(firstListen: LocalDate, today: LocalDate): List<WrappedPeriod> =
    (today.year downTo firstListen.year).map { calendarYear(it, today) } +
        WrappedPeriod(today.minusYears(1).plusDays(1), today, "Last 12 months") +
        WrappedPeriod(firstListen, today, "All time")

data class ObsessionDay(val song: SongEntity, val date: LocalDate, val plays: Int, val startHours: List<Float>)
data class MonthTop(val month: YearMonth, val song: SongEntity, val plays: Int)
data class ArtistShare(val name: String, val share: Float)

/** Median, the lower middle for an even count. */
fun typicalYear(years: List<Int>): Int = years.sorted()[(years.size - 1) / 2]

fun listeningAge(typicalYear: Int, periodEndYear: Int): Int = periodEndYear - typicalYear + 14

/** [songsTotal] and [unchecked] count distinct songs played in the period; [years] has one entry per song with a year. */
data class AgeStats(
    val songsWithYear: Int, val songsTotal: Int, val unchecked: Int, val years: List<Int>,
    val typicalYear: Int?, val age: Int?, val oldestYear: Int?, val oldestSong: SongEntity?,
)

data class SpotifriedStats(
    val period: WrappedPeriod,
    /** The later of the period's start and the first listen, so a short history isn't padded with empty days. */
    val start: LocalDate,
    val plays: Int,
    val seconds: Long,
    val songs: Int,
    val topSong: TopSong?,
    /** One entry per day from [start] to the period's end. */
    val daySeconds: List<Long>,
    val longestDay: LocalDate?,
    /** Seconds by the hour each listen started, 24 of them. */
    val hours: List<Long>,
    val obsession: ObsessionDay?,
    /** Only months with a play, in order. */
    val months: List<MonthTop>,
    /** Month whose top 5 shares the fewest songs with the previous month's; ties go to the earlier month. */
    val changeMonth: YearMonth?,
    val longestStreak: Int,
    val newSongs: Int,
    val replayedSongs: Int,
    val topArtist: ArtistShare?,
    val earlyShare: Float,
    val nightShare: Float,
    val age: AgeStats,
)

fun spotifriedStats(
    rows: List<ListenRow>, years: Map<String, Int>, period: WrappedPeriod,
    zone: ZoneId = ZoneId.systemDefault(), checked: Set<String> = years.keys,
): SpotifriedStats {
    class Line(val row: ListenRow, val date: LocalDate, val hour: Int, val hourF: Float)
    val all = rows.map { r -> Instant.ofEpochMilli(r.startedAt).atZone(zone).let { Line(r, it.toLocalDate(), it.hour, it.hour + it.minute / 60f + it.second / 3600f) } }
    val lines = all.filter { it.date >= period.from && it.date <= period.to }
    val start = all.minOfOrNull { it.date }?.let { maxOf(period.from, it) } ?: period.from

    val heard = lines.filter { it.row.secondsHeard >= LISTEN_SEC }
    val topSong = heard.filter { it.row.song != null }.groupBy { it.row.songId }.values
        .map { l -> TopSong(l.last().row.song!!, l.sumOf { it.row.secondsHeard.toLong() }, l.size) }
        .maxWithOrNull(compareBy<TopSong> { it.plays }.thenBy { it.seconds })

    val dayCount = maxOf(0, ChronoUnit.DAYS.between(start, period.to).toInt() + 1)
    val days = LongArray(dayCount)
    lines.forEach { days[ChronoUnit.DAYS.between(start, it.date).toInt().coerceIn(0, dayCount - 1)] += it.row.secondsHeard.toLong() }
    val hours = LongArray(24).also { a -> lines.forEach { a[it.hour] += it.row.secondsHeard.toLong() } }

    val withSong = heard.filter { it.row.song != null }
    val obsession = withSong.groupBy { it.row.songId to it.date }.values
        .maxWithOrNull(compareBy<List<Line>> { it.size }.thenByDescending { it.first().date })
        ?.let { l -> ObsessionDay(l.last().row.song!!, l.first().date, l.size, l.map { it.hourF }) }

    val monthLines = withSong.groupBy { YearMonth.from(it.date) }.toSortedMap()
    val monthSongs = monthLines.mapValues { (_, l) ->
        l.groupBy { it.row.songId }.values
            .sortedWith(compareByDescending<List<Line>> { it.size }.thenByDescending { g -> g.sumOf { it.row.secondsHeard } })
    }
    val months = monthSongs.map { (m, g) -> MonthTop(m, g.first().last().row.song!!, g.first().size) }
    val top5 = monthSongs.mapValues { (_, g) -> g.take(5).map { it.first().row.songId }.toSet() }
    val changeMonth = top5.keys.zipWithNext().minByOrNull { (a, b) -> top5.getValue(a).intersect(top5.getValue(b)).size }?.second

    var streak = 0; var run = 0; var prev: LocalDate? = null
    lines.map { it.date }.distinct().sorted().forEach { d ->
        run = if (prev?.plusDays(1) == d) run + 1 else 1
        streak = maxOf(streak, run); prev = d
    }

    val firstPlay = all.filter { it.row.secondsHeard >= LISTEN_SEC }.groupBy { it.row.songId }.mapValues { (_, l) -> l.minOf { it.date } }
    val newSongs = heard.map { it.row.songId }.distinct().count { firstPlay[it]!! >= period.from }
    val replayed = heard.groupBy { it.row.songId }.count { it.value.size >= 2 }

    val totalSeconds = lines.sumOf { it.row.secondsHeard.toLong() }
    val topArtist = lines.filter { it.row.song != null }.groupBy { cleanArtist(it.row.song!!.artist) }
        .mapValues { (_, l) -> l.sumOf { it.row.secondsHeard.toLong() } }.maxByOrNull { it.value }
        ?.takeIf { totalSeconds > 0 }?.let { ArtistShare(it.key, it.value.toFloat() / totalSeconds) }
    fun share(n: Int) = if (heard.isEmpty()) 0f else n.toFloat() / heard.size

    val songIds = heard.map { it.row.songId }.distinct()
    val yearOf = songIds.filter { it in years }
    val songYears = yearOf.map { years.getValue(it) }
    val oldestId = yearOf.minByOrNull { years.getValue(it) }
    val enough = songYears.size >= 20
    val ageStats = AgeStats(
        songYears.size, songIds.size, songIds.count { it !in checked }, songYears.sorted(),
        if (enough) typicalYear(songYears) else null,
        if (enough) listeningAge(typicalYear(songYears), period.to.year) else null,
        oldestId?.let { years.getValue(it) }, oldestId?.let { id -> heard.last { it.row.songId == id }.row.song },
    )

    return SpotifriedStats(
        period, start, heard.size, lines.sumOf { it.row.secondsHeard.toLong() },
        heard.distinctBy { it.row.songId }.size, topSong,
        days.toList(), days.indices.maxByOrNull { days[it] }?.takeIf { days[it] > 0 }?.let { start.plusDays(it.toLong()) },
        hours.toList(),
        obsession, months, changeMonth, streak, newSongs, replayed, topArtist,
        share(heard.count { it.hour < 9 }), share(heard.count { it.hour < 5 }), ageStats,
    )
}
