package com.spotifry.desktop

import com.spotifry.music.data.SHIFT_STEP_MS
import com.spotifry.music.data.nudgeShift
import com.spotifry.music.data.shiftLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.basicMarquee
import com.spotifry.music.data.source.isLocal
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.indexAt
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.RepeatMode
import kotlinx.coroutines.launch

internal sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Failed : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

/**
 * The big view of the song playing, after Ali's mockup: the cover, the name large, Save
 * and More, and the lyrics in a glass card under it. The song's own cover, blurred, lies behind ([NowPlayingBackdrop]);
 * the queue is in the panel on the right. No seek bar here: the one in the bar below is enough.
 */
@Composable
fun NowPlayingPage(services: Services, state: com.spotifry.music.player.PlayerUiState, modifier: Modifier = Modifier) {
    val lib = services.library
    val song = state.current
    val scope = rememberCoroutineScope()
    val savedIds by lib.db.songs().observeSaved().collectAsState(emptyList())
    val saved = song != null && savedIds.any { it.id == song.id }
    val shift by lib.settings.lyricsShift.collectAsState()

    if (song == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Nothing playing", fontFamily = Heading, fontSize = 40.sp, color = Palette.ink)
            Text("Pick a song and it shows up here.", color = Palette.ink2, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    // View full lyrics swaps the page for the lyrics alone; Now playing again brings the page back
    var full by remember(song.id) { mutableStateOf(startFullLyrics) }
    androidx.compose.animation.Crossfade(full, modifier, animationSpec = androidx.compose.animation.core.tween(300)) { showFull ->
    if (showFull) FullLyrics(services, song, shift[song.id] ?: 0, { full = false }, Modifier.fillMaxSize())
    else BoxWithConstraints(Modifier.fillMaxSize()) {
        // the cover gives way on a short window so the lyrics keep some room
        val cover = (maxHeight - 380.dp).coerceIn(170.dp, 300.dp)
        Column(Modifier.fillMaxSize().padding(top = 24.dp, bottom = LocalBottomInset.current + 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(
                    song.thumbnailUrl ?: song.localThumbPath, cover, RoundedCornerShape(14.dp), large = true,
                    Modifier.shadow(28.dp, RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                )
                Column(Modifier.padding(start = 40.dp).weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).frosted(0.35f).border(1.dp, Palette.ink.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Wave, null, Modifier.size(15.dp), tint = Palette.accent)
                        Text(state.from?.let { "Playing from $it" } ?: "Now playing", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink, maxLines = 1)
                    }
                    Text(
                        song.title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = if (song.title.length <= 18) 64.sp else 52.sp, letterSpacing = (-1.5).sp,
                        color = Palette.ink, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(top = 14.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(song.artist, color = Palette.ink.copy(alpha = 0.85f), fontSize = 22.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        state.quality?.let { QualityPill(it) }
                    }
                    SongNote(services, song.id)
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!song.isLocal) Pill(if (saved) "Saved" else "Save", if (saved) Icons.Check else Icons.Download, glass = true) {
                            scope.launch { if (saved) lib.downloads.remove(song.id) else lib.downloads.save(song) }
                        }
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            Pill("More", Icons.More, glass = true) { menu = true }
                            if (menu) SongMenu(services, song, { menu = false }) {}
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 30.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Palette.accent))
                Text("Lyrics", fontFamily = Heading, fontSize = 22.sp, color = Palette.ink, modifier = Modifier.padding(start = 12.dp))
            }
            val card = RoundedCornerShape(18.dp)
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).clip(card).frosted(0.4f).border(1.dp, Palette.ink.copy(alpha = 0.1f), card)) {
                Waves(Modifier.align(Alignment.BottomEnd).fillMaxWidth().fillMaxHeight(0.45f).heightIn(max = 150.dp))
                LyricsPanel(services, song, shift[song.id] ?: 0, Modifier.fillMaxSize().padding(start = 28.dp, end = 180.dp, top = 20.dp, bottom = 20.dp))
                Row(
                    Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(8.dp)).clickable { full = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("View full lyrics", fontSize = 13.sp, color = Palette.ink2)
                    Chevron(left = false)
                }
            }
        }
    }
    }
}

/**
 * The lyrics card's lower edge: a warm glow in the corner and three slow overlapping waves, each a soft fill with a thin
 * bright crest, running from a cooler neighbour of the cover's colour into the cover's colour itself.
 */
@Composable
private fun Waves(modifier: Modifier) {
    val tint by androidx.compose.animation.animateColorAsState(Palette.glow ?: Palette.accent, androidx.compose.animation.core.tween(600))
    val cool = remember(tint) { shiftHue(tint, -55f) }
    // drawn on its own layer and faded in from the top and the left, so nothing ends in a hard line and the lyrics on
    // the left stay clear
    androidx.compose.foundation.Canvas(modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w, h), radius = h * 2f))
        // one hill: rises from the bottom edge at [start], peaks, and comes back down to the bottom at [end] (past the
        // right edge for the last ones, where the card cuts it)
        fun hill(start: Float, end: Float, tall: Float) {
            val crest = androidx.compose.ui.graphics.Path()
            val steps = 90
            for (i in 0..steps) {
                val u = i / steps.toFloat()
                val x = w * (start + (end - start) * u)
                val y = h - 4f - (h - 8f) * tall * kotlin.math.sin(u * Math.PI.toFloat()).let { it * it }
                if (i == 0) crest.moveTo(x, y) else crest.lineTo(x, y)
            }
            val fill = androidx.compose.ui.graphics.Path().apply { addPath(crest); lineTo(w * end, h); lineTo(w * start, h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), cool.copy(alpha = 0.08f)), startY = h * (1f - tall), endY = h))
            // the line fades out where the hill meets the bottom, so no flat line runs along the edge
            drawPath(crest, Brush.horizontalGradient(0f to Color.Transparent, 0.25f to cool.copy(alpha = 0.7f), 0.7f to tint, 1f to Color.Transparent, startX = w * start, endX = w * end), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
        }
        hill(0.28f, 0.92f, 0.55f)
        hill(0.5f, 1.12f, 0.8f)
        hill(0.7f, 1.3f, 0.5f)
        drawRect(Brush.horizontalGradient(0.25f to Color.Transparent, 0.6f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
}

/** [c] turned round the colour wheel by [degrees], same strength and lightness. */
private fun shiftHue(c: Color, degrees: Float): Color {
    val hsb = java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), null)
    val rgb = java.awt.Color.HSBtoRGB(((hsb[0] + degrees / 360f) % 1f + 1f) % 1f, hsb[1], hsb[2])
    return Color(rgb).copy(alpha = 1f)
}

/** Whether Now playing opens on the full lyrics; tests set it to draw that view. */
internal var startFullLyrics = false

/** The lyrics alone, large, following the singing; a click on a line jumps there. The song is named small on top. */
@Composable
private fun FullLyrics(services: Services, song: Song, shiftMs: Int, onBack: () -> Unit, modifier: Modifier) {
    val lyrics = lyricsFor(services, song)
    Column(modifier.padding(top = 8.dp)) {
        Text("‹ Now playing", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
        Row(Modifier.padding(top = 14.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(song.thumbnailUrl ?: song.localThumbPath, 64.dp, RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontFamily = Heading, fontSize = 26.sp, color = Palette.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(song.artist, color = Palette.ink2, fontSize = 15.sp, maxLines = 1)
            }
            val from = ((lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Timed)?.from
            if (from != null) Text("Lyrics from $from", color = Palette.ink2, fontSize = 12.sp)
            // lyrics running ahead of or behind the singing: a tenth of a second a click, kept for this song
            if (from != null) {
                val set = services.library.settings::setLyricsShift
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("−") { set(song.id, nudgeShift(shiftMs, -SHIFT_STEP_MS)) }
                    Text(shiftLabel(shiftMs), color = if (shiftMs == 0) Palette.ink2 else Palette.ink, fontSize = 13.sp, modifier = Modifier.widthIn(min = 140.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Pill("+") { set(song.id, nudgeShift(shiftMs, SHIFT_STEP_MS)) }
                    if (shiftMs != 0) Pill("Reset") { set(song.id, 0) }
                }
            }
        }
        LyricsPanel(services, song, shiftMs, Modifier.weight(1f).fillMaxWidth(), big = true)
    }
}

/** The lyrics of [song], looked up once per song. */
@Composable
internal fun lyricsFor(services: Services, song: Song): LyricsState {
    val l by produceState<LyricsState>(LyricsState.Loading, song.id) {
        value = LyricsState.Loading
        value = try { services.lyrics(song)?.let { LyricsState.Found(it) } ?: LyricsState.None } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { LyricsState.Failed }
    }
    return l
}

@Composable
private fun LyricsPanel(services: Services, song: Song, shiftMs: Int, modifier: Modifier, big: Boolean = false) {
    val lyrics = lyricsFor(services, song)
    Box(modifier) {
        when (val l = lyrics) {
            LyricsState.Loading -> Text("Looking for lyrics…", fontFamily = Display, fontSize = 22.sp, color = Palette.ink2)
            LyricsState.None -> Text("No lyrics for this song", fontFamily = Display, fontSize = 20.sp, color = Palette.ink.copy(alpha = 0.4f))
            LyricsState.Failed -> Text("Couldn't load lyrics. Check your connection.", fontFamily = Display, fontSize = 22.sp, color = Palette.ink2)
            is LyricsState.Found -> when (val x = l.lyrics) {
                is Lyrics.Timed -> TimedLyrics(services, x, shiftMs, big)
                is Lyrics.Plain -> Column {
                    Text("Lyrics from ${Lyrics.LRCLIB} · not timed", color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(contentPadding = PaddingValues(bottom = if (big) LocalBottomInset.current else 0.dp)) { itemsIndexed(x.lines) { _, line -> Text(line.ifBlank { " " }, fontFamily = Display, fontSize = if (big) 30.sp else 24.sp, lineHeight = if (big) 40.sp else 32.sp, color = Palette.ink.copy(alpha = 0.85f)) } }
                }
            }
        }
    }
}

@Composable
private fun TimedLyrics(services: Services, lyrics: Lyrics.Timed, shiftMs: Int, big: Boolean) {
    val position by services.player.position.collectAsState()
    // a positive shift runs the lyrics earlier: they are looked up that much ahead of the song
    val at = position + shiftMs
    val index = lyrics.lines.indexAt(at)
    val list = rememberLazyListState()
    // in the card the sung line sits on top; on the full page two lines stay above it
    LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem((index - if (big) 2 else 0).coerceAtLeast(0)) }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(if (big) 18.dp else 10.dp), contentPadding = PaddingValues(bottom = if (big) 400.dp else 0.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == index
            Text(
                lineText(line, current, at), fontFamily = Display, fontSize = if (big) (if (current) 40.sp else 30.sp) else if (current) 26.sp else 21.sp, lineHeight = if (big) (if (current) 48.sp else 38.sp) else if (current) 32.sp else 27.sp, letterSpacing = (-0.5).sp,
                color = if (current) Palette.ink else Palette.ink.copy(alpha = if (big) 0.35f else 0.4f),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { services.player.seekTo((line.atMs - shiftMs).coerceAtLeast(0)) },
            )
        }
    }
}

/** The line as text; the current one, when its words are timed, shows the words already sung in full colour. */
@Composable
private fun lineText(line: LyricLine, current: Boolean, at: Long): AnnotatedString {
    val text = line.text.ifBlank { "♪" }
    if (!current || line.words.isEmpty()) return AnnotatedString(text)
    val sung = Palette.ink; val ahead = Palette.ink.copy(alpha = 0.45f)
    return buildAnnotatedString { line.words.forEach { w -> withStyle(SpanStyle(color = if (w.startMs <= at) sung else ahead)) { append(w.text) } } }
}

/** The listener's note on the song, one quiet line under the artist; a click edits it. Nothing when there is none. */
@Composable
private fun SongNote(services: Services, songId: String) {
    val note by remember(songId) { services.library.db.notes().observe(songId) }.collectAsState(null)
    var editing by remember { mutableStateOf(false) }
    val text = note ?: return
    Tip("Edit note") {
        Text(
            text, fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 3, color = Palette.ink2,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable { editing = true },
        )
    }
    if (editing) NoteDialog(services, songId, text) { editing = false }
}

/** Behind Now playing: the song's cover, large and blurred, melting into the window at the bottom and on the left. */
@Composable
fun NowPlayingBackdrop(song: Song?, base: Color, modifier: Modifier = Modifier) {
    val url = song?.let { it.thumbnailUrl ?: it.localThumbPath }
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) { value = url?.let { Covers.load(com.spotifry.music.data.source.artAt(it, true)) } }
    Box(
        modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0.35f to Color.Black, 0.85f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) },
    ) {
        androidx.compose.animation.Crossfade(image, animationSpec = androidx.compose.animation.core.tween(600)) { shown ->
            if (shown != null) androidx.compose.foundation.Image(shown, null, Modifier.fillMaxSize().blur(36.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle).graphicsLayer { alpha = 0.75f }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to base.copy(alpha = 0.8f), 0.45f to base.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = 0.6f), 0.18f to Color.Transparent)))
    }
}
