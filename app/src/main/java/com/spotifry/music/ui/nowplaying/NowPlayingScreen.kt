package com.spotifry.music.ui.nowplaying

import com.spotifry.music.player.SleepState
import com.spotifry.music.player.clockText
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.player.OutputInfo
import com.spotifry.music.player.RepeatMode
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.coverTint
import com.spotifry.music.data.source.isLocal

sealed interface SaveUi {
    data object Unsaved : SaveUi
    data class Saving(val progress: Float) : SaveUi
    data object Saved : SaveUi
    data object Failed : SaveUi
}

/** The quieter of the page's two text colours: the content colour, dimmed. */
internal val Faint @Composable get() = LocalContentColor.current.copy(alpha = if (com.spotifry.music.ui.theme.isDark()) .72f else .66f)

/** Behind the round ⋯, the bar's empty part and a picked mode button. */
private val Dim @Composable get() = LocalContentColor.current.copy(alpha = if (com.spotifry.music.ui.theme.isDark()) .22f else .16f)

/** What fills Now playing: the big cover, or lyrics or the queue in place of it, with the cover shrunk into a row. */
enum class NowPlayingMode { Cover, Lyrics, Queue }

/**
 * One screen, no scrolling: the cover runs full width under the status bar and fades into its own colour, then the
 * title, one lyric line, the progress bar, the controls, and lyrics, output and queue at the bottom. On a short screen or
 * with large text the cover gives way. Follows the app's theme: the cover colour deep on dark, pale on light.
 */
@Composable
fun NowPlayingContent(
    state: PlayerUiState, save: SaveUi,
    onClose: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onSave: () -> Unit, onMore: () -> Unit,
    modifier: Modifier = Modifier,
    saved: Set<String> = emptySet(), onPick: (Int) -> Unit = {},
    lyrics: LyricsUi = LyricsUi.None, onRetryLyrics: () -> Unit = {},
    mode: NowPlayingMode = NowPlayingMode.Cover, onMode: (NowPlayingMode) -> Unit = {},
    position: () -> Long = { 0L },
    sleep: SleepState = SleepState.Off, sleepLeft: () -> Long? = { null }, onSleep: () -> Unit = {},
    /** "1.25× · +2" when speed or pitch isn't normal, else null. */
    speedLabel: String? = null, onSpeed: () -> Unit = {},
    /** The playback speed, so lyrics count along at the same pace. */
    speed: Float = 1f,
    /** The ⋮ of a suggested song in the queue, with its queue position. */
    onSuggestionMore: (Int) -> Unit = {},
    /** The scissors by the time left of a trimmed song: opens the Trim sheet. */
    onTrim: () -> Unit = {},
    /** The quality label between the times: opens the Audio pipeline sheet. */
    onQuality: () -> Unit = {},
    /** Milliseconds this song's lyrics run earlier (negative: later), set in lyrics mode. */
    lyricsShift: Int = 0, onLyricsShift: (Int) -> Unit = {},
    /** The listener's note on this song, one quiet line under the artist; tapping it edits it. */
    note: String? = null, onNote: () -> Unit = {},
    /** The output button in the bottom row: opens the Audio output sheet. */
    onOutput: () -> Unit = {},
    /** A drag in the queue, in places after the playing song (1 = next), and a song swiped away by its queue position. */
    onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int) -> Unit = {},
) {
    val song = state.current ?: return
    // a new song closes the lyrics of the old one
    LaunchedEffect(song.id) { if (mode == NowPlayingMode.Lyrics) onMode(NowPlayingMode.Cover) }
    val client = LocalImageClient.current
    val files = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val tint by produceState<Color?>(null, song.id, client) { value = coverTint(client, song.thumbnailUrl, song.localThumbPath, files) }
    val cover by animateColorAsState(tint ?: Color(0xFF2A2E35), tween(500), label = "cover colour")
    val dark = com.spotifry.music.ui.theme.isDark()
    val top = if (dark) cover else pale(cover, .3f, .91f)
    val bottom = if (dark) lerp(cover, Color.Black, .6f) else pale(cover, .15f, .955f)
    val ink = if (dark) Color.White else Color(0xFF15181C)
    // white status icons over the cover's dark shade; on a pale page in lyrics or queue mode, dark ones
    val view = androidx.compose.ui.platform.LocalView.current
    val bars = remember(view) { (view.context as? android.app.Activity)?.window?.takeIf { !view.isInEditMode }?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) } }
    val lightStatus = !dark && mode != NowPlayingMode.Cover
    androidx.compose.runtime.SideEffect { bars?.isAppearanceLightStatusBars = lightStatus; bars?.isAppearanceLightNavigationBars = !dark }
    androidx.compose.runtime.DisposableEffect(bars, dark) { onDispose { bars?.isAppearanceLightStatusBars = !dark; bars?.isAppearanceLightNavigationBars = !dark } }
    val output = rememberOutput()
    // lyrics are timed to the whole song; the player counts from the trim's start
    val trimStart = state.trim?.startMs ?: 0L
    val sung = remember(trimStart, position, lyricsShift) { { position() + trimStart + lyricsShift } }
    CompositionLocalProvider(LocalContentColor provides ink) {
    val blur by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, song.id, client) { value = com.spotifry.music.ui.components.coverBlur(client, song.thumbnailUrl, song.localThumbPath, files) }
    // pure black keeps a near-black page; otherwise the veil is the cover colour, deep or pale
    val black = dark && MaterialTheme.colorScheme.background == Color.Black
    val veilTop = if (black) Color(0xFF0B0B0C) else top
    val veilBottom = if (black) Color(0xFF050506) else bottom
    // one background for the whole screen: the cover blurred and stretched to fill, under a veil of its colour, so the
    // sharp cover fades into its own texture and no edge can show
    Box(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(listOf(veilTop, veilBottom)))
        blur?.let { b ->
            val scale = maxOf(size.width / b.width, size.height / b.height)
            val w = b.width * scale; val h = b.height * scale
            drawImage(
                b, dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            )
            drawRect(Brush.verticalGradient(listOf(veilTop.copy(alpha = .8f), veilBottom.copy(alpha = .92f))))
        }
    }) {
        androidx.compose.animation.Crossfade(mode, animationSpec = tween(220), label = "now playing mode") { m ->
            if (m == NowPlayingMode.Cover) BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                var body by remember { mutableStateOf(0.dp) }
                // the cover fills what the controls leave, at most 40 dp taller than wide (its sides cut); more room goes under the bottom row
                val room = (maxHeight - body - 16.dp).coerceAtLeast(0.dp)
                val side = room.coerceIn(maxWidth + 48.dp, maxWidth + 88.dp)
                val shown = minOf(side, room)
                BigCover(state, onClose, sleep, sleepLeft, onSleep, speedLabel, onSpeed, side, shown)
                Column(
                    Modifier.fillMaxWidth().offset(y = minOf(maxHeight - body, shown + 16.dp)).onSizeChanged { body = with(density) { it.height.toDp() } }
                        .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
                ) {
                    TitleRow(song, save, note, onSave, onMore, onNote, Modifier.padding(horizontal = 12.dp))
                    LyricsPeek(lyrics, sung, { onMode(NowPlayingMode.Lyrics) }, onRetryLyrics, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                    ProgressBar(state, position, Modifier.padding(horizontal = 12.dp).padding(top = 8.dp), onSeek, onTrim, onQuality)
                    Controls(state, onToggle, onNext, onPrev, onShuffle, onRepeat, Modifier.padding(top = 16.dp))
                    Tools(m, output, onMode, onOutput, Modifier.padding(top = 16.dp))
                }
            } else ModeView(
                m, state, lyrics, onRetryLyrics, position, speed, lyricsShift, onLyricsShift, onSeek, onTrim, onQuality,
                onToggle, onNext, onPrev, onShuffle, onRepeat, onMore, onMode, output, onOutput, saved, onPick, onSuggestionMore, onMove, onRemove, top,
            )
        }
    }
    }
}

/** [c]'s hue, light: its saturation times [keep] at brightness [value], so a light page still carries the cover's colour. */
private fun pale(c: Color, keep: Float, value: Float): Color {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * keep, value)))
}

/**
 * The cover, slightly wider than the screen and as tall as the room allows, fading into the page at the bottom. A dark
 * shade over its top carries the status bar, the grabber and where the music comes from; swiping down closes.
 */
@Composable
private fun BigCover(
    state: PlayerUiState, onClose: () -> Unit, sleep: SleepState, sleepLeft: () -> Long?, onSleep: () -> Unit, speedLabel: String?, onSpeed: () -> Unit,
    side: androidx.compose.ui.unit.Dp, shown: androidx.compose.ui.unit.Dp,
) {
    val song = state.current ?: return
    val density = LocalDensity.current
    var pulled by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(maxOf(shown, 120.dp)).draggable(
            rememberDraggableState { pulled += it }, Orientation.Vertical,
            onDragStarted = { pulled = 0f },
            onDragStopped = { v -> if (pulled > with(density) { 72.dp.toPx() } || v > 1_800f) onClose() },
        ),
    ) {
        // no box around it: the cover itself turns clear by [shown], over the page's one background, so there is no edge to see
        Cover(
            song.id, song.thumbnailUrl, song.localThumbPath, side,
            Modifier.align(Alignment.TopCenter).wrapContentSize(Alignment.TopCenter, unbounded = true).fadeOut(if (side > 0.dp) shown / side else 1f), radius = 0.dp,
        )
        Box(
            Modifier.fillMaxWidth().height(200.dp).background(
                Brush.verticalGradient(*(0..12).map { k -> k / 12f to Color.Black.copy(alpha = .8f * (1f - smooth01(k / 12f))) }.toTypedArray()),
            ),
        )
        // white on both themes: it sits on the shade
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                Column(
                    Modifier.fillMaxWidth().height(48.dp).clickable(onClickLabel = "Close player", onClick = onClose),
                    Arrangement.spacedBy(8.dp, Alignment.CenterVertically), Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .7f)))
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (state.from == null) append("Now playing") else {
                                append("Playing from ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(state.from) }
                            }
                        },
                        Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sleep != SleepState.Off || speedLabel != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    if (speedLabel != null) InfoPill(Icons.Speed, speedLabel, "Change speed and pitch", onSpeed)
                    if (sleep != SleepState.Off) SleepPill(sleep, sleepLeft, onSleep)
                }
            }
        }
    }
}

/** Fades what is drawn to clear on a smooth curve, fully gone at [end] (a share of its height) and below. */
private fun Modifier.fadeOut(end: Float) = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        // smoothstep from solid at half of [end] to clear at [end]: no slope where the fade starts or ends, so no line shows
        val from = end * .5f
        val stops = (0..12).map { k ->
            val t = k / 12f
            (from + (end - from) * t).coerceIn(0f, 1f) to Color.Black.copy(alpha = 1f - smooth01(t))
        }
        drawRect(Brush.verticalGradient(*stops.toTypedArray()), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/** Soft top and bottom edges for the lyrics and the queue, so lines slide out of view instead of being cut. */
private fun Modifier.fadeEdges() = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height.coerceAtLeast(1f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, (24.dp.toPx() / h).coerceAtMost(.3f) to Color.Black, (1f - 48.dp.toPx() / h).coerceAtLeast(.7f) to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** Title and artist the same size, the note under them, then save and the round ⋯ for the song menu. */
@Composable
private fun TitleRow(song: com.spotifry.music.data.source.Song, save: SaveUi, note: String?, onSave: () -> Unit, onMore: () -> Unit, onNote: () -> Unit, modifier: Modifier) {
    val big = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    Row(modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(song.title, Modifier.basicMarquee(), style = big.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(song.artist, style = big.copy(fontWeight = FontWeight.Normal), color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(
                note, Modifier.padding(top = 4.dp).fillMaxWidth().clickable(onClickLabel = "Edit note", onClick = onNote).semantics { contentDescription = "Your note: $note" },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, lineHeight = 20.sp),
                color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // a file on the phone is already there: nothing to save
        if (!song.isLocal) SaveButton(save, onSave)
        RoundMore(onMore)
    }
}

/** The song menu's ⋯ in a soft 40 dp circle, with a 48 dp touch area. */
@Composable
private fun RoundMore(onMore: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore).semantics { contentDescription = "More options" }, Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Dim), Alignment.Center) { Icon(Icons.Dots, null, Modifier.size(24.dp)) }
    }
}

/** Lyrics, output, queue. The mode buttons show a rounded square while their mode is open; a second tap goes back to the cover. */
@Composable
private fun Tools(mode: NowPlayingMode, output: OutputInfo, onMode: (NowPlayingMode) -> Unit, onOutput: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        ModeButton(Icons.LyricsBubble, "Lyrics", mode == NowPlayingMode.Lyrics) { onMode(if (mode == NowPlayingMode.Lyrics) NowPlayingMode.Cover else NowPlayingMode.Lyrics) }
        Row(
            Modifier.weight(1f, fill = false).height(48.dp).clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = "Change output", onClick = onOutput)
                .semantics(mergeDescendants = true) { contentDescription = "Audio output: ${output.name}" }.padding(horizontal = 14.dp),
            Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
        ) {
            Icon(outputIcon(output.look), null, Modifier.size(20.dp), tint = Faint)
            Text(output.name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ModeButton(Icons.Queue, "Queue", mode == NowPlayingMode.Queue) { onMode(if (mode == NowPlayingMode.Queue) NowPlayingMode.Cover else NowPlayingMode.Queue) }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).let { if (on) it.background(Dim) else it }
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label; selected = on },
        Alignment.Center,
    ) { Icon(icon, null, tint = if (on) LocalContentColor.current else Faint) }
}

/** Hides the controls while the list is moved on by hand, and shows them again when it is moved back. */
private class Hider : NestedScrollConnection {
    var hidden by mutableStateOf(false)
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) { if (available.y < -1f) hidden = true else if (available.y > 1f) hidden = false }
        return Offset.Zero
    }
}

/**
 * Lyrics or the queue in place of the cover. The cover shrinks into a row at the top (a tap on it goes back). Lyrics
 * keep only the progress bar and their timing row, so nothing gets hit by accident while reading; the queue keeps the
 * controls too. Moving through the list by hand hides those; moving back, or the lyrics following the song again, shows them.
 */
@Composable
private fun ModeView(
    mode: NowPlayingMode, state: PlayerUiState, lyrics: LyricsUi, onRetry: () -> Unit, position: () -> Long, speed: Float,
    shift: Int, onShift: (Int) -> Unit, onSeek: (Long) -> Unit, onTrim: () -> Unit, onQuality: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onMore: () -> Unit,
    onMode: (NowPlayingMode) -> Unit, output: OutputInfo, onOutput: () -> Unit,
    saved: Set<String>, onPick: (Int) -> Unit, onSuggestionMore: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, rowColor: Color,
) {
    val song = state.current ?: return
    val hider = remember(mode) { Hider() }
    val ready = (lyrics as? LyricsUi.Ready)?.lyrics
    Column(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var pulled by remember { mutableStateOf(0f) }
        // only the header starts a pull down, so the list below keeps its own scrolling
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).draggable(
                rememberDraggableState { pulled += it }, Orientation.Vertical,
                onDragStarted = { pulled = 0f },
                onDragStopped = { v -> if (pulled > with(density) { 56.dp.toPx() } || v > 1_800f) onMode(NowPlayingMode.Cover) },
            ),
        ) {
            Box(
                Modifier.fillMaxWidth().height(28.dp).clickable(onClickLabel = "Back to the cover") { onMode(NowPlayingMode.Cover) }.semantics { contentDescription = "Back to the cover" },
                Alignment.Center,
            ) { Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(LocalContentColor.current.copy(alpha = .5f))) }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Back to the cover") { onMode(NowPlayingMode.Cover) }.padding(12.dp),
                    Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                ) {
                    Cover(song.id, song.thumbnailUrl, song.localThumbPath, 64.dp, radius = 8.dp)
                    Column(Modifier.weight(1f)) {
                        val mid = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 23.sp)
                        Text(song.title, style = mid.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = mid.copy(fontWeight = FontWeight.Normal), color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundMore(onMore)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().fadeEdges().nestedScroll(hider)) {
            if (mode == NowPlayingMode.Lyrics) {
                if (ready != null) LyricsLines(state, position, ready, onSeek, speed = speed, shift = shift, onReading = { if (!it) hider.hidden = false })
                else Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(
                        when (lyrics) { LyricsUi.Loading -> "Looking for lyrics…"; LyricsUi.Failed -> "Couldn't load lyrics. Check your connection."; else -> "No lyrics for this song." },
                        style = MaterialTheme.typography.titleMedium, color = Faint,
                    )
                    if (lyrics == LyricsUi.Failed) androidx.compose.material3.TextButton(onRetry, Modifier.padding(top = 8.dp)) { Text("Try again", color = LocalContentColor.current) }
                }
            } else Column(Modifier.fillMaxSize()) {
                Text("Queue", Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        if (state.from == null) append("Next up") else {
                            append("Next from ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = LocalContentColor.current, fontWeight = FontWeight.SemiBold)) { append(state.from) }
                        }
                    },
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.upNextIndices().size <= 1) Text(
                    // says what the player will really do: the same rules as shouldAppend
                    when {
                        state.repeatMode == RepeatMode.ONE -> "This song plays again when it ends."
                        state.repeatMode == RepeatMode.ALL && state.queue.size > 1 -> "The list starts again when this one ends."
                        else -> "Similar songs are added as this one plays."
                    },
                    Modifier.padding(horizontal = 24.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = Faint,
                )
                else com.spotifry.music.ui.QueueList(state, saved, onSuggestionMore, onMove, onRemove, Modifier.weight(1f), rowColor = rowColor, onPick = onPick)
            }
        }
        androidx.compose.animation.AnimatedVisibility(!hider.hidden) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = if (mode == NowPlayingMode.Lyrics) 24.dp else 20.dp)) {
                if (mode == NowPlayingMode.Lyrics && ready != null) TimingRow(ready, shift, onShift, Modifier.padding(start = 12.dp))
                ProgressBar(state, position, Modifier.padding(horizontal = 12.dp), onSeek, onTrim, onQuality)
                if (mode == NowPlayingMode.Queue) {
                    Controls(state, onToggle, onNext, onPrev, onShuffle, onRepeat, Modifier.padding(top = 6.dp))
                    Tools(mode, output, onMode, onOutput, Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/**
 * Keeps the screen awake while this is in the composition and [on]. Meant to sit inside Now playing, so leaving it lets
 * the screen sleep again; the caller passes "playing" too, since a paused song gives no reason to keep it lit.
 */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** A soft circle like the ⋯ beside it: download when not saved, a filling ring while saving, a check once saved, red when it failed. No accent: a user's accent can clash with the cover. */
@Composable
private fun SaveButton(save: SaveUi, onSave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = saveLabel(save)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = save is SaveUi.Unsaved || save is SaveUi.Failed, onClick = onSave)
            .semantics { contentDescription = label },
        Alignment.Center,
    ) {
        when (save) {
            SaveUi.Unsaved -> Box(Modifier.size(40.dp).clip(CircleShape).background(Dim), Alignment.Center) { Icon(Icons.Download, null, Modifier.size(22.dp)) }
            is SaveUi.Saving -> Box(Modifier.size(40.dp).clip(CircleShape).background(Dim), Alignment.Center) {
                CircularProgressIndicator(progress = { save.progress }, modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
            }
            SaveUi.Saved -> Box(Modifier.size(40.dp).clip(CircleShape).background(Dim), Alignment.Center) { Icon(Icons.Check, null, Modifier.size(22.dp)) }
            SaveUi.Failed -> Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.error.copy(alpha = .18f)), Alignment.Center) { Icon(Icons.Download, null, Modifier.size(22.dp), tint = scheme.error) }
        }
    }
}

/** A thick bar with no knob, still dragged to seek. Under it: time played, the quality label (opens the audio path) and time left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressBar(state: PlayerUiState, position: () -> Long, modifier: Modifier, onSeek: (Long) -> Unit, onTrim: (() -> Unit)? = null, onQuality: (() -> Unit)? = null) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.durationSec ?: 0) * 1000)
    val fraction = drag ?: if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
    // full ink is too heavy on a pale page
    val ink = LocalContentColor.current.let { if (com.spotifry.music.ui.theme.isDark()) it else it.copy(alpha = .72f) }
    val dim = Dim
    val small = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    Column(modifier) {
        Slider(
            value = fraction, onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = dim),
            thumb = { Spacer(Modifier.size(0.dp, 20.dp)) },
            track = {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(dim)) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ink))
                }
            },
            modifier = Modifier.height(20.dp).semantics { contentDescription = "Song position" },
        )
        val played = (fraction * duration).toLong()
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            Text(formatTime(played), Modifier.align(Alignment.CenterStart), style = small, color = Faint)
            state.quality?.let {
                QualityPill(it, Modifier.align(Alignment.Center).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .let { m -> if (onQuality != null) m.clickable(onClickLabel = "Show audio path", onClick = onQuality) else m }.padding(horizontal = 8.dp))
            }
            Row(
                // scissors: the only sign a song is trimmed, opening the Trim sheet
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().clip(CircleShape).let { if (state.trim != null && onTrim != null) it.clickable(onClickLabel = "Change trim", onClick = onTrim) else it },
                Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
            ) {
                if (state.trim != null) Icon(Icons.Cut, "Trimmed", Modifier.size(12.dp), tint = Faint)
                Text("-" + formatTime(duration - played), style = small, color = Faint)
            }
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState, onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, modifier: Modifier,
) {
    // a short line saying what the press just did; gone after a moment
    var hint by remember { mutableStateOf<String?>(null) }
    var hintTick by remember { mutableStateOf(0) }
    LaunchedEffect(hintTick) { if (hint != null) { kotlinx.coroutines.delay(1_500); hint = null } }
    fun say(text: String) { hint = text; hintTick++ }
    val slow = com.spotifry.music.ui.components.rememberSlow(state.loading)
    val shown = hint ?: if (slow) com.spotifry.music.ui.components.SLOW_TEXT else null
    Box(modifier.fillMaxWidth()) {
    androidx.compose.animation.AnimatedVisibility(
        shown != null, Modifier.align(Alignment.TopCenter).offset(y = (-34).dp),
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
    ) {
        Text(
            shown.orEmpty(), Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = Color.White,
        )
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        // the order the button steps through: off, Shuffle, Smart shuffle, off
        ToggleIcon(if (state.smart) Icons.SmartShuffle else Icons.Shuffle, if (state.smart) "Smart shuffle" else "Shuffle", state.shuffle) {
            say(when { !state.shuffle -> "Shuffle on"; !state.smart -> "Smart shuffle on"; else -> "Shuffle off" })
            onShuffle()
        }
        IconButton(onPrev, Modifier.size(64.dp)) { Icon(Icons.Prev, "Previous song", Modifier.size(40.dp)) }
        Box(Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onToggle), Alignment.Center) {
            com.spotifry.music.ui.components.PlayGlyph(state, 52.dp, LocalContentColor.current)
        }
        IconButton(onNext, Modifier.size(64.dp)) { Icon(Icons.Next, "Next song", Modifier.size(40.dp)) }
        ToggleIcon(
            if (state.repeatMode == RepeatMode.ONE) Icons.RepeatOne else Icons.Repeat,
            when (state.repeatMode) { RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat this song"; else -> "Repeat off" },
            state.repeatMode != RepeatMode.OFF,
        ) {
            // the order the button steps through: off, all, this song, off
            say(when (state.repeatMode) { RepeatMode.OFF -> "Repeat all songs"; RepeatMode.ALL -> "Repeat this song"; else -> "Repeat off" })
            onRepeat()
        }
    }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp), Alignment.Center) {
        // on is full white (or ink) with a dot, off is faded; no accent, which can clash with the cover
        IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, description, tint = if (active) LocalContentColor.current else Faint.copy(alpha = .45f)) }
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(6.dp).clip(CircleShape).background(LocalContentColor.current))
    }
}

private fun saveLabel(save: SaveUi) = when (save) {
    SaveUi.Unsaved -> "Save on this phone"
    is SaveUi.Saving -> "Saved on this phone" // shown as saved at once; the file finishes in the background
    SaveUi.Saved -> "Saved on this phone"
    SaveUi.Failed -> "Save failed. Retry"
}

/** Writes, changes or deletes the note on a song. Empty saves as no note; the text is cut at [com.spotifry.music.data.NOTE_MAX] as it is typed. */
@Composable
fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add a note" else "Edit note") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                text, { text = com.spotifry.music.data.cutName(it.replace('\n', ' '), com.spotifry.music.data.NOTE_MAX) }, maxLines = 4,
                placeholder = { Text("What this song means to you") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(text); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial.isNotEmpty()) androidx.compose.material3.TextButton(
                    { onSave(""); onDismiss() }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
                androidx.compose.material3.TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** "Stops in 28:41" under the top bar while the sleep timer runs. Tapping it opens the timer sheet. */
@Composable
private fun SleepPill(sleep: SleepState, left: () -> Long?, onClick: () -> Unit) {
    val text by produceState("", sleep) {
        while (true) {
            value = if (sleep is SleepState.Minutes) "Stops in ${clockText(left() ?: 0)}" else "Stops after this song"
            kotlinx.coroutines.delay(1_000)
        }
    }
    InfoPill(Icons.Clock, text, "Change sleep timer", onClick)
}

@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, clickLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable(onClickLabel = clickLabel, onClick = onClick)
            .height(32.dp).padding(start = 10.dp, end = 14.dp),
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"))
    }
}
