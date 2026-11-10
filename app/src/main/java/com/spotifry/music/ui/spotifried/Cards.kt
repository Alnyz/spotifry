package com.spotifry.music.ui.spotifried

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.rememberTextMeasurer
import android.os.SystemClock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import com.spotifry.music.data.hourName
import com.spotifry.music.data.spotifried.listenerType
import com.spotifry.music.data.spotifried.typeFacts
import com.spotifry.music.data.spotifried.typeTitle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import android.os.Build
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import com.spotifry.music.data.spotifried.MonthTop
import com.spotifry.music.ui.listening.count
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.cleanArtist
import com.spotifry.music.data.longDuration
import com.spotifry.music.data.minutesOf
import com.spotifry.music.data.spotifried.SpotifriedStats
import com.spotifry.music.data.spotifried.WrappedPeriod
import com.spotifry.music.data.sunScale
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.listening.Sun
import com.spotifry.music.ui.listening.SunMotion
import com.spotifry.music.ui.listening.rememberNoMotion
import com.spotifry.music.ui.theme.Albert
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The one curve every card moves on: quick out, long settle. */
private val Ease = CubicBezierEasing(.16f, 1f, .3f, 1f)
private val Headline = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 46.sp, lineHeight = 45.sp, letterSpacing = (-1.6).sp)
private val Sub = TextStyle(fontFamily = Albert, fontSize = 15.sp, lineHeight = 22.sp)
private const val TABULAR = "tnum"
private val english = Locale.ENGLISH

/** Before "played back.": "2026,", "Your last 12 months,", "Everything,". */
internal fun introLead(p: WrappedPeriod): String = when {
    p.label.length == 4 && p.label.all { it.isDigit() } -> "${p.label},"
    p.label == "All time" -> "Everything,"
    else -> "Your ${p.label.replaceFirstChar { it.lowercase() }},"
}

/** Before the hours: "You gave 2026 ", "Twelve months, ", "All told, "; as short as the mockup's line. */
internal fun hoursLead(p: WrappedPeriod): String = when {
    p.label.length == 4 && p.label.all { it.isDigit() } -> "You gave ${p.label} "
    p.label == "Last 12 months" -> "Twelve months, "
    p.label == "All time" -> "All told, "
    else -> "${p.label}, "
}

private val MAX_SLOT = 6.dp

/** How many bars fit [width], and each one's slot: about 2.5 dp a bar, but never wider than [MAX_SLOT], so a few days stay thin. */
internal fun barLayout(days: Int, width: Dp): Pair<Int, Dp> {
    val n = minOf(days, (width / 2.5.dp).toInt()).coerceAtLeast(1)
    return n to minOf(width / n, MAX_SLOT)
}

/**
 * The dates under the bars, each different. [full]: the bars span the width, so start, middle and end spread across it;
 * otherwise one line from the first day to the last.
 */
internal fun dateLabels(start: LocalDate, days: Int, full: Boolean): List<String> {
    val end = start.plusDays(days - 1L)
    val fmt = DateTimeFormatter.ofPattern(when { start.year != end.year -> "MMM yyyy"; days > 62 -> "MMM"; else -> "d MMM" }, english)
    val all = listOf(start, start.plusDays(days / 2L), end).map { it.format(fmt) }.distinct()
    return if (full) all else listOf(listOf(all.first(), all.last()).distinct().joinToString(" – "))
}

/** Which of [bars] bars day [day] of [days] falls in. */
internal fun barOf(day: Int, days: Int, bars: Int) = day * bars / days

/**
 * One bar per day, or per few days once there are more than [cap]; a bar is its busiest day as a share of the busiest of
 * all. Square root, so an ordinary day still shows next to a nine-hour one.
 */
internal fun barHeights(daySeconds: List<Long>, cap: Int = Int.MAX_VALUE): List<Float> {
    val n = minOf(daySeconds.size, cap)
    val most = LongArray(n)
    daySeconds.forEachIndexed { d, s -> val b = barOf(d, daySeconds.size, n); most[b] = maxOf(most[b], s) }
    val peak = most.maxOrNull()?.takeIf { it > 0 } ?: return List(n) { 0f }
    return most.map { sqrt(it.toFloat() / peak) }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "9 hours", "1 hour", "40 minutes". */
private fun amount(seconds: Long): String {
    val h = (seconds / 3600.0).roundToInt()
    return if (h >= 1) plural(h, "hour") else plural(minutesOf(seconds), "minute")
}

/** 0 to 1, after [delayMs], on [Ease]; already 1 when [calm]. Restarts each time the card is shown, or its period changes (see `key` in the cards). */
@Composable
private fun progress(calm: Boolean, delayMs: Int, durationMs: Int): Animatable<Float, *> {
    val p = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(p) { p.animateTo(1f, tween(durationMs, delayMs, Ease)) }
    return p
}

@Composable
private fun countUp(to: Int, calm: Boolean): String = count((to * progress(calm, 500, 1500).value).roundToInt())

/** Fades in and lifts into place; [order] staggers the lines of a card one after another. */
@Composable
private fun Modifier.rise(order: Int, calm: Boolean): Modifier = riseAt(order * 180, calm)

@Composable
private fun Modifier.riseAt(delayMs: Int, calm: Boolean, durationMs: Int = 900): Modifier {
    val p = progress(calm, delayMs, durationMs)
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    return graphicsLayer { alpha = p.value; translationY = (1 - p.value) * lift }
}

/** A sun that grows in from nothing, or is already grown when [calm]. */
@Composable
private fun rememberSunMotion(hours: List<Long>, scale: Float, calm: Boolean) = remember {
    SunMotion().apply {
        if (calm) { val peak = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat(); hours.forEachIndexed { i, v -> now[i] = v / peak }; this.scale = scale }
    }
}

@Composable
private fun CardColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 28.dp), content = content)

@Composable
private fun soft() = MaterialTheme.colorScheme.onBackground.copy(alpha = .64f)

/** Card 1: the title, and the person's own sun rising from the bottom edge. */
@Composable
internal fun IntroCard(s: SpotifriedStats, still: Boolean = false) {
    val calm = still || rememberNoMotion()
    key(s.period, calm) { Intro(s, calm) }
}

@Composable
private fun Intro(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val scale = sunScale(minutesOf(s.seconds))
    val motion = rememberSunMotion(s.hours, scale, calm)
    // the busiest hour points straight up, so the part above the edge is the person's longest rays whatever their hours
    val turn = -(s.hours.indices.maxByOrNull { s.hours[it] } ?: 0) * 15f
    val up = progress(calm, 200, 1400)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val side = maxWidth * 1.33f
        val drop = with(LocalDensity.current) { 140.dp.toPx() }
        Box(
            Modifier.align(Alignment.BottomCenter).offset(y = side / 2 - 50.dp).requiredSize(side)
                .graphicsLayer { translationY = (1 - up.value) * drop; alpha = up.value },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .34f), Color.Transparent)), size.width / 2) }
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }) {
                Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false)
            }
        }
        CardColumn {
            Text(
                buildAnnotatedString { append(introLead(s.period)); append("\nplayed\n"); withStyle(SpanStyle(color = accent)) { append("back.") } },
                Modifier.rise(0, calm), style = Headline,
            )
            Text("Tap to start. This one is about you.", Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub)
        }
    }
}

/** Card 2: the most played song, its cover pulsing like it's still on repeat, and the plays counting up. */
@Composable
internal fun TopSongCard(s: SpotifriedStats, still: Boolean = false) {
    val top = s.topSong ?: return
    val calm = still || rememberNoMotion()
    key(s.period, calm) { TopSong(top, calm) }
}

@Composable
private fun TopSong(top: com.spotifry.music.data.TopSong, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val plays = countUp(top.plays, calm)
    val ring = if (calm) null else rememberInfiniteTransition("rings").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), "ring")
    val shape = RoundedCornerShape(18.dp)
    CardColumn {
        Column(Modifier.weight(1f)) {
            // smaller than the other headlines and three lines at most, so the cover keeps about 200 dp on a 780 dp phone
            Text("You couldn't leave this one alone.", Modifier.rise(0, calm), maxLines = 3, overflow = TextOverflow.Ellipsis, style = Headline.copy(fontSize = 38.sp, lineHeight = 37.sp, letterSpacing = (-1.3).sp))
            Box(
                Modifier.padding(top = 20.dp).weight(1f, fill = false).widthIn(max = 230.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .testTag("top_cover").rise(1, calm),
            ) {
                // a soft accent glow under the cover: inset 18 dp, 24 dp lower, blurred. The blur needs Android 12.
                if (Build.VERSION.SDK_INT >= 31) Box(
                    Modifier.matchParentSize().offset(y = 24.dp).padding(horizontal = 18.dp, vertical = 18.dp)
                        .blur(20.dp, BlurredEdgeTreatment.Unbounded).background(accent.copy(alpha = .45f), shape),
                )
                CoverArt(
                    top.song.id, top.song.thumbnailUrl, top.song.thumbPath,
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        val t = ring?.value ?: return@drawWithContent
                        val base = 10.dp.toPx()
                        repeat(3) { k ->
                            val q = Ease.transform((t + k / 3f) % 1f)
                            val grow = 1 + 21 * q
                            drawCircle(accent, base * grow, center, alpha = .7f * (1 - q), style = Stroke(1.5.dp.toPx() * grow))
                        }
                    },
                    shape,
                )
            }
            Text(
                top.song.title, Modifier.padding(top = 20.dp).rise(2, calm), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = (-.65).sp),
            )
            Text(cleanArtist(top.song.artist), Modifier.padding(top = 4.dp).rise(2, calm), soft(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = Sub.copy(fontSize = 14.sp))
        }
        Row(Modifier.rise(3, calm), Arrangement.spacedBy(8.dp)) {
            Text(
                plays, Modifier.alignByBaseline(), accent,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 58.sp, letterSpacing = (-3.2).sp, fontFeatureSettings = TABULAR),
            )
            Text(if (top.plays == 1) "play" else "plays", Modifier.alignByBaseline(), soft(), style = Sub)
        }
    }
}

/** Card 3: the period's hours counting up, over a barcode of its days with the longest one lit. */
@Composable
internal fun HoursCard(s: SpotifriedStats, still: Boolean = false) {
    val calm = still || rememberNoMotion()
    key(s.period, calm) { Hours(s, calm) }
}

@Composable
private fun Hours(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onBackground.copy(alpha = .2f)
    val hours = (s.seconds / 3600.0).roundToInt()
    val (total, unit) = if (hours >= 1) hours to (if (hours == 1) "hour." else "hours.") else minutesOf(s.seconds).let { it to if (it == 1) "minute." else "minutes." }
    val shown = countUp(total, calm)
    val days = s.daySeconds.size
    val longest = s.longestDay?.let { ChronoUnit.DAYS.between(s.start, it).toInt() }?.takeIf { it in 0 until days }
    // one clock for all the bars; each starts a little after the one before, so they grow left to right
    val sweep = 1400; val grow = 700; val end = 400f + sweep + grow
    val clock = remember { Animatable(if (calm) end else 0f) }
    LaunchedEffect(clock) { clock.animateTo(end, tween(end.toInt(), easing = LinearEasing)) }
    CardColumn {
        Text(
            buildAnnotatedString { append(hoursLead(s.period)); withStyle(SpanStyle(color = accent, fontFeatureSettings = TABULAR)) { append("$shown $unit") } },
            Modifier.rise(0, calm), style = Headline,
        )
        if (s.seconds >= 86_400) {
            Text("That is ${longDuration(minutesOf(s.seconds))}, with no break.", Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomStart) {
            val (n, slotDp) = barLayout(days, maxWidth)
            val bars = remember(s.daySeconds, n) { barHeights(s.daySeconds, n) }
            val lit = longest?.let { barOf(it, days, bars.size) }
            val full = slotDp * n >= maxWidth - 1.dp
            val tallest = minOf(maxHeight - 30.dp, 270.dp).coerceAtLeast(40.dp)
            if (days > 0) Column {
                Canvas(Modifier.width(slotDp * n).height(tallest)) {
                    val slot = slotDp.toPx()
                    val gap = minOf(1.dp.toPx(), slot * .4f)
                    val stub = 1.5.dp.toPx()
                    bars.forEachIndexed { i, h ->
                        val p = Ease.transform(((clock.value - 400 - i * sweep / bars.size) / grow).coerceIn(0f, 1f))
                        val tall = maxOf(h * size.height, stub) * p
                        drawRoundRect(
                            if (i == lit) accent else faint, Offset(i * slot, size.height - tall), Size(slot - gap, tall),
                            CornerRadius(minOf(1.dp.toPx(), (slot - gap) / 2)),
                        )
                    }
                }
                Row(Modifier.then(if (full) Modifier.fillMaxWidth() else Modifier).padding(top = 8.dp), Arrangement.SpaceBetween) {
                    dateLabels(s.start, days, full).forEach { Text(it, color = soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)) }
                }
            }
        }
        if (longest != null) {
            val date = s.start.plusDays(longest.toLong())
            Text(
                "Longest day: ${amount(s.daySeconds[longest])}, ${date.format(DateTimeFormatter.ofPattern(if (s.start.year != s.period.to.year) "d MMMM yyyy" else "d MMMM", english))}",
                Modifier.padding(top = 8.dp).rise(3, calm), accent, style = Sub.copy(fontSize = 12.sp, lineHeight = 16.sp),
            )
        }
    }
}

/** Places the content with its point [f] of the way across (0 left edge, .5 centre) at [x], kept inside the parent's width. */
private fun Modifier.anchorAt(x: Dp, f: Float = .5f) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0))
    layout(c.maxWidth, p.height) { p.place((x.roundToPx() - (p.width * f).roundToInt()).coerceIn(0, maxOf(0, c.maxWidth - p.width)), 0) }
}

/** Where [clock] is in a step that starts at [delay] and takes [dur] ms, on [Ease]. */
private fun stepAt(clock: Float, delay: Float, dur: Float) = Ease.transform(((clock - delay) / dur).coerceIn(0f, 1f))

/** One clock in ms for a card whose parts start one after another; already at [end] when [calm]. */
@Composable
private fun clock(calm: Boolean, end: Float): Animatable<Float, *> {
    val c = remember { Animatable(if (calm) end else 0f) }
    LaunchedEffect(c) { c.animateTo(end, tween(end.toInt(), easing = LinearEasing)) }
    return c
}

/** One dot per song, oldest first: its year and its place in that year's stack. */
internal fun yearStacks(years: List<Int>): List<Pair<Int, Int>> = years.sorted().groupBy { it }.flatMap { (y, l) -> l.indices.map { y to it } }

internal fun topDecade(years: List<Int>): Int? = years.groupingBy { it / 10 * 10 }.eachCount().maxByOrNull { it.value }?.key

/** "90s", "2000s"; the 1900s keep all four digits. */
internal fun decadeName(d: Int) = if (d in 1910..1990) "${d % 100}s" else "${d}s"

/** Space between dots in a stack: 10 dp at most, squeezed so the tallest stack ([levels] above its first dot) stays within [room]. */
internal fun stackStep(levels: Int, room: Dp): Dp = if (levels == 0) 10.dp else minOf(10.dp, room.coerceAtLeast(0.dp) / levels)

/** Card 4: a dot per song falls onto its release year, the typical year is marked, then the listening age counts up. */
@Composable
internal fun AgeCard(s: SpotifriedStats, checked: Int, unchecked: Int, still: Boolean = false) {
    val calm = still || rememberNoMotion()
    // the age can arrive while the card is open, mid-lookup; it starts again then, so the age still counts up
    key(s.period, calm, s.age.age != null) { Age(s, checked, unchecked, calm) }
}

@Composable
private fun Age(s: SpotifriedStats, checked: Int, unchecked: Int, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = ink.copy(alpha = .14f)
    val a = s.age
    // the stats give an age once 20 songs have a year, even while others are still being looked up
    val typical = a.typicalYear.takeIf { a.age != null }
    val dots = remember(a.years) { yearStacks(a.years) }
    val lo = minOf(1960, (a.years.minOrNull() ?: 1960) / 10 * 10)
    val hi = maxOf(s.period.to.year, a.years.maxOrNull() ?: 0, lo + 20)
    val t = clock(calm, 4600f)
    CardColumn {
        val head = Headline.copy(fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp)
        if (typical != null) {
            Text(buildAnnotatedString { append("You listen like someone who was "); withStyle(SpanStyle(color = accent)) { append("14 in $typical.") } }, style = head)
        } else {
            Text(buildAnnotatedString { append("How old is "); withStyle(SpanStyle(color = accent)) { append("your music?") } }, style = head)
            Text("Checking song years: ${count(checked)} of ${count(checked + unchecked)}", Modifier.padding(top = 10.dp), soft(), style = Sub)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val axis = maxHeight - 22.dp
            fun xOf(y: Int) = 4.dp + (maxWidth - 8.dp) * ((y - lo).toFloat() / (hi - lo))
            val step = stackStep(dots.maxOfOrNull { it.second } ?: 0, axis - 12.dp - 44.dp)
            val dot = (step * .8f).coerceIn(3.dp, 8.dp)
            val lift = with(LocalDensity.current) { 18.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val ax = axis.toPx()
                drawRect(faint, Offset(0f, ax), Size(size.width, 1.dp.toPx()))
                dots.forEach { (y, k) ->
                    val p = stepAt(t.value, 300 + (y - lo) * 1450f / (hi - lo) + k * 25f, 800f)
                    val cy = ax - 12.dp.toPx() - k * step.toPx() - (1 - p) * 120.dp.toPx()
                    drawCircle(ink, dot.toPx() / 2, Offset(xOf(y).toPx(), cy), alpha = .9f * p)
                }
                if (typical != null) {
                    val p = stepAt(t.value, 2200f, 800f)
                    val top = 26.dp.toPx()
                    drawRoundRect(accent, Offset(xOf(typical).toPx() - 1.dp.toPx(), ax - (ax - top) * p), Size(2.dp.toPx(), (ax - top) * p), CornerRadius(1.dp.toPx()))
                }
            }
            val small = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)
            val every = if (hi - lo > 100) 40 else 20
            (((lo + every - 1) / every * every)..hi step every).forEach {
                Text("$it", Modifier.align(Alignment.BottomStart).anchorAt(xOf(it)), soft(), style = small)
            }
            if (typical != null) Text(
                "$typical", Modifier.anchorAt(xOf(typical)).padding(top = 4.dp)
                    .graphicsLayer { val p = stepAt(t.value, 2800f, 600f); alpha = p; translationY = (1 - p) * lift },
                accent, style = small.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
            )
            a.oldestYear?.let { old ->
                // ends at the oldest dot, so it stays left of the typical year's line; lifted above the stacks under its ~90 dp
                val left = (xOf(old) + 4.dp - 90.dp).coerceAtLeast(0.dp)
                val under = dots.filter { xOf(it.first) in left..left + 90.dp }.maxOfOrNull { it.second } ?: 0
                Text(
                    "$old, your oldest", Modifier.offset(y = (axis - 12.dp - step * under - 26.dp).coerceAtLeast(22.dp)).anchorAt(xOf(old) + 4.dp, 1f)
                        .graphicsLayer { val p = stepAt(t.value, 1800f, 600f); alpha = p; translationY = (1 - p) * lift },
                    soft(), style = small,
                )
            }
        }
        val shown = if (typical != null) count((a.age!! * stepAt(t.value, 3100f, 1500f)).roundToInt()) else "?"
        val line = if (typical != null) "Your listening age." + (topDecade(a.years)?.let { " Most of your songs are from the ${decadeName(it)}." } ?: "")
            else "Needs 20 songs with a year."
        Row(Modifier.padding(top = 6.dp), Arrangement.spacedBy(10.dp)) {
            Text(
                shown, Modifier.alignByBaseline(), accent,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 112.sp, lineHeight = 96.sp, letterSpacing = (-5.6).sp, fontFeatureSettings = TABULAR),
            )
            Text(line, Modifier.alignByBaseline().graphicsLayer { val p = stepAt(t.value, 3100f, 800f); alpha = p; translationY = (1 - p) * 18.dp.toPx() }, soft(), style = Sub)
        }
        Text("Song years from MusicBrainz", Modifier.padding(top = 8.dp), soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp))
    }
}

internal fun dayText(d: LocalDate, withYear: Boolean): String = d.format(DateTimeFormatter.ofPattern(if (withYear) "d MMMM yyyy" else "d MMMM", english))

/** At most about [max] characters, cut at a word, so the title stays within two lines of the sentence. */
internal fun shortTitle(t: String, max: Int = 60): String {
    if (t.length <= max) return t
    val word = t.take(max).substringBeforeLast(' ').trimEnd(',', ' ', '-')
    // a cut at a word that loses a third or more is cut mid-word instead
    return (if (word.length >= max * 2 / 3) word else t.take(max).trimEnd()) + "\u2026"
}

/** Card 5: the day one song was played the most, one tick per play across the 24 hours, each taller than the one before. */
@Composable
internal fun DayCard(s: SpotifriedStats, still: Boolean = false) {
    val day = s.obsession ?: return
    val calm = still || rememberNoMotion()
    key(s.period, calm) { Day(s, day, calm) }
}

@Composable
private fun Day(s: SpotifriedStats, day: com.spotifry.music.data.spotifried.ObsessionDay, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = ink.copy(alpha = .14f)
    val ticks = remember(day) { day.startHours.sorted() }
    // a long day of plays still lands within about two seconds
    val gap = minOf(70f, 2000f / ticks.size.coerceAtLeast(1))
    val t = clock(calm, 500 + gap * ticks.size + 550)
    val times = countUp(day.plays, calm)
    CardColumn {
        Text(
            buildAnnotatedString { append("On ${dayText(day.date, s.start.year != s.period.to.year)}, "); withStyle(SpanStyle(color = accent)) { append("something happened.") } },
            Modifier.rise(0, calm), style = Headline,
        )
        Text(
            buildAnnotatedString {
                append("You played “${shortTitle(day.song.title)}” ")
                withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR)) { append("$times ${if (day.plays == 1) "time" else "times"}") }
                append(" in one day. We won't ask.")
            },
            Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomStart) {
            val strip = minOf(maxHeight - 26.dp, 190.dp).coerceAtLeast(40.dp)
            Column(Modifier.rise(2, calm)) {
                Canvas(Modifier.fillMaxWidth().height(strip)) {
                    val w = 4.dp.toPx()
                    drawRect(faint, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                    ticks.forEachIndexed { i, h ->
                        val tall = (.35f + .65f * (i + 1) / ticks.size) * size.height * stepAt(t.value, 500 + i * gap, 550f)
                        drawRoundRect(accent, Offset(h / 24f * (size.width - w), size.height - tall), Size(w, tall), CornerRadius(w / 2))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween) {
                    listOf("00", "06", "12", "18", "24").forEach { Text(it, color = soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)) }
                }
            }
        }
    }
}

/** Twelve months at most: the latest twelve that still hold [change]. */
internal fun shownMonths(m: List<MonthTop>, change: YearMonth?): List<MonthTop> {
    val i = m.indexOfFirst { it.month == change }.takeIf { it >= 0 } ?: m.size
    return m.drop(minOf(m.size - 12, i).coerceAtLeast(0)).take(12)
}

/** Card 6: each month's top song, one line each (no play counts, as in the mockup), the month the taste changed most in the accent. */
@Composable
internal fun MonthsCard(s: SpotifriedStats, still: Boolean = false) {
    if (s.months.isEmpty()) return
    val calm = still || rememberNoMotion()
    key(s.period, calm) { Months(s, calm) }
}

@Composable
private fun Months(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onBackground.copy(alpha = .14f)
    val rows = shownMonths(s.months, s.changeMonth)
    val years = rows.first().month.year != rows.last().month.year
    val fmt = DateTimeFormatter.ofPattern(if (years) "MMM ''yy" else "MMM", english)
    val year = s.period.label.length == 4 && s.period.label.all { it.isDigit() }
    val slide = with(LocalDensity.current) { 14.dp.toPx() }
    CardColumn {
        Text(buildAnnotatedString { append(if (year) "Your year,\n" else "Your months,\n"); withStyle(SpanStyle(color = accent)) { append("in order.") } }, style = Headline)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 22.dp)) {
            // rows share the room, up to 72 dp each, so three months read big and twelve still fit
            val rowH = minOf(maxHeight / rows.size, 72.dp)
            val title = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = (rowH.value * .45f).coerceIn(12f, 22f).sp, letterSpacing = (-.3).sp)
            val small = Sub.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Column {
                rows.forEachIndexed { i, m ->
                    val turn = m.month == s.changeMonth
                    val p = progress(calm, 200 + i * 90, 700)
                    Row(
                        Modifier.fillMaxWidth().height(rowH).testTag(if (turn) "change_month" else "month")
                            .graphicsLayer { alpha = p.value; translationX = (1 - p.value) * -slide }
                            .drawBehind { drawRect(faint, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) },
                        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                    ) {
                        Text(m.month.format(fmt), Modifier.width(if (years) 52.dp else 32.dp), soft(), maxLines = 1, style = small)
                        Text(m.song.title, Modifier.weight(1f), if (turn) accent else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis, style = title)
                        if (turn) Text("taste changed", color = accent, style = small.copy(fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

/** Before "a sun.": the period in words. */
internal fun sunLead(p: WrappedPeriod): String = when {
    p.label.length == 4 && p.label.all { it.isDigit() } -> "This is your year as "
    p.label == "Last 12 months" -> "This is your twelve months as "
    p.label == "All time" -> "This is all your listening as "
    else -> "This is your listening as "
}

/** Card 7: the person's sun, big and not turned, so the hour labels around it are true; it is drawn round from midnight like a clock hand. */
@Composable
internal fun SunCard(s: SpotifriedStats, still: Boolean = false) {
    val calm = still || rememberNoMotion()
    key(s.period, calm) { SunPortrait(s, calm) }
}

@Composable
private fun SunPortrait(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val scale = sunScale(minutesOf(s.seconds))
    val motion = rememberSunMotion(s.hours, scale, calm)
    val t = clock(calm, 2300f)
    // labels can sit over long rays, so each gets a dark halo
    val label = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, shadow = Shadow(Color.Black, blurRadius = 8f))
    Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 28.dp)) {
        Column(Modifier.padding(horizontal = 26.dp)) {
            Text(buildAnnotatedString { append(sunLead(s.period)); withStyle(SpanStyle(color = accent)) { append("a sun.") } }, Modifier.rise(0, calm), style = Headline)
            Text(
                "Each ray is one hour of your day. Longer means you listened more. Nobody else has this one.",
                Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), Alignment.Center) {
            // a little wider than the screen, as in the mockup: only the longest rays at 6 a.m. or 6 p.m. reach the edges
            val side = minOf(maxWidth * 1.08f, maxHeight)
            // the glow fades in whole; only the rays sweep round, so the middle never shows a pie slice
            Canvas(Modifier.requiredSize(side).graphicsLayer { alpha = stepAt(t.value, 500f, 1800f) }) {
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .3f), Color.Transparent)), size.width / 2)
            }
            Box(
                Modifier.requiredSize(side).testTag("sun").drawWithContent {
                    val sweep = 360f * stepAt(t.value, 500f, 1800f)
                    if (sweep >= 360f) drawContent()
                    else clipPath(Path().apply { moveTo(center.x, center.y); arcTo(Rect(center, size.maxDimension), -90f, sweep, false); close() }) {
                        this@drawWithContent.drawContent()
                    }
                },
            ) { Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false) }
            // a clock face: just inside the longest rays, as in the mockup, each shown once the sweep reaches it
            // side labels are kept 8 dp inside the screen whatever their width, so large text never runs off it
            val r = side * .44f
            listOf(0 to (0.dp to -r), 6 to (r to 0.dp), 12 to (0.dp to r), 18 to (-r to 0.dp)).forEach { (h, at) ->
                Text(
                    hourName(h),
                    Modifier.offset(y = at.second).padding(horizontal = 8.dp).anchorAt(maxWidth / 2 - 8.dp + at.first)
                        .graphicsLayer { alpha = hourLabelAlpha(stepAt(t.value, 500f, 1800f), h) },
                    soft(), maxLines = 1, style = label,
                )
            }
        }
    }
}

/** An hour label fades in over the three hours after the sweep ([sweep] 0 to 1 round the day) reaches hour [h]. */
internal fun hourLabelAlpha(sweep: Float, h: Int) = ((sweep * 24 - h) / 3).coerceIn(0f, 1f)

/** Where the first figure of a fact is, with its "%" or "a.m.", to set in the accent; a percentage wins over a number in a name. */
internal fun factFigure(f: String): IntRange? =
    (Regex("""\d+(?:[.,]\d+)*%""").find(f) ?: Regex("""\d+(?:[.,]\d+)*(?: [ap]\.m\.)?""").find(f))?.range

/** Card 8: who the person is as a listener, three true facts one after another, and Share ([onShare] null: no button, as in the shared picture). */
@Composable
internal fun TypeCard(s: SpotifriedStats, onShare: (() -> Unit)?, still: Boolean = false, working: Boolean = false, failed: Boolean = false) {
    val calm = still || rememberNoMotion()
    key(s.period, calm) { Type(s, onShare, calm, working, failed) }
}

@Composable
private fun Type(s: SpotifriedStats, onShare: (() -> Unit)?, calm: Boolean, working: Boolean, failed: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val title = typeTitle(s) + "."
    val facts = remember(s) { typeFacts(s, listenerType(s)) }
    val scale = sunScale(minutesOf(s.seconds))
    val motion = rememberSunMotion(s.hours, scale, calm)
    val up = progress(calm, 0, 1400)
    val button = progress(calm, 1900, 800)
    // tappable only once it has appeared: before that a tap on it goes back a card, as anywhere on the left
    val ready by remember { derivedStateOf { button.value >= 1f } }
    var sharedAt by remember { mutableLongStateOf(-1_000L) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        // the person's own sun, big and dim behind everything, its centre 60 dp above the bottom edge, as in the mockup
        val side = maxWidth * 1.72f
        Box(
            Modifier.align(Alignment.BottomCenter).offset(y = side / 2 - 60.dp).requiredSize(side)
                .graphicsLayer { alpha = .55f * up.value },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .34f), Color.Transparent), radius = size.width * .36f), size.width * .36f) }
            Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false)
        }
        CardColumn {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val big = Headline.copy(fontSize = 56.sp, lineHeight = 55.sp, letterSpacing = (-2).sp)
                val factWidth = minOf(constraints.maxWidth, with(density) { 280.dp.roundToPx() })
                // the title shrinks until its longest word fits a line, so it never breaks inside a word; three words make three lines at most
                val word = remember(title, measurer, constraints.maxWidth) {
                    minOf(1f, constraints.maxWidth * .98f / title.split(' ').maxOf { measurer.measure(it, big).size.width }.coerceAtLeast(1))
                }
                fun titleStyle(k: Float) = big.copy(fontSize = big.fontSize * word * k, lineHeight = big.lineHeight * word * k, letterSpacing = big.letterSpacing * word * k)
                fun factStyle(k: Float) = Sub.copy(fontSize = Sub.fontSize * k, lineHeight = 20.sp * k)
                // large system text: everything shrinks in steps until the title and all three facts fit above the button; scrolling is the last resort
                val fit = remember(title, facts, measurer, constraints) {
                    fun height(k: Float): Int = with(density) {
                        measurer.measure("You are", Sub).size.height + 6.dp.roundToPx() +
                            measurer.measure(title, titleStyle(k), maxLines = 3, constraints = Constraints(maxWidth = constraints.maxWidth)).size.height + 26.dp.roundToPx() +
                            facts.sumOf { measurer.measure(it, factStyle(k), constraints = Constraints(maxWidth = factWidth)).size.height } + 2 * 12.dp.roundToPx()
                    }
                    generateSequence(1f) { it - .05f }.takeWhile { it >= .5f }.firstOrNull { height(it) <= constraints.maxHeight } ?: .5f
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("You are", Modifier.rise(0, calm), soft(), style = Sub)
                    val cut = title.lastIndexOf(' ') + 1
                    Text(
                        buildAnnotatedString { append(title.take(cut)); withStyle(SpanStyle(color = accent)) { append(title.drop(cut)) } },
                        Modifier.padding(top = 6.dp).rise(1, calm), maxLines = 3, style = titleStyle(fit),
                    )
                    Column(Modifier.padding(top = 26.dp).widthIn(max = 280.dp), Arrangement.spacedBy(12.dp)) {
                        facts.forEachIndexed { i, f ->
                            val fig = factFigure(f)
                            Text(
                                buildAnnotatedString {
                                    if (fig == null) append(f)
                                    else { append(f.substring(0, fig.first)); withStyle(SpanStyle(color = accent, fontWeight = FontWeight.ExtraBold)) { append(f.substring(fig)) }; append(f.substring(fig.last + 1)) }
                                },
                                Modifier.riseAt(1000 + i * 250, calm, 800), ink, style = factStyle(fit),
                            )
                        }
                    }
                }
            }
            val lift = with(LocalDensity.current) { 18.dp.toPx() }
            if (onShare != null) Button(
                // a second tap within a second, while the share sheet is still opening, does nothing
                { val now = SystemClock.uptimeMillis(); if (now - sharedAt >= 1000) { sharedAt = now; onShare() } },
                Modifier.padding(top = 12.dp).heightIn(min = 48.dp).graphicsLayer { alpha = button.value; translationY = (1 - button.value) * lift }
                    .then(if (ready) Modifier else Modifier.clearAndSetSemantics {}),
                enabled = ready && !working,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = accent, disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp),
            ) {
                Text(if (working) "Making pictures…" else "Share your story", style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp))
            }
            if (failed) Text("Couldn't make the pictures. Try again.", Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, ink, style = Sub)
        }
    }
}
