package com.spotifry.music.data.spotifried

import com.spotifry.music.data.TopSong
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.player.LISTEN_MS
import java.time.Instant
import java.time.LocalDate
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
)

// `checked` is for Task 3
fun spotifriedStats(
    rows: List<ListenRow>, years: Map<String, Int>, period: WrappedPeriod,
    zone: ZoneId = ZoneId.systemDefault(), checked: Set<String> = years.keys,
): SpotifriedStats {
    class Line(val row: ListenRow, val date: LocalDate, val hour: Int)
    val all = rows.map { r -> Instant.ofEpochMilli(r.startedAt).atZone(zone).let { Line(r, it.toLocalDate(), it.hour) } }
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

    return SpotifriedStats(
        period, start, heard.size, lines.sumOf { it.row.secondsHeard.toLong() },
        heard.distinctBy { it.row.songId }.size, topSong,
        days.toList(), days.indices.maxByOrNull { days[it] }?.takeIf { days[it] > 0 }?.let { start.plusDays(it.toLong()) },
        hours.toList(),
    )
}
