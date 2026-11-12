// The phone's Spotifried cards (app/.../ui/spotifried/Cards.kt), on the desktop: same layout at the phone's size, same words,
// no reduced-motion switch, and Save instead of Share on the last card.
package com.spotifry.desktop

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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import com.spotifry.music.data.spotifried.MonthTop
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

private val WIDE_BAR = 14.dp
/** Fewer days than this show as separate wide bars, so only the first and last dates go under them. */
private const val FEW_DAYS = 30

/**
 * How many bars fit [width], and each one's width; the bars always span the whole width. A year is a barcode, about 2.5 dp a
 * bar with a 1 dp gap; a short history has wide bars, [WIDE_BAR] at most, spread out with gaps, so the card never looks empty.
 */
internal fun barLayout(days: Int, width: Dp): Pair<Int, Dp> {
    val n = minOf(days, (width / 2.5.dp).toInt()).coerceAtLeast(1)
    val slot = width / n
    return n to minOf(slot - minOf(1.dp, slot * .4f), WIDE_BAR)
}

/** The dates under the bars, each different: start, middle and end, or only the first and last for a short history. */
internal fun dateLabels(start: LocalDate, days: Int): List<String> {
    val end = start.plusDays(days - 1L)
    val fmt = DateTimeFormatter.ofPattern(when { start.year != end.year -> "MMM yyyy"; days > 62 -> "MMM"; else -> "d MMM" }, english)
    val at = if (days < FEW_DAYS) listOf(start, end) else listOf(start, start.plusDays(days / 2L), end)
    return at.map { it.format(fmt) }.distinct()
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

/** A number counting up; [started] is false until it moves, and till then it is hidden, so a 0 never shows as if real. */
internal class CountUp(val text: String, val started: Boolean)

/** Counts to [to] from [delayMs] (once what it sits in has come in), quickly; already there when [calm]. */
@Composable
private fun countUp(to: Int, calm: Boolean, delayMs: Int): CountUp {
    val p = progress(calm, delayMs, 900).value
    return CountUp(count((to * p).roundToInt()), p > 0f)
}

/** The number as a span: transparent before its count starts, so the line around it keeps its place and never shows 0. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.countSpan(c: CountUp, style: SpanStyle, text: String = c.text) =
    withStyle(if (c.started) style else style.copy(color = Color.Transparent)) { append(text) }

/** Fades in and lifts into place; [order] staggers the lines of a card one after another. */
@Composable
private fun Modifier.rise(order: Int, calm: Boolean): Modifier = riseAt(order * 180, calm)

/** How far a line lifts as it comes in. */
private val RISE = 18.dp

@Composable
private fun Modifier.riseAt(delayMs: Int, calm: Boolean, durationMs: Int = 900): Modifier {
    val p = progress(calm, delayMs, durationMs)
    val lift = with(LocalDensity.current) { RISE.toPx() }
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

/** Fades what it holds out over its bottom [height], so a sun cut by the card's edge ends softly, not on a hard line. */
private fun Modifier.fadeOutBottom(height: Dp = 64.dp) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - height.toPx(), endY = size.height), blendMode = BlendMode.DstIn)
}

/** Card 1: the title, and the person's own sun rising from the bottom edge. */
@Composable
internal fun IntroCard(s: SpotifriedStats, still: Boolean = false) {
    val calm = still
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
        Box(Modifier.fillMaxSize().fadeOutBottom()) {
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = side / 2 - 50.dp).requiredSize(side)
                    .graphicsLayer { translationY = (1 - up.value) * drop; alpha = up.value },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .34f), Color.Transparent)), size.width / 2) }
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }) {
                    Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false, dark = true)
                }
            }
        }
        CardColumn {
            Text(
                buildAnnotatedString { append(introLead(s.period)); append("\nplayed\n"); withStyle(SpanStyle(color = accent)) { append("back.") } },
                Modifier.rise(0, calm), style = Headline,
            )
            Text("Click to start. This one is about you.", Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub)
        }
    }
}

/** Card 2: the most played song, its cover pulsing like it's still on repeat, and the plays counting up. */
@Composable
internal fun TopSongCard(s: SpotifriedStats, still: Boolean = false) {
    val top = s.topSong ?: return
    val calm = still
    key(s.period, calm) { TopSong(top, calm) }
}

@Composable
private fun TopSong(top: com.spotifry.music.data.TopSong, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    // starts once its row has risen in
    val plays = countUp(top.plays, calm, 700)
    val ring = if (calm) null else rememberInfiniteTransition("rings").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), "ring")
    val shape = RoundedCornerShape(18.dp)
    // the rings are drawn behind the whole card, centred on the cover, so they pass under the headline, not over it
    var card by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cover by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(Modifier.fillMaxSize().onPlaced { card = it }.drawBehind {
        val t = ring?.value ?: return@drawBehind
        val from = card?.takeIf { it.isAttached } ?: return@drawBehind
        val c = cover?.takeIf { it.isAttached } ?: return@drawBehind
        val rings = from.localPositionOf(c, Offset(c.size.width / 2f, c.size.height / 2f))
        val base = 10.dp.toPx()
        val line = 1.5.dp.toPx()
        // the widest ring, its stroke included, just meets the nearest edge of the card, so no ring is ever cut by it
        val far = minOf(rings.x, size.width - rings.x, rings.y, size.height - rings.y)
        val most = maxOf(1f, far / (base + line / 2))
        repeat(3) { k ->
            val q = Ease.transform((t + k / 3f) % 1f)
            val grow = 1 + (most - 1) * q
            // quieter than the mockup's .7: wide rings at that strength made the headline busy on a phone
            drawCircle(accent, base * grow, rings, alpha = .4f * (1 - q), style = Stroke(line * grow))
        }
    }) { CardColumn {
        Column(Modifier.weight(1f)) {
            // smaller than the other headlines and three lines at most, so the cover keeps about 200 dp on a 780 dp phone
            Text("You couldn't leave this one alone.", Modifier.rise(0, calm), maxLines = 3, overflow = TextOverflow.Ellipsis, style = Headline.copy(fontSize = 38.sp, lineHeight = 37.sp, letterSpacing = (-1.3).sp))
            Box(
                // up to 300 dp: on a tall phone the cover takes the room rather than leaving it empty under the plays
                // centred, so a cover narrower than the card (a short phone) and the rings round it sit in the middle, not to the left
                Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp).weight(1f, fill = false).widthIn(max = 300.dp)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true).onPlaced { cover = it }.testTag("top_cover").rise(1, calm),
            ) {
                // a soft accent glow under the cover: inset 18 dp, 24 dp lower, blurred
                Box(
                    Modifier.matchParentSize().offset(y = 24.dp).padding(horizontal = 18.dp, vertical = 18.dp)
                        .blur(20.dp, BlurredEdgeTreatment.Unbounded).background(accent.copy(alpha = .45f), shape),
                )
                StoryCover(top.song.thumbnailUrl ?: top.song.thumbPath, Modifier.fillMaxSize(), shape)
            }
            Text(
                top.song.title, Modifier.padding(top = 20.dp).rise(2, calm), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = (-.65).sp),
            )
            Text(cleanArtist(top.song.artist), Modifier.padding(top = 4.dp).rise(2, calm), soft(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = Sub.copy(fontSize = 14.sp))
        }
        Row(Modifier.rise(3, calm), Arrangement.spacedBy(8.dp)) {
            Text(
                plays.text, Modifier.alignByBaseline().graphicsLayer { alpha = if (plays.started) 1f else 0f }, accent,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 58.sp, letterSpacing = (-3.2).sp, fontFeatureSettings = TABULAR),
            )
            Text(if (top.plays == 1) "play" else "plays", Modifier.alignByBaseline(), soft(), style = Sub)
        }
    } }
}

/** Card 3: the period's hours counting up, over a barcode of its days with the longest one lit. */
@Composable
internal fun HoursCard(s: SpotifriedStats, still: Boolean = false) {
    val calm = still
    key(s.period, calm) { Hours(s, calm) }
}

@Composable
private fun Hours(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onBackground.copy(alpha = .2f)
    val hours = (s.seconds / 3600.0).roundToInt()
    val (total, unit) = if (hours >= 1) hours to (if (hours == 1) "hour." else "hours.") else minutesOf(s.seconds).let { it to if (it == 1) "minute." else "minutes." }
    // starts once the headline is mostly in, and runs while the bars grow
    val shown = countUp(total, calm, 450)
    val days = s.daySeconds.size
    val longest = s.longestDay?.let { ChronoUnit.DAYS.between(s.start, it).toInt() }?.takeIf { it in 0 until days }
    // one clock for all the bars; each starts a little after the one before, so they grow left to right
    val sweep = 1400; val grow = 700; val end = 400f + sweep + grow
    val clock = remember { Animatable(if (calm) end else 0f) }
    LaunchedEffect(clock) { clock.animateTo(end, tween(end.toInt(), easing = LinearEasing)) }
    CardColumn {
        Text(
            buildAnnotatedString { append(hoursLead(s.period)); countSpan(shown, SpanStyle(color = accent, fontFeatureSettings = TABULAR), "${shown.text} $unit") },
            Modifier.rise(0, calm), style = Headline,
        )
        if (s.seconds >= 86_400) {
            Text("That is ${longDuration(minutesOf(s.seconds))}, with no break.", Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomStart) {
            val (n, barDp) = barLayout(days, maxWidth)
            val bars = remember(s.daySeconds, n) { barHeights(s.daySeconds, n) }
            val lit = longest?.let { barOf(it, days, bars.size) }
            val tallest = minOf(maxHeight - 30.dp, 270.dp).coerceAtLeast(40.dp)
            if (days > 0) Column {
                Canvas(Modifier.fillMaxWidth().height(tallest).testTag("day_bars")) {
                    val bar = barDp.toPx()
                    // first bar on the left edge, last on the right, so the first and last dates sit under them
                    val step = if (bars.size > 1) (size.width - bar) / (bars.size - 1) else 0f
                    val stub = 1.5.dp.toPx()
                    val round = if (bar >= WIDE_BAR.toPx()) 3.dp.toPx() else minOf(1.dp.toPx(), bar / 2)
                    bars.forEachIndexed { i, h ->
                        val p = Ease.transform(((clock.value - 400 - i * sweep / bars.size) / grow).coerceIn(0f, 1f))
                        val tall = maxOf(h * size.height, stub) * p
                        drawRoundRect(if (i == lit) accent else faint, Offset(i * step, size.height - tall), Size(bar, tall), CornerRadius(minOf(round, tall / 2)))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween) {
                    dateLabels(s.start, days).forEach { Text(it, color = soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)) }
                }
            }
        }
        if (longest != null) {
            val date = s.start.plusDays(longest.toLong())
            Text(
                "Longest day: ${amount(s.daySeconds[longest])}, ${date.format(DateTimeFormatter.ofPattern(if (s.start.year != s.period.to.year) "d MMMM yyyy" else "d MMMM", english))}",
                Modifier.padding(top = 10.dp).rise(3, calm), accent, style = Sub.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
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
private fun cardClock(calm: Boolean, end: Float): Animatable<Float, *> {
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
/** When the listening age starts to show on its card's clock: after the dots have fallen and the typical year is marked. */
private const val AGE_AT = 3000f

internal fun stackStep(levels: Int, room: Dp): Dp = if (levels == 0) 10.dp else minOf(10.dp, room.coerceAtLeast(0.dp) / levels)

/** Card 4: a dot per song falls onto its release year, the typical year is marked, then the listening age counts up. */
@Composable
internal fun AgeCard(s: SpotifriedStats, checked: Int, unchecked: Int, still: Boolean = false) {
    val calm = still
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
    val t = cardClock(calm, 4600f)
    // while years are still being looked up the timeline and the progress under it sit as one group in the middle of the
    // free room; on a short card or with large text the timeline needs all of it
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) { val centred = typical == null && maxHeight >= 520.dp && fontScale < 1.5f; CardColumn {
        val head = Headline.copy(fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp)
        if (typical != null) {
            Text(buildAnnotatedString { append("You listen like someone who was "); withStyle(SpanStyle(color = accent)) { append("14 in $typical.") } }, style = head)
        } else {
            Text(buildAnnotatedString { append("How old is "); withStyle(SpanStyle(color = accent)) { append("your music?") } }, style = head)
        }
        if (centred) Spacer(Modifier.weight(.6f))
        BoxWithConstraints(Modifier.weight(if (centred) .7f else 1f).fillMaxWidth().clipToBounds()) {
            val axis = maxHeight - 22.dp
            fun xOf(y: Int) = 4.dp + (maxWidth - 8.dp) * ((y - lo).toFloat() / (hi - lo))
            val small = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)
            val density = LocalDensity.current
            val labelH = with(density) { small.lineHeight.toDp() }
            // above the stacks: the typical year's label (22 dp), the oldest one's line, and 8 dp of air over the top dot
            val step = stackStep(dots.maxOfOrNull { it.second } ?: 0, axis - 12.dp - (22.dp + labelH + 8.dp + 4.dp))
            val dot = (step * .8f).coerceIn(3.dp, 8.dp)
            val lift = with(density) { 18.dp.toPx() }
            val measurer = rememberTextMeasurer()
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
                val text = "$old, your oldest"
                // its real width, at any text size; it ends at the oldest dot, so it stays left of the typical year's line
                val wide = with(density) { measurer.measure(text, small).size.width.toDp() }
                val left = (xOf(old) + 4.dp - wide).coerceAtLeast(0.dp)
                // lifted above every stack under it (a dot is 4 dp either side of its x), with 8 dp of air over the top dot
                val under = dots.filter { xOf(it.first) in left - 4.dp..left + wide + 4.dp }.maxOfOrNull { it.second } ?: 0
                val y = axis - 12.dp - step * under - 4.dp - 8.dp - labelH
                // while waiting, a timeline squeezed by large text has no room for it above the dots
                if (typical == null && y < 0.dp) return@let
                // fades in where it stays: rising from below would pass over the dots
                Text(
                    text, Modifier.offset(y = if (typical == null) y else y.coerceAtLeast(22.dp)).anchorAt(xOf(old) + 4.dp, 1f)
                        .graphicsLayer { alpha = stepAt(t.value, 1800f, 600f) },
                    soft(), maxLines = 1, style = small,
                )
            }
        }
        if (typical != null) {
            // the age and its line come in together once the dots and the typical year are in; the number is hidden till then
            // rather than sitting at 0, and counts up quickly. A plain fade, so the line doesn't pop in on the fast curve.
            val shown = count((a.age!! * stepAt(t.value, AGE_AT, 900f)).roundToInt())
            val fade = { ((t.value - AGE_AT) / 500f).coerceIn(0f, 1f) }
            val line = "Your listening age." + (topDecade(a.years)?.let { " Most of your songs are from the ${decadeName(it)}." } ?: "")
            Row(Modifier.padding(top = 6.dp), Arrangement.spacedBy(10.dp)) {
                Text(
                    shown, Modifier.alignByBaseline().graphicsLayer { alpha = fade() }, accent,
                    style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Black, fontSize = 112.sp, lineHeight = 96.sp, letterSpacing = (-5.6).sp, fontFeatureSettings = TABULAR),
                )
                // balanced lines, so the last word never sits alone on its own line
                Text(
                    line, Modifier.alignByBaseline().graphicsLayer { val p = fade(); alpha = p; translationY = (1 - p) * 12.dp.toPx() }, soft(),
                    style = Sub.copy(lineBreak = LineBreak.Paragraph),
                )
            }
        } else {
            // the progress takes the age's place, with a quiet bar under it
            val done = checked.toFloat() / (checked + unchecked).coerceAtLeast(1)
            Text(
                "Checking song years: ${count(checked)} of ${count(checked + unchecked)}", Modifier.padding(top = 14.dp),
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-.4).sp, fontFeatureSettings = TABULAR),
            )
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(4.dp).testTag("years_progress").drawBehind {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(faint, cornerRadius = r)
                    drawRoundRect(accent, size = Size(size.width * done, size.height), cornerRadius = r)
                },
            )
            Text("Needs 20 songs with a year.", Modifier.padding(top = 12.dp), soft(), style = Sub)
            if (centred) Spacer(Modifier.weight(.8f))
        }
        Text("Song years from MusicBrainz", Modifier.padding(top = 8.dp), soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp))
    } }
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
    val calm = still
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
    val t = cardClock(calm, 500 + gap * ticks.size + 550)
    // starts once its line has risen in
    val times = countUp(day.plays, calm, 450)
    CardColumn {
        Text(
            buildAnnotatedString { append("On ${dayText(day.date, s.start.year != s.period.to.year)}, "); withStyle(SpanStyle(color = accent)) { append("something happened.") } },
            Modifier.rise(0, calm), style = Headline,
        )
        Text(
            buildAnnotatedString {
                append("You played “${shortTitle(day.song.title)}” ")
                countSpan(times, SpanStyle(color = ink, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR), "${times.text} ${if (day.plays == 1) "time" else "times"}")
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
    val calm = still
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
    val calm = still
    key(s.period, calm) { SunPortrait(s, calm) }
}

@Composable
private fun SunPortrait(s: SpotifriedStats, calm: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val scale = sunScale(minutesOf(s.seconds))
    val motion = rememberSunMotion(s.hours, scale, calm)
    val t = cardClock(calm, 2300f)
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
            ) { Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false, dark = true) }
            // a clock face, each label shown once the sweep reaches it; side labels are kept 8 dp inside the screen whatever
            // their width, so large text never runs off it
            val r = side * .44f
            // top and bottom ones clear the ray tips by 8 dp where the card has the room, so they never touch them
            val half = with(LocalDensity.current) { label.lineHeight.toDp() } / 2
            val room = maxHeight / 2 - half
            val up = minOf(maxOf(r, side * sunReach(s.hours, scale) + 8.dp + half), room)
            val down = minOf(maxOf(r, side * sunReach(s.hours, scale, up = false) + 8.dp + half), room)
            listOf(0 to (0.dp to -up), 6 to (r to 0.dp), 12 to (0.dp to down), 18 to (-r to 0.dp)).forEach { (h, at) ->
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

/** The line above the type title: "You are", or "Ali, you are" with the listener's [name]. */
internal fun typeLead(name: String) = if (name.isEmpty()) "You are" else "$name, you are"

/** Card 8: who the person is as a listener, three true facts one after another, and Share ([onShare] null: no button, as in the shared picture). */
@Composable
internal fun TypeCard(s: SpotifriedStats, onShare: (() -> Unit)?, still: Boolean = false, working: Boolean = false, message: String? = null, name: String = "") {
    val calm = still
    key(s.period, calm) { Type(s, onShare, calm, working, message, name) }
}

@Composable
private fun Type(s: SpotifriedStats, onShare: (() -> Unit)?, calm: Boolean, working: Boolean, message: String?, name: String) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val title = typeTitle(s) + "."
    val lead = typeLead(name)
    val facts = remember(s) { typeFacts(s, listenerType(s)) }
    val scale = sunScale(minutesOf(s.seconds))
    val motion = rememberSunMotion(s.hours, scale, calm)
    val up = progress(calm, 0, 1400)
    val button = progress(calm, 1900, 800)
    // tappable only once it has appeared: before that a tap on it goes back a card, as anywhere on the left
    val ready by remember { derivedStateOf { button.value >= 1f } }
    var sharedAt by remember { mutableLongStateOf(-1_000L) }
    // where the facts end, from the card's top: the sun is lowered when its rays would come closer to them than 24 dp
    var top by remember { mutableFloatStateOf(0f) }
    var factsEnd by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { top = it.boundsInRoot().top }) {
        // the person's own sun, big and dim behind everything, its centre 60 dp above the bottom edge, as in the mockup
        val side = maxWidth * 1.72f
        val height = maxHeight
        val reach = remember(s.hours, scale) { sunReach(s.hours, scale) }
        Box(Modifier.fillMaxSize().fadeOutBottom()) {
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = side / 2 - 60.dp).requiredSize(side)
                    .graphicsLayer {
                        alpha = .55f * up.value
                        val rays = height.toPx() - 60.dp.toPx() - side.toPx() * reach
                        translationY = (factsEnd - top + 24.dp.toPx() - rays).coerceAtLeast(0f)
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .34f), Color.Transparent), radius = size.width * .36f), size.width * .36f) }
                Sun(s.hours, scale, live = false, motion = motion, modifier = Modifier.fillMaxSize(), labels = false, dark = true)
            }
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
                val fit = remember(title, lead, facts, measurer, constraints) {
                    fun height(k: Float): Int = with(density) {
                        measurer.measure(lead, Sub, maxLines = 3, constraints = Constraints(maxWidth = constraints.maxWidth)).size.height + 6.dp.roundToPx() +
                            measurer.measure(title, titleStyle(k), maxLines = 3, constraints = Constraints(maxWidth = constraints.maxWidth)).size.height + 26.dp.roundToPx() +
                            facts.sumOf { measurer.measure(it, factStyle(k), constraints = Constraints(maxWidth = factWidth)).size.height } + 2 * 12.dp.roundToPx() +
                            RISE.roundToPx()
                    }
                    generateSequence(1f) { it - .05f }.takeWhile { it >= .5f }.firstOrNull { height(it) <= constraints.maxHeight } ?: .5f
                }
                // the scroll clips to its content, so room under the last fact for its rise: it is never cut while it comes in
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = RISE)) {
                    // the title comes in with the line above it, so the card is never only a faint "you are"
                    Text(lead, Modifier.rise(0, calm), soft(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = Sub)
                    val cut = title.lastIndexOf(' ') + 1
                    Text(
                        buildAnnotatedString { append(title.take(cut)); withStyle(SpanStyle(color = accent)) { append(title.drop(cut)) } },
                        Modifier.padding(top = 6.dp).rise(0, calm), maxLines = 3, style = titleStyle(fit),
                    )
                    Column(Modifier.padding(top = 26.dp).widthIn(max = 280.dp).onGloballyPositioned { factsEnd = it.boundsInRoot().bottom }, Arrangement.spacedBy(12.dp)) {
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
            val backdrop = MaterialTheme.colorScheme.background
            if (onShare != null) Button(
                // a second click within a second does nothing
                { val now = System.currentTimeMillis(); if (now - sharedAt >= 1000) { sharedAt = now; onShare() } },
                Modifier.padding(top = 12.dp).heightIn(min = 48.dp).graphicsLayer { alpha = button.value; translationY = (1 - button.value) * lift }
                    // a soft dark pool under the button: the rays fade out round it and stay whole everywhere else. It is drawn over
                    // what comes before the button, so it reaches no higher than the 12 dp gap and never dims the facts.
                    .drawBehind {
                        val r = size.width * .5f + 40.dp.toPx()
                        scale(1f, (size.height / 2 + 12.dp.toPx()) / r, center) {
                            drawCircle(Brush.radialGradient(0f to backdrop, .55f to backdrop.copy(alpha = .85f), 1f to Color.Transparent, center = center, radius = r), r, center)
                        }
                    }
                    .then(if (ready) Modifier else Modifier.clearAndSetSemantics {}),
                enabled = ready && !working,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = accent, disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp),
            ) {
                Text(if (working) "Saving the pictures…" else "Save your story", style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp))
            }
            if (message != null) Text(message, Modifier.padding(top = 8.dp), ink, style = Sub.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
    }
}
