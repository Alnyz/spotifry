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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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

/** "You gave 2026", "You gave the last 12 months", "You gave music" (all time). */
private fun gaveLead(p: WrappedPeriod): String = when {
    p.label.length == 4 && p.label.all { it.isDigit() } -> "You gave ${p.label}"
    p.label == "All time" -> "You gave music"
    else -> "You gave the ${p.label.replaceFirstChar { it.lowercase() }}"
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

/** 0 to 1, after [delayMs], on [Ease]; already 1 when [calm]. Restarts each time the card is shown. */
@Composable
private fun progress(calm: Boolean, delayMs: Int, durationMs: Int): Animatable<Float, *> {
    val p = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(p) { p.animateTo(1f, tween(durationMs, delayMs, Ease)) }
    return p
}

@Composable
private fun countUp(to: Int, calm: Boolean): Int = (to * progress(calm, 500, 1500).value).roundToInt()

/** Fades in and lifts into place; [order] staggers the lines of a card one after another. */
@Composable
private fun Modifier.rise(order: Int, calm: Boolean): Modifier {
    val p = progress(calm, order * 180, 900)
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    return graphicsLayer { alpha = p.value; translationY = (1 - p.value) * lift }
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
    val accent = MaterialTheme.colorScheme.primary
    val scale = sunScale(minutesOf(s.seconds))
    val motion = remember {
        SunMotion().apply {
            if (calm) { val peak = (s.hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat(); s.hours.forEachIndexed { i, v -> now[i] = v / peak }; this.scale = scale }
        }
    }
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
    val accent = MaterialTheme.colorScheme.primary
    val plays = countUp(top.plays, calm)
    val ring = if (calm) null else rememberInfiniteTransition("rings").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), "ring")
    val shape = RoundedCornerShape(18.dp)
    CardColumn {
        Column(Modifier.weight(1f)) {
            Text("You couldn't leave this one alone.", Modifier.rise(0, calm), style = Headline)
            Box(
                Modifier.padding(top = 26.dp).weight(1f, fill = false).widthIn(max = 230.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .rise(1, calm).shadow(28.dp, shape, ambientColor = accent, spotColor = accent),
            ) {
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
                top.song.title, Modifier.padding(top = 22.dp).rise(2, calm), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Albert, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = (-.65).sp),
            )
            Text(cleanArtist(top.song.artist), Modifier.padding(top = 4.dp).rise(2, calm), soft(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = Sub.copy(fontSize = 14.sp))
        }
        Row(Modifier.rise(3, calm), Arrangement.spacedBy(8.dp)) {
            Text(
                "$plays", Modifier.alignByBaseline(), accent,
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
            buildAnnotatedString { append(gaveLead(s.period)); append(" "); withStyle(SpanStyle(color = accent, fontFeatureSettings = TABULAR)) { append("$shown $unit") } },
            Modifier.rise(0, calm), style = Headline,
        )
        if (s.seconds >= 86_400) {
            Text("That is ${longDuration(minutesOf(s.seconds))}, with no break.", Modifier.padding(top = 14.dp).rise(1, calm), soft(), style = Sub)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomCenter) {
            // about a mockup's worth of bars on a phone; a year of days on a narrow screen would be hairlines
            val bars = remember(s.daySeconds, maxWidth) { barHeights(s.daySeconds, (maxWidth / 2.5.dp).toInt().coerceAtLeast(1)) }
            val lit = longest?.let { barOf(it, days, bars.size) }
            Canvas(Modifier.fillMaxWidth().height(minOf(maxHeight, 270.dp))) {
                if (bars.isEmpty()) return@Canvas
                val slot = size.width / bars.size
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
        }
        if (days > 0) {
            val fmt = DateTimeFormatter.ofPattern(
                when { s.start.year != s.period.to.year -> "MMM yyyy"; days > 62 -> "MMM"; else -> "d MMM" }, english,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween) {
                listOf(0, days / 2, days - 1).forEach { Text(s.start.plusDays(it.toLong()).format(fmt), color = soft(), style = Sub.copy(fontSize = 11.sp, lineHeight = 14.sp)) }
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
