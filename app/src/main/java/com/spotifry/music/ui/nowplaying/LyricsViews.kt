package com.spotifry.music.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.SHIFT_STEP_MS
import com.spotifry.music.data.nudgeShift
import com.spotifry.music.data.shiftLabel
import com.spotifry.music.data.LyricWord
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons

/**
 * The song's position, counted forward every frame between the player's twice-a-second updates, so words light up
 * on time instead of in pairs. Counts at the playback [speed]. Runs frames only while playing and on screen; paused,
 * it only checks for a seek a few times a second.
 */
@Composable
private fun smoothPosition(position: () -> Long, playing: Boolean, speed: Float = 1f): androidx.compose.runtime.State<Long> {
    val smooth = remember { androidx.compose.runtime.mutableLongStateOf(position()) }
    // the newest lambda: a trim change shifts where lyrics count from
    val pos = androidx.compose.runtime.rememberUpdatedState(position)
    val rate = androidx.compose.runtime.rememberUpdatedState(speed)
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, life) {
        life.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var raw = pos.value(); var at = System.nanoTime()
            while (true) {
                if (playing) androidx.compose.runtime.withFrameNanos { } else kotlinx.coroutines.delay(100)
                val now = System.nanoTime(); val p = pos.value()
                if (p != raw) { raw = p; at = now }
                // never more than 0.6 s past the last real update, in case the player stalls
                val next = if (playing) raw + ((now - at) / 1_000_000 * rate.value).toLong().coerceAtMost(600) else raw
                // a small correction from a new update never makes the light step back
                smooth.longValue = if (playing && next < smooth.longValue && smooth.longValue - next < 300) smooth.longValue else next
            }
        }
    }
    return smooth
}

/** Eases in and out, so the page moves softly and is fastest half-way. */
private val TravelEase = CubicBezierEasing(.42f, 0f, .58f, 1f)

/** One move of the page: every line starts [px] away from its place and travels home, the leading ones first. */
private class Shift(val id: Int, val prev: Int, val now: Int, val px: Float, val snap: Boolean)
private class ShiftBus { var event by mutableStateOf(Shift(0, -1, -1, 0f, true)) }
/** [last]: the line the page last followed; [away]: the reader scrolled off by hand since. */
private class Follow { var last = -1; var away = false }

/** Where each word sits in the laid-out line; empty when the sweep can't be placed (no word times, right-to-left). */
private class Boxes {
    var layout: TextLayoutResult? = null
    var rects: List<Rect> = emptyList()
    private var words: List<LyricWord>? = null

    /** The same layout read again for a changed line (lyrics upgraded to word times): the rects follow the line. */
    fun place(line: LyricLine, offs: IntArray?) { if (words !== line.words) { words = line.words; layout?.let { set(it, line, offs) } } }

    fun set(l: TextLayoutResult, line: LyricLine, offsets: IntArray?) {
        layout = l
        words = line.words
        rects = if (offsets == null || l.getParagraphDirection(0) == ResolvedTextDirection.Rtl) emptyList() else List(line.words.size) { k ->
            val s = offsets[k]; val e = offsets[k + 1]
            if (e <= s) Rect.Zero else {
                val a = l.getBoundingBox(s); val row = l.getLineForOffset(s)
                val right = if (l.getLineForOffset(e - 1) == row) maxOf(l.getBoundingBox(e - 1).right, a.left) else l.getLineRight(row)
                Rect(a.left, l.getLineTop(row), right, l.getLineBottom(row))
            }
        }
    }
}

private val blurCache = HashMap<Int, RenderEffect>()
/** A blur of [px], in half-pixel steps so the same few effects are reused every frame; null for none. */
private fun blurOf(px: Float): RenderEffect? {
    val step = (px * 2).toInt()
    if (step < 1) return null
    return blurCache.getOrPut(step) { BlurEffect(step / 2f, step / 2f, TileMode.Decal) }
}

private fun wholeRise(p: Long, ws: List<LyricWord>, range: IntRange) = riseFraction(p, LyricWord(ws[range.first].startMs, ws[range.last].endMs, ""))

/**
 * The line drawn from the song's position: the words already sung lit, the word being sung lit by a soft edge that
 * sweeps through its letters (and raised a little), the rest dim. A line without word times lights as a whole.
 */
private fun DrawScope.drawLyric(layout: TextLayoutResult, line: LyricLine, rects: List<Rect>, whole: List<IntRange>, p: Long, d: Float, calm: Boolean, ink: Color) {
    val unlit = ink.copy(alpha = unlitAlpha(d)); val lit = ink.copy(alpha = litAlpha(d))
    val ws = line.words
    if (rects.isEmpty()) {
        val t = if (calm) (if (p >= line.atMs) 1f else 0f) else lineLight(p, line)
        drawText(layout, color = lerp(unlit, lit, t)); return
    }
    if (p < ws.first().startMs) { drawText(layout, color = unlit); return }
    if (p >= ws.last().endMs + SETTLE_MS && p >= ws.last().startMs + SETTLE_MS) { drawText(layout, color = lit); return }
    val covered = Path(); val done = Path()
    ws.forEachIndexed { k, w ->
        val f = if (calm) (if (p >= w.startMs) 1f else 0f) else sweepFraction(p, w)
        val r = if (calm) 0f else wholeRise(p, ws, whole[k])
        if (f >= 1f && r <= 0f) { done.addRect(rects[k]); covered.addRect(rects[k]) } else if (f > 0f || r > 0f) covered.addRect(rects[k])
    }
    clipPath(covered, ClipOp.Difference) { drawText(layout, color = unlit) }
    if (!done.isEmpty) clipPath(done) { drawText(layout, color = lit) }
    val edge = 8.dp.toPx(); val rise = 2.5.dp.toPx()
    ws.forEachIndexed { k, w ->
        val f = if (calm) (if (p >= w.startMs) 1f else 0f) else sweepFraction(p, w)
        val r = if (calm) 0f else wholeRise(p, ws, whole[k])
        if ((f >= 1f && r <= 0f) || (f <= 0f && r <= 0f)) return@forEachIndexed
        val box = rects[k]
        val x = box.left - edge + f * (box.width + 2 * edge)
        clipRect(box.left, box.top - rise, box.right, box.bottom) {
            translate(top = -rise * r) {
                drawText(layout, color = unlit)
                drawText(layout, brush = Brush.horizontalGradient(listOf(lit, Color.Transparent), startX = x - edge, endX = x + edge))
            }
        }
    }
}

/**
 * One timed line. Its size, brightness and blur follow how far it is from the page's focus ([focus], in lines), the
 * lit part follows the song's position ([pos]); both are read only when drawing, so nothing recomposes per frame.
 * A page move ([bus]) starts every line [Shift.px] off its place and lets it travel home after its turn in the chain.
 */
@Composable
private fun LyricRow(i: Int, line: LyricLine, focus: State<Float>, pos: State<Long>, bus: ShiftBus, calm: Boolean, blurOn: Boolean, ink: Color, reading: () -> Boolean, onClick: () -> Unit) {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(bus) {
        // a line that scrolls into view mid-move doesn't take part (the first value is dropped)
        snapshotFlow { bus.event }.drop(1).collectLatest { ev ->
            if (ev.snap) offset.snapTo(0f) else {
                offset.snapTo(offset.value + ev.px)
                delay(chainDelayMs(i, ev.prev, ev.now))
                offset.animateTo(0f, tween(TRAVEL_MS, easing = TravelEase))
            }
        }
    }
    val offsets = remember(line) { wordOffsets(line) }
    val boxes = remember(line.text) { Boxes() }
    val whole = remember(line) { wholeWords(line.words) }
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                val d = i - focus.value
                translationY = offset.value
                if (!calm) { val s = focusScale(d); scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, .5f) }
                renderEffect = if (blurOn && !reading()) blurOf(blurDp(d) * density) else null
            }
            .clickable(onClickLabel = "Play from here", onClick = onClick),
    ) {
        // the text is laid out (and read out by a screen reader) here, but drawn by hand below
        Text(
            line.text,
            Modifier.fillMaxWidth(.9f).drawWithContent {
                boxes.place(line, offsets)
                val l = boxes.layout ?: return@drawWithContent
                val d = if (calm) i - Math.round(focus.value).toFloat() else i - focus.value
                // lines well away from the focus are plainly unsung or sung: they don't watch the clock, so they don't redraw every frame
                val p = if (abs(d) < 2f) pos.value else if (d > 0) Long.MIN_VALUE / 2 else Long.MAX_VALUE / 2
                // while the reader scrolls by hand nothing is blurred or dimmed beyond a line away
                drawLyric(l, line, boxes.rects, whole, p, if (reading()) d.coerceIn(-1f, 1f) else d, calm, ink)
            },
            style = BigLine, onTextLayout = { boxes.set(it, line, offsets) },
        )
    }
}

/**
 * Lyrics mode's lines, scrolling with the song. Tapping a timed line jumps there. The page follows the sung line in a
 * loose chain (see [Shift]); reading by hand pauses that for a few seconds, and [onReading] says when it starts and stops.
 */
@Composable
internal fun LyricsLines(
    state: PlayerUiState, position: () -> Long, lyrics: Lyrics, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
    speed: Float = 1f,
    /** Milliseconds the lyrics run earlier (negative: later). */
    shift: Int = 0, onReading: (Boolean) -> Unit = {},
) {
    val timed = lyrics as? Lyrics.Timed
    val lines = timed?.lines.orEmpty()
    // lyric times count from the whole song's start, the player from the trim's
    val trimStart = state.trim?.startMs ?: 0L
    val sung = remember(trimStart, shift) { { position() + trimStart + shift } }
    val smooth = smoothPosition(sung, state.isPlaying && timed != null, speed)
    val starts = remember(timed) { LongArray(lines.size) { lines[it].atMs } }
    val focus = remember(starts) { derivedStateOf { focusPosition(starts, smooth.value) } }
    val now by remember(timed) { derivedStateOf { lines.indexAt(smooth.value) } }
    // animations off in the system settings: no chain, growth, blur or sweep; lines just light when sung
    val context = LocalContext.current
    val calm = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // blur makes big text harder to read
    val blurOn = !calm && LocalDensity.current.fontScale < 1.3f
    val ink = androidx.compose.material3.LocalContentColor.current

    val list = rememberLazyListState()
    val bus = remember { ShiftBus() }
    val follow = remember(timed) { Follow() }
    var manual by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // the sung line rests about a quarter of the way down; the padding lets the first and last lines get there too
    val rest = height * .27f

    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) manual = true
        else if (manual) {
            snapshotFlow { list.isScrollInProgress }.first { !it }
            delay(3_000)
            manual = false
        }
    }
    LaunchedEffect(manual) { onReading(manual) }
    val tgt = now.coerceAtLeast(0)
    LaunchedEffect(tgt, manual, height, timed) {
        if (manual) { follow.away = true; return@LaunchedEffect }
        if (height == 0 || lines.isEmpty()) return@LaunchedEffect
        fun item() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == tgt }
        val from = follow.last
        val returning = follow.away; follow.away = false
        val cur = item()
        if (!calm && !returning && cur != null && chains(from, tgt)) {
            delay(SCROLL_DELAY_MS)
            val at = item()
            if (at != null) {
                val px = list.scrollBy(at.offset.toFloat())
                bus.event = Shift(bus.event.id + 1, from, tgt, px, false)
            }
        } else if (!calm && returning && cur != null) {
            list.animateScrollBy(cur.offset.toFloat(), tween(500, easing = TravelEase))
        } else {
            // the first time, a seek or a skip far away: land there, no chain
            list.scrollToItem(tgt)
            item()?.let { list.scrollBy(it.offset.toFloat()) }
            bus.event = Shift(bus.event.id + 1, from, tgt, 0f, true)
        }
        follow.last = tgt
    }
    LazyColumn(
        modifier.fillMaxSize().onSizeChanged { height = it.height }, state = list,
        contentPadding = if (timed != null) PaddingValues(start = 24.dp, end = 24.dp, top = with(density) { rest.toDp() }, bottom = with(density) { (height * .7f).toDp() }) else PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (timed != null) itemsIndexed(timed.lines) { i, line ->
            LyricRow(i, line, focus, smooth, bus, calm, blurOn, ink, { manual }) {
                manual = false
                onSeek((line.atMs - trimStart - shift).coerceAtLeast(0))
            }
        }
        else itemsIndexed((lyrics as Lyrics.Plain).lines) { _, line -> Text(line, style = BigLine) }
    }
}

/** Over the progress bar in lyrics mode: where the lyrics come from, and moving them a tenth of a second a tap, kept for this song. */
@Composable
internal fun TimingRow(lyrics: Lyrics, shift: Int, onShift: (Int) -> Unit, modifier: Modifier = Modifier) {
    val timed = lyrics as? Lyrics.Timed
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (timed == null) "Lyrics from LRCLIB · not timed" else "Lyrics from ${timed.from}", Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (timed != null) {
            TimingButton("−", "Lyrics later") { onShift(nudgeShift(shift, -SHIFT_STEP_MS)) }
            Text(
                shiftLabel(shift), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = if (shift == 0) Faint else androidx.compose.material3.LocalContentColor.current,
            )
            TimingButton("+", "Lyrics earlier") { onShift(nudgeShift(shift, SHIFT_STEP_MS)) }
            if (shift != 0) TimingButton("Reset", "Lyrics back on their own timing") { onShift(0) }
        }
    }
}

@Composable
private fun TimingButton(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(48.dp, 48.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 12.dp),
        Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium.copy(fontSize = if (text.length == 1) 20.sp else 14.sp, fontWeight = FontWeight.SemiBold), color = Faint) }
}

private val BigLine @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
