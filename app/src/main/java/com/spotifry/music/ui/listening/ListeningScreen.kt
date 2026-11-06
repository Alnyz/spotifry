package com.spotifry.music.ui.listening

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.ListeningStats
import com.spotifry.music.data.MIN_TOP
import com.spotifry.music.data.Period
import com.spotifry.music.data.PeriodId
import com.spotifry.music.data.TopSong
import com.spotifry.music.data.db.ListenDao
import com.spotifry.music.data.hourKind
import com.spotifry.music.data.hourName
import com.spotifry.music.data.listeningStats
import com.spotifry.music.data.longDuration
import com.spotifry.music.data.minutesOf
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.sunScale
import com.spotifry.music.data.weekStart
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.LocalNowPlaying
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.theme.LocalAccent
import com.spotifry.music.ui.theme.isDark
import com.spotifry.music.ui.theme.onAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private fun count(n: Int) = "%,d".format(Locale.US, n)
private val Tabular = "tnum"
/** Weeks that fit across a phone; more than that and the days scroll sideways, today in view. */
private const val WEEKS_SHOWN = 19

class ListeningViewModel(listens: ListenDao) : ViewModel() {
    /** Null until read, and when the log is empty. */
    val stats = listens.observeAll().map { listeningStats(it) }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** What Home's row shows. Held above the page, so coming back to Home finds the row in place instead of pushing the page down. */
class ListeningRowViewModel(private val listens: ListenDao, private val zone: () -> ZoneId = ZoneId::systemDefault) : ViewModel() {
    class Week(val hours: List<Long>, val minutes: Int)

    /** False until [any] has been read, so Home can wait instead of having the row push the page down. */
    val anyRead = MutableStateFlow(false)
    val any = listens.observeAny().onEach { anyRead.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val since = MutableStateFlow(weekStartMs())
    @OptIn(ExperimentalCoroutinesApi::class)
    val week = since.flatMapLatest { listens.observeSince(it) }.map { rows ->
        val z = zone()
        val hours = LongArray(24).also { a -> rows.forEach { a[Instant.ofEpochMilli(it.startedAt).atZone(z).hour] += it.secondsHeard.toLong() } }.toList()
        Week(hours, minutesOf(rows.sumOf { it.secondsHeard.toLong() }))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Week(List(24) { 0L }, 0))
    val motion = SunMotion()

    /** Each time Home comes back: it may be Monday now. */
    fun refresh() { since.value = weekStartMs() }
    private fun weekStartMs(): Long { val z = zone(); return weekStart(LocalDate.now(z)).atStartOfDay(z).toInstant().toEpochMilli() }
}

/** The big title: what your own hours make you. */
internal fun listenerTitle(p: Period, firstDay: Boolean) = when {
    p.seconds == 0L -> "Still warm"
    firstDay -> "First spark"
    else -> "${hourKind(p.peakHour)} listener"
}

internal fun listenerLine(p: Period, firstDay: Boolean) = when {
    p.seconds == 0L -> "Nothing yet ${p.label.lowercase()}. Play something."
    firstDay -> "${p.minutes} ${if (p.minutes == 1) "minute" else "minutes"} in. This grows as you listen."
    else -> "${longDuration(p.minutes)} of music. Most of it around ${hourName(p.peakHour)}"
}

/** Your own page: what the phone has counted, drawn as a sun. No account and no photo; nothing here leaves the phone. */
@Composable
fun ListeningScreen(
    vm: ListeningViewModel, savedIds: Set<String>, playing: Boolean, onBack: () -> Unit, onPlay: (List<Song>, Int, String) -> Unit,
    onMore: (Song) -> Unit, onArtist: (String) -> Unit, bottomInset: Dp,
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    // emptied from elsewhere (Clear listening history): there's no page to show. Waits for a first read that found something.
    var seen by remember { mutableStateOf(false) }
    if (stats != null) seen = true
    LaunchedEffect(seen, stats == null) { if (seen && stats == null) onBack() }
    stats?.let { ListeningContent(it, savedIds, playing, onBack, onPlay, onMore, onArtist, bottomInset) }
}

@Composable
internal fun ListeningContent(
    stats: ListeningStats, savedIds: Set<String>, playing: Boolean, onBack: () -> Unit, onPlay: (List<Song>, Int, String) -> Unit,
    onMore: (Song) -> Unit, onArtist: (String) -> Unit, bottomInset: Dp,
) {
    var picked by rememberSaveable { mutableStateOf<PeriodId?>(null) }
    val period = stats.periods.firstOrNull { it.id == picked } ?: stats.periods.first { it.id == stats.opensOn }
    var hour by remember(period.id) { mutableStateOf<Int?>(null) }
    val motion = remember { SunMotion() }
    val title = listenerTitle(period, stats.firstDay)
    val line = listenerLine(period, stats.firstDay)
    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item("head") {
            IconButton(onBack, Modifier.padding(start = 8.dp, top = 4.dp).size(48.dp)) { Icon(Icons.Back, "Back") }
            Text(
                buildAnnotatedString { append(title); withStyle(SpanStyle(color = accent)) { append(".") } },
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp),
            )
            Text(line, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item("sun") {
            // the hole in the sun has a fixed size, so text there stops growing past this; the line above says the same in full
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.3f))) {
                Box(
                    Modifier.fillMaxWidth().semantics {
                        if (period.seconds > 0) customActions = listOf(
                            CustomAccessibilityAction("Next hour") { hour = ((hour ?: period.peakHour - 1) + 1).mod(24); true },
                            CustomAccessibilityAction("Previous hour") { hour = ((hour ?: period.peakHour + 1) - 1).mod(24); true },
                        )
                    },
                    Alignment.Center,
                ) {
                    Sun(
                        period.hours, sunScale(period.minutes), playing, motion, Modifier.fillMaxWidth(), selected = hour,
                        onTap = { h -> hour = if (h == hour) null else h },
                        description = if (period.seconds == 0L) "Nothing heard yet" else "Your listening by hour of the day. Most around ${hourName(period.peakHour)}",
                    )
                    Centre(period, hour, Modifier.fillMaxWidth(.37f).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        if (stats.periods.size > 1) item("tabs") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).selectableGroup(), Arrangement.spacedBy(6.dp)) {
                stats.periods.forEach { p -> PeriodTab(p, p.id == period.id, Modifier.weight(1f)) { picked = p.id } }
            }
        }
        if (period.seconds > 0) {
            item("tally") { Tally(period) }
            val top = period.topSongs
            if (top.size >= MIN_TOP) {
                val list = top.map { it.song.toSong() }
                item("one") {
                    SectionTitle("You keep coming back")
                    NumberOne(top[0]) { onPlay(list, 0, "Your top songs") }
                }
                itemsIndexed(top.drop(1), key = { _, s -> "top-" + s.song.id }) { i, t ->
                    Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 2}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SongRow(list[i + 1], t.song.id in savedIds, { onPlay(list, i + 1, "Your top songs") }, { onMore(list[i + 1]) }, Modifier.padding(start = 6.dp, end = 8.dp), localCover = t.song.thumbPath, subtitleExtra = longDuration(t.minutes))
                    }
                }
            } else item("no-songs") { Soon("Your top songs show up once you've heard three.") }
            if (period.artists.size >= MIN_TOP) {
                item("artists-title") { SectionTitle("The ones you play most") }
                val first = period.artists[0].seconds
                itemsIndexed(period.artists, key = { _, a -> "artist-" + a.name }) { i, a ->
                    val size = (18 + 25 * (a.seconds.toFloat() / first).pow(1.1f)).sp
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Search for ${a.name}") { onArtist(a.name) }.padding(horizontal = 20.dp, vertical = 7.dp)) {
                        Text(a.name, style = MaterialTheme.typography.displaySmall.copy(fontSize = size, lineHeight = size * 1.08f), color = if (i == 0) accent else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (a.minutes >= 600) "${(a.minutes / 60f).toInt()} hours" else "${count(a.minutes)} min", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (top.size >= MIN_TOP) item("no-artists") { Soon("Your top artists show up once you've heard three.") }
        }
        if (!stats.firstDay) item("days") {
            SectionTitle("Every day you've played")
            DaysGrid(stats)
            Text("${stats.longestRun} ${if (stats.longestRun == 1) "day" else "days"} in a row, your longest.", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall)
            Text("You've played on ${stats.daysPlayed} of ${stats.days.size} days.", Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item("end") {
            stats.first?.let { f ->
                SectionTitle("It started here")
                val song = f.toSong()
                Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                    Cover(f.id, f.thumbnailUrl, f.thumbPath, 72.dp, radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stats.firstAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ onPlay(listOf(song), 0, "Where it started") }, Modifier.size(48.dp).clip(CircleShape).background(accent)) { Icon(Icons.Play, "Play it again", tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            val all = stats.periods.last()
            if (stats.days.size > 7) Text("${stats.days.size} days. ${count(all.songs)} songs. Still going.", Modifier.padding(start = 20.dp, end = 20.dp, top = 36.dp), style = MaterialTheme.typography.headlineMedium)
            Text("Kept only on this phone.", Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One line that shrinks to fit across the hole in the sun. */
@Composable
private fun Fit(text: String, style: TextStyle, color: Color, max: androidx.compose.ui.unit.TextUnit = style.fontSize) {
    BasicText(text, style = style.copy(color = color, textAlign = TextAlign.Center), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = max))
}

@Composable
private fun Centre(p: Period, hour: Int?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val big = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = Tabular)
    val small = MaterialTheme.typography.labelMedium
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (hour != null) {
            val minutes = minutesOf(p.hours[hour])
            Fit(hourName(hour), small, scheme.onSurfaceVariant)
            Fit(count(minutes), big, scheme.onSurface)
            Fit("minutes", small, scheme.onSurfaceVariant)
            if (minutes > 0) p.hourSongs[hour]?.let { Fit(it.title, small, scheme.onSurface) }
        } else {
            Fit(count(p.minutes), big, scheme.onSurface)
            Fit("minutes", small, scheme.onSurfaceVariant)
            Fit("${count(p.songs)} ${if (p.songs == 1) "song" else "songs"}", small, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeriodTab(p: Period, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (on) scheme.onPrimary else scheme.onSurface
    Column(
        modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(if (on) scheme.primary else scheme.surface)
            .selectable(on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        Arrangement.Center,
    ) {
        BasicText(p.label, style = MaterialTheme.typography.labelMedium.copy(color = if (on) scheme.onPrimary else scheme.onSurfaceVariant), maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize))
        BasicText(count(p.minutes), style = MaterialTheme.typography.titleMedium.copy(color = ink, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = Tabular), maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 17.sp))
    }
}

/** The joke is the point: you skip as much as you like, and it's counted. The ads line is always 0: the app doesn't play any. */
@Composable
private fun Tally(p: Period) {
    val scheme = MaterialTheme.colorScheme
    val big = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = Tabular)
    @Composable fun line(n: Int, words: String, color: Color = scheme.onSurface) {
        Text(count(n), style = big, color = color)
        Text(words, Modifier.padding(bottom = 14.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 40.dp)) {
        line(p.songs, if (p.songs == 1) "different song" else "different songs")
        if (p.skips > 0) line(p.skips, if (p.skips == 1) "skip. Nobody stopped you." else "skips. Nobody stopped you.")
        line(0, "ads. Not one.", scheme.primary)
    }
}

@Composable
private fun NumberOne(t: TopSong, onPlay: () -> Unit) {
    val now = LocalNowPlaying.current
    val current = now != null && now.id == t.song.id
    val shape = RoundedCornerShape(22.dp)
    val hot = LocalAccent.current.dark
    Box(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(1f).clip(shape)
            .clickable(onClickLabel = if (current) "Open Now playing" else "Play", onClick = if (current) now!!.open else onPlay),
    ) {
        CoverArt(t.song.id, t.song.thumbnailUrl, t.song.thumbPath, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.38f to Color.Transparent, .84f to Color(0xE608090C))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp), Arrangement.spacedBy(12.dp), Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Your no. 1", Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .18f)).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Color.White)
                Text(t.song.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.song.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${count(t.plays)} ${if (t.plays == 1) "play" else "plays"} · ${longDuration(t.minutes)}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(hot), Alignment.Center) { Icon(Icons.Play, null, tint = onAccent(hot)) }
        }
    }
}

@Composable
private fun Soon(text: String) = Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** One dot per day, a week to a column with Monday on top; brighter the more was played. */
@Composable
private fun DaysGrid(stats: ListeningStats) {
    val scheme = MaterialTheme.colorScheme
    val lead = stats.firstDate.dayOfWeek.value - 1
    val weeks = (lead + stats.days.size + 6) / 7
    val most = max(stats.days.maxOrNull() ?: 1L, 1L).toFloat()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val dark = isDark()
    BoxWithConstraints(Modifier.padding(horizontal = 20.dp).fillMaxWidth().semantics { contentDescription = "${stats.daysPlayed} of ${stats.days.size} days with listening" }) {
        val cell = maxWidth / WEEKS_SHOWN
        val scroll = rememberScrollState()
        LaunchedEffect(weeks, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Canvas(Modifier.horizontalScroll(scroll).width(if (weeks > WEEKS_SHOWN) cell * weeks else maxWidth).height(cell * 7 + 24.dp)) {
            val c = cell.toPx(); val top = 22.dp.toPx(); val gap = 3.dp.toPx()
            val base = scheme.background
            stats.days.forEachIndexed { i, s ->
                val at = i + lead
                val x = at / 7 * c; val y = top + at % 7 * c
                val centre = Offset(x + c / 2, y + c / 2)
                val color = if (s == 0L) scheme.surfaceContainerHigh else lerp(base, scheme.primary, .28f + .72f * sqrt(s / most))
                drawCircle(color, (c - gap) / 2, centre)
                if (i == stats.days.lastIndex) drawCircle(scheme.onSurface, (c - gap) / 2 + 1.5.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
            }
            var d = stats.firstDate; var last = 0
            while (!d.isAfter(stats.firstDate.plusDays(stats.days.size - 1L))) {
                val i = ChronoUnit.DAYS.between(stats.firstDate, d).toInt()
                val col = (i + lead) / 7
                if (i == 0 || col >= last + 3) { drawText(measurer, d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), Offset(col * c, 0f), labelStyle); last = col }
                d = d.withDayOfMonth(1).plusMonths(1)
            }
        }
    }
}

/** On Home: your sun, small, and how much you've heard this week. Not there until something has been heard. */
@Composable
fun ListeningRow(vm: ListeningRowViewModel, playing: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(vm) { vm.refresh(); onPauseOrDispose {} }
    val any by vm.any.collectAsStateWithLifecycle()
    if (!any) return
    val week by vm.week.collectAsStateWithLifecycle()
    val minutes = week.minutes
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen).padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
    ) {
        Sun(week.hours, sunScale(minutes), playing, vm.motion, Modifier.size(64.dp), small = true)
        Column(Modifier.weight(1f)) {
            Text("Your listening", style = MaterialTheme.typography.labelLarge)
            if (minutes > 0) Text("${count(minutes)} min this week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
