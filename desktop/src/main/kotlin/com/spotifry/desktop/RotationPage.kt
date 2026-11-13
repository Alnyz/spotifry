package com.spotifry.desktop

import com.spotifry.music.data.source.Song
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.ListeningStats
import com.spotifry.music.data.MIN_TOP
import com.spotifry.music.data.Period
import com.spotifry.music.data.PeriodId
import com.spotifry.music.data.hourKind
import com.spotifry.music.data.hourName
import com.spotifry.music.data.listeningStats
import com.spotifry.music.data.longDuration
import com.spotifry.music.data.minutesOf
import com.spotifry.music.data.sunScale
import com.spotifry.music.data.weekStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal fun count(n: Int) = "%,d".format(Locale.US, n)

/** The big title: what your own hours make you. The same words as the phone. */
private fun listenerTitle(p: Period, firstDay: Boolean) = when {
    p.seconds == 0L -> "Still warm"
    firstDay -> "First spark"
    else -> "${hourKind(p.peakHour)} listener"
}

private fun listenerLine(p: Period, firstDay: Boolean) = when {
    p.seconds == 0L -> "Nothing yet ${p.label.lowercase()}. Play something."
    firstDay -> "${p.minutes} ${if (p.minutes == 1) "minute" else "minutes"} in. This grows as you listen."
    else -> "${longDuration(p.minutes)} of music. Most of it around ${hourName(p.peakHour)}"
}

/** Your rotation: what this computer has counted, drawn as a sun, like the phone. Nothing here leaves the computer. */
@Composable
fun RotationPage(services: Services, onBack: () -> Unit, onSpotifried: () -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val flow = remember(lib) { lib.db.listens().observeAll().map { listeningStats(it) }.flowOn(Dispatchers.Default) }
    val stats by flow.collectAsState(null)
    val state by services.player.state.collectAsState()
    val s = stats
    LazyColumn(modifier) {
        item { Text("‹ Home", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp)) }
        if (s == null) item { Text("Nothing heard yet. What you play is counted here, only on this computer.", color = Palette.ink2, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp)) }
        else rotation(services, s, state.isPlaying, state.current?.id, onSpotifried)
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rotation(services: Services, stats: ListeningStats, playing: Boolean, currentId: String?, onSpotifried: () -> Unit) {
    item("head") {
        var picked by remember { mutableStateOf<PeriodId?>(null) }
        val period = stats.periods.firstOrNull { it.id == picked } ?: stats.periods.first { it.id == stats.opensOn }
        var hour by remember(period.id) { mutableStateOf<Int?>(null) }
        val motion = remember { SunMotion() }
        // wide: Spotifried stands beside the numbers; narrower, it gets its own row under them
        BoxWithConstraints { val wide = maxWidth > 1250.dp; Column {
            // the sun big on the left, what it says on the right
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Tip("Click a ray to see that hour") {
                    Box(Modifier.width(380.dp), contentAlignment = Alignment.Center) {
                        Sun(period.hours, sunScale(period.minutes), playing, motion, Modifier.fillMaxWidth(), selected = hour, onTap = { h -> hour = if (h == hour) null else h })
                        Centre(period, hour, Modifier.width(120.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Kicker("Your rotation")
                    Text(
                        buildAnnotatedString { append(listenerTitle(period, stats.firstDay)); withStyle(SpanStyle(color = Palette.accent)) { append(".") } },
                        fontFamily = Heading, fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-1.5).sp, color = Palette.ink, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(listenerLine(period, stats.firstDay), color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    if (stats.periods.size > 1) @OptIn(ExperimentalLayoutApi::class) FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats.periods.forEach { p -> Chip("${p.label} · ${count(p.minutes)} min", p.id == period.id) { picked = p.id } }
                    }
                    if (period.seconds > 0) Column(Modifier.padding(top = 28.dp)) { Tally(period) }
                }
                if (wide) SpotifriedTall(onSpotifried, Modifier.width(360.dp))
            }
            if (!wide) SpotifriedBand(onSpotifried, Modifier.padding(top = 36.dp))
            if (period.seconds > 0) TopLists(services, period, currentId)
        } }
    }
    if (!stats.firstDay) item("days") {
        SectionTitle("Every day you've played")
        DaysGrid(stats)
        Text("${stats.longestRun} ${if (stats.longestRun == 1) "day" else "days"} in a row, your longest.", fontFamily = Heading, fontSize = 22.sp, color = Palette.ink, modifier = Modifier.padding(top = 16.dp))
        Text("You've played on ${stats.daysPlayed} of ${stats.days.size} days.", color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
    }
    item("end") {
        stats.first?.let { f ->
            SectionTitle("It started here")
            val song = f.toSong()
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.ink.copy(alpha = 0.05f)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(f.thumbnailUrl ?: f.thumbPath, 64.dp, RoundedCornerShape(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontFamily = Display, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.ink)
                    Text(f.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
                    Text(stats.firstAt, color = Palette.ink2, fontSize = 13.sp)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.accent).clickable { services.player.playList(listOf(song), 0, "Where it started") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Play, "Play it again", Modifier.size(22.dp), tint = Palette.onAccent)
                }
            }
        }
        val all = stats.periods.last()
        if (stats.days.size > 7) Text("${stats.days.size} days. ${count(all.songs)} songs. Still going.", fontFamily = Heading, fontSize = 28.sp, color = Palette.ink, modifier = Modifier.padding(top = 32.dp))
    }
}

/** In the hole of the sun: the whole period, or the hour clicked. */
@Composable
private fun Centre(p: Period, hour: Int?, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    val small = 12.sp
    if (hour != null) {
        val minutes = minutesOf(p.hours[hour])
        Text(hourName(hour), color = Palette.ink2, fontSize = small)
        Text(count(minutes), color = Palette.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("minutes", color = Palette.ink2, fontSize = small)
        if (minutes > 0) p.hourSongs[hour]?.let { Text(it.title, color = Palette.ink, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else {
        Text(count(p.minutes), color = Palette.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("minutes", color = Palette.ink2, fontSize = small)
        Text("${count(p.songs)} ${if (p.songs == 1) "song" else "songs"}", color = Palette.ink2, fontSize = small)
    }
}

/** Plays, songs, artists and days played, then what was new. */
@Composable
private fun Tally(p: Period) {
    val stats = listOfNotNull(
        Triple(p.plays, "play", "plays"), Triple(p.songs, "song", "songs"),
        Triple(p.artistCount, "artist", "artists").takeIf { p.artistCount > 0 }, Triple(p.activeDays, "day", "days"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        stats.forEach { (n, one, many) ->
            Column {
                Text(count(n), fontFamily = Display, fontSize = 48.sp, lineHeight = 50.sp, color = Palette.ink)
                Text(if (n == 1) one else many, color = Palette.ink2, fontSize = 14.sp)
            }
        }
    }
    if (p.newSongs > 0) Text(
        buildAnnotatedString { withStyle(SpanStyle(color = Palette.accent, fontWeight = FontWeight.Bold)) { append(count(p.newSongs)) }; append(if (p.newSongs == 1) " song new to you" else " songs new to you") },
        color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp),
    )
}

/** The song heard most in the period: a big cover, how often and how long, and Play. */
@Composable
private fun NumberOne(t: com.spotifry.music.data.TopSong, list: List<Song>, onPlay: () -> Unit) {
    val s = list[0]
    Row(
        Modifier.padding(top = 8.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.ink.copy(alpha = 0.05f)).clickable(onClick = onPlay).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.song.thumbnailUrl ?: t.song.thumbPath, 150.dp, RoundedCornerShape(14.dp), large = true)
        Column(Modifier.weight(1f)) {
            Text("Your no. 1", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text(s.title, fontFamily = Heading, fontSize = 28.sp, lineHeight = 30.sp, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(s.artist, color = Palette.ink2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${count(t.plays)} ${if (t.plays == 1) "play" else "plays"} · ${com.spotifry.music.data.longDuration(t.minutes)}", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.accent), contentAlignment = Alignment.Center) { Icon(Icons.Play, "Play", Modifier.size(26.dp), tint = Palette.onAccent) }
    }
}

/** Top songs and top artists side by side. */
@Composable
private fun TopLists(services: Services, p: Period, currentId: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1.3f)) {
            SectionTitle("You keep coming back")
            if (p.topSongs.size < MIN_TOP) Text("Your top songs show up once you've heard three.", color = Palette.ink2, fontSize = 15.sp)
            else {
                val list = p.topSongs.map { it.song.toSong() }
                NumberOne(p.topSongs[0], list) { services.player.playList(list, 0, "Your top songs") }
                list.drop(1).forEachIndexed { i, s -> SongLine(services, i + 2, s, playing = s.id == currentId) { services.player.playList(list, i + 1, "Your top songs") } }
            }
        }
        Column(Modifier.weight(1f)) {
            SectionTitle("The ones you play most")
            if (p.artists.size < MIN_TOP) Text("Your top artists show up once you've heard three.", color = Palette.ink2, fontSize = 15.sp)
            else {
                val first = p.artists[0].seconds
                p.artists.forEachIndexed { i, a ->
                    val size = (16 + 18 * (a.seconds.toFloat() / first).pow(1.1f)).sp
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(a.name, fontFamily = Display, fontSize = size, lineHeight = size * 1.08f, color = if (i == 0) Palette.accent else Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (a.minutes >= 600) "${(a.minutes / 60f).toInt()} hours" else "${count(a.minutes)} min", color = Palette.ink2, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** One dot per day, a week to a column with Monday on top; brighter the more was played. Today has a ring. */
@Composable
private fun DaysGrid(stats: ListeningStats) {
    val lead = stats.firstDate.dayOfWeek.value - 1
    val realWeeks = (lead + stats.days.size + 6) / 7
    val most = max(stats.days.maxOrNull() ?: 1L, 1L).toFloat()
    val measurer = rememberTextMeasurer()
    val ink = Palette.ink; val ink2 = Palette.ink2; val accent = Palette.accent; val bg = Palette.bg; val empty = Palette.high
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = 18.dp
        val shownWeeks = (maxWidth / cell).toInt().coerceAtLeast(1)
        // a few days alone don't read as a calendar: the weeks before the first day fill the width, dimmer; only the latest fit
        val pad = max(0, shownWeeks - realWeeks)
        val skip = max(0, realWeeks - shownWeeks)
        val gridStart = stats.firstDate.minusDays(lead + pad * 7L).plusWeeks(skip.toLong())
        Canvas(Modifier.fillMaxWidth().height(cell * 7 + 24.dp)) {
            val c = cell.toPx(); val top = 22.dp.toPx(); val gap = 4.dp.toPx()
            for (col in 0 until shownWeeks) for (row in 0 until 7) {
                val day = gridStart.plusDays(col * 7L + row)
                val i = ChronoUnit.DAYS.between(stats.firstDate, day).toInt()
                if (i >= stats.days.size) continue
                val centre = Offset(col * c + c / 2, top + row * c + c / 2)
                if (i < 0) { drawCircle(empty, (c - gap) / 2, centre, alpha = .45f); continue }
                val v = stats.days[i]
                val today = i == stats.days.lastIndex
                drawCircle(if (v == 0L) empty else lerp(bg, accent, .28f + .72f * sqrt(v / most)), (c - gap) / 2 - if (today) 2.dp.toPx() else 0f, centre)
                if (today) drawCircle(ink, c / 2 - 1.5.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
            }
            // a month's name above the week that holds its first day, when the last name is 3 weeks back
            var last = -3
            var d = gridStart.withDayOfMonth(1).let { if (it.isBefore(gridStart)) it.plusMonths(1) else it }
            val end = stats.firstDate.plusDays(stats.days.size - 1L)
            while (!d.isAfter(end)) {
                val col = (ChronoUnit.DAYS.between(gridStart, d) / 7).toInt()
                if (col >= last + 3) { drawText(measurer, d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), Offset(col * c, 0f), TextStyle(color = ink2, fontSize = 11.sp)); last = col }
                d = d.plusMonths(1)
            }
        }
    }
}

/**
 * On Home, beside the song: this week as a sun, what it makes you, and the seven days. Not there until something has
 * been heard; a click opens Your rotation.
 */
@Composable
fun RotationCard(services: Services, modifier: Modifier = Modifier, glass: Boolean = false, onSpotifried: () -> Unit, onOpen: () -> Unit) {
    val lib = services.library
    val any by remember(lib) { lib.db.listens().observeAny() }.collectAsState(false)
    if (!any) return
    val zone = ZoneId.systemDefault()
    val monday = remember { weekStart(LocalDate.now(zone)) }
    val since = remember(monday) { monday.atStartOfDay(zone).toInstant().toEpochMilli() }
    val rows by remember(lib, since) { lib.db.listens().observeSince(since) }.collectAsState(emptyList())
    val hours = remember(rows) { LongArray(24).also { a -> rows.forEach { a[Instant.ofEpochMilli(it.startedAt).atZone(zone).hour] += it.secondsHeard.toLong() } }.toList() }
    val days = remember(rows) { LongArray(7).also { a -> rows.forEach { a[Instant.ofEpochMilli(it.startedAt).atZone(zone).dayOfWeek.value - 1] += it.secondsHeard.toLong() } } }
    val minutes = minutesOf(rows.sumOf { it.secondsHeard.toLong() })
    // heard for 30 s or more, as Your rotation counts them
    val songs = remember(rows) { rows.filter { it.secondsHeard * 1000L >= com.spotifry.music.player.LISTEN_MS }.map { it.songId }.distinct().size }
    val peak = hours.indices.maxBy { hours[it] }
    val playing by services.player.state.collectAsState()
    val motion = remember { SunMotion() }
    val today = LocalDate.now(zone).dayOfWeek.value - 1
    Row(
        // glass: over Home's picture, a dark see-through card with a thin edge
        modifier.clip(RoundedCornerShape(24.dp))
            .then(if (glass) Modifier.frosted(0.5f).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp)) else Modifier.background(Palette.ink.copy(alpha = 0.05f)))
            .clickable(onClick = onOpen).padding(start = 8.dp, end = 26.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (glass) 170.dp else 200.dp), contentAlignment = Alignment.Center) {
            Sun(hours, sunScale(minutes), playing.isPlaying, motion, Modifier.fillMaxSize(), labels = false)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(count(minutes), color = Palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("min", color = Palette.ink2, fontSize = 11.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Kicker("Your rotation · this week")
            Text(
                buildAnnotatedString { append(if (minutes == 0) "Still warm" else "${hourKind(peak)} listener"); withStyle(SpanStyle(color = Palette.accent)) { append(".") } },
                fontFamily = Heading, fontSize = if (glass) 27.sp else 30.sp, lineHeight = 32.sp, color = Palette.ink, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (minutes == 0) "Nothing yet this week. Play something." else "${longDuration(minutes)} · ${count(songs)} ${if (songs == 1) "song" else "songs"}\nMost around ${hourName(peak)}",
                color = Palette.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp),
            )
            // the seven days, Monday first, brighter the more was played; days still to come stay empty
            val most = max(days.maxOrNull() ?: 1L, 1L).toFloat()
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                "MTWTFSS".forEachIndexed { i, letter ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val color = when { i > today -> Palette.high.copy(alpha = .45f); days[i] == 0L -> Palette.high; else -> lerp(Palette.bg, Palette.accent, .45f + .55f * sqrt(days[i] / most)) }
                        Box(Modifier.size(18.dp).clip(CircleShape).background(color))
                        Text(letter.toString(), color = if (i == today) Palette.ink else Palette.ink2, fontSize = 11.sp, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("See your rotation ›", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Spotifried ›", color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSpotifried),
                )
            }
        }
    }
}
