package com.spotifry.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.LyricWord
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.abs

// The phone's lyrics motion (see LyricsMotion.kt for the numbers), drawn the same way: the sung line grows and sharpens,
// lines further away dim and blur, the page moves in a loose chain, and the word being sung is lit by a soft edge.

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
 * The song's position, counted forward every frame between the player's twice-a-second updates, so words light up on
 * time instead of in pairs. Runs frames only while playing; paused, it only checks for a seek a few times a second.
 */
@Composable
private fun smoothPosition(position: () -> Long, playing: Boolean): State<Long> {
    val smooth = remember { mutableLongStateOf(position()) }
    val pos = rememberUpdatedState(position)
    LaunchedEffect(playing) {
        var raw = pos.value(); var at = System.nanoTime()
        while (true) {
            if (playing) withFrameNanos { } else delay(100)
            val now = System.nanoTime(); val p = pos.value()
            if (p != raw) { raw = p; at = now }
            // never more than 0.6 s past the last real update, in case the player stalls
            val next = if (playing) raw + ((now - at) / 1_000_000).coerceAtMost(600) else raw
            // a small correction from a new update never makes the light step back
            smooth.longValue = if (playing && next < smooth.longValue && smooth.longValue - next < 300) smooth.longValue else next
        }
    }
    return smooth
}

/**
 * The line drawn from the song's position: the words already sung lit, the word being sung lit by a soft edge that
 * sweeps through its letters (and raised a little), the rest dim. A line without word times lights as a whole.
 */
private fun DrawScope.drawLyric(layout: TextLayoutResult, line: LyricLine, rects: List<Rect>, whole: List<IntRange>, p: Long, d: Float, ink: Color) {
    val unlit = ink.copy(alpha = unlitAlpha(d)); val lit = ink.copy(alpha = litAlpha(d))
    val ws = line.words
    if (rects.isEmpty()) { drawText(layout, color = lerp(unlit, lit, lineLight(p, line))); return }
    if (p < ws.first().startMs) { drawText(layout, color = unlit); return }
    if (p >= ws.last().endMs + SETTLE_MS && p >= ws.last().startMs + SETTLE_MS) { drawText(layout, color = lit); return }
    val covered = Path(); val done = Path()
    ws.forEachIndexed { k, w ->
        val f = sweepFraction(p, w); val r = wholeRise(p, ws, whole[k])
        if (f >= 1f && r <= 0f) { done.addRect(rects[k]); covered.addRect(rects[k]) } else if (f > 0f || r > 0f) covered.addRect(rects[k])
    }
    clipPath(covered, ClipOp.Difference) { drawText(layout, color = unlit) }
    if (!done.isEmpty) clipPath(done) { drawText(layout, color = lit) }
    val edge = 8.dp.toPx(); val rise = 2.5.dp.toPx()
    ws.forEachIndexed { k, w ->
        val f = sweepFraction(p, w); val r = wholeRise(p, ws, whole[k])
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
private fun LyricRow(i: Int, line: LyricLine, focus: State<Float>, pos: State<Long>, bus: ShiftBus, style: TextStyle, ink: Color, reading: () -> Boolean, onClick: () -> Unit) {
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
                val s = focusScale(d); scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, .5f)
                renderEffect = if (!reading()) blurOf(blurDp(d) * density) else null
            }
            .clickable(onClick = onClick),
    ) {
        // the text is laid out here, but drawn by hand
        Text(
            line.text.ifBlank { "♪" },
            Modifier.fillMaxWidth(.9f).drawWithContent {
                boxes.place(line, offsets)
                val l = boxes.layout ?: return@drawWithContent
                val d = i - focus.value
                // lines well away from the focus are plainly unsung or sung: they don't watch the clock, so they don't redraw every frame
                val p = if (abs(d) < 2f) pos.value else if (d > 0) Long.MIN_VALUE / 2 else Long.MAX_VALUE / 2
                // while the reader scrolls by hand nothing is blurred or dimmed beyond a line away
                drawLyric(l, line, boxes.rects, whole, p, if (reading()) d.coerceIn(-1f, 1f) else d, ink)
            },
            style = style, onTextLayout = { boxes.set(it, line, offsets) },
        )
    }
}

/**
 * The timed lyrics, moving with the song: the page follows the sung line in a loose chain, and reading by hand
 * (the mouse wheel) pauses that for a few seconds. [restAt] is how far down the sung line rests (0 to 1 of the height).
 */
@Composable
internal fun AnimatedLyrics(services: Services, lyrics: Lyrics.Timed, shiftMs: Int, big: Boolean, restAt: Float, bottomPad: androidx.compose.ui.unit.Dp) {
    val lines = lyrics.lines
    val playing by remember { services.player.state }.collectAsState()
    // read inside the clock only, so the twice-a-second position never recomposes the page
    val position = services.player.position.collectAsState()
    // a positive shift runs the lyrics earlier: they are looked up that much ahead of the song
    val smooth = smoothPosition({ position.value + shiftMs }, playing.isPlaying)
    val starts = remember(lyrics) { LongArray(lines.size) { lines[it].atMs } }
    val focus = remember(starts) { derivedStateOf { focusPosition(starts, smooth.value) } }
    val now by remember(lyrics) { derivedStateOf { lines.indexAt(smooth.value) } }

    val list = rememberLazyListState()
    val bus = remember { ShiftBus() }
    val follow = remember(lyrics) { Follow() }
    var manual by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // the mouse wheel or a drag on the list is the reader taking over; the page waits a few seconds after the last one
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastTouch) {
        if (lastTouch == 0L) return@LaunchedEffect
        manual = true
        delay(3_000)
        snapshotFlow { list.isScrollInProgress }.first { !it }
        manual = false
    }
    val tgt = now.coerceAtLeast(0)
    LaunchedEffect(tgt, manual, height, lyrics) {
        if (manual) { follow.away = true; return@LaunchedEffect }
        if (height == 0 || lines.isEmpty()) return@LaunchedEffect
        fun item() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == tgt }
        val from = follow.last
        val returning = follow.away; follow.away = false
        val cur = item()
        if (!returning && cur != null && chains(from, tgt)) {
            delay(SCROLL_DELAY_MS)
            val at = item()
            if (at != null) {
                val px = list.scrollBy(at.offset.toFloat())
                bus.event = Shift(bus.event.id + 1, from, tgt, px, false)
            }
        } else if (returning && cur != null) {
            list.animateScrollBy(cur.offset.toFloat(), tween(500, easing = TravelEase))
        } else {
            // the first time, a seek or a skip far away: land there, no chain
            list.scrollToItem(tgt)
            item()?.let { list.scrollBy(it.offset.toFloat()) }
            bus.event = Shift(bus.event.id + 1, from, tgt, 0f, true)
        }
        follow.last = tgt
    }
    val ink = Palette.ink
    val style = TextStyle(fontFamily = Display, fontSize = if (big) 32.sp else 22.sp, lineHeight = if (big) 40.sp else 29.sp)
    LazyColumn(
        Modifier.fillMaxSize().onSizeChanged { height = it.height }.pointerInput(Unit) {
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); if (e.type == PointerEventType.Scroll || e.type == PointerEventType.Press) lastTouch = System.nanoTime() } }
        },
        state = list,
        contentPadding = PaddingValues(top = with(density) { (height * restAt).toDp() }, bottom = bottomPad.coerceAtLeast(with(density) { (height * .7f).toDp() })),
        verticalArrangement = Arrangement.spacedBy(if (big) 18.dp else 10.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            LyricRow(i, line, focus, smooth, bus, style, ink, { manual }) { manual = false; services.player.seekTo((line.atMs - shiftMs).coerceAtLeast(0)) }
        }
    }
}
