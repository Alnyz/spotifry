package com.spotifry.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongYearEntity
import com.spotifry.music.data.source.artAt
import com.spotifry.music.data.spotifried.CardId
import com.spotifry.music.data.spotifried.MusicBrainz
import com.spotifry.music.data.spotifried.SpotifriedStats
import com.spotifry.music.data.spotifried.WrappedPeriod
import com.spotifry.music.data.spotifried.calendarYear
import com.spotifry.music.data.spotifried.lookUpYears
import com.spotifry.music.data.spotifried.morePlaysNeeded
import com.spotifry.music.data.spotifried.nextToLookUp
import com.spotifry.music.data.spotifried.periodChoices
import com.spotifry.music.data.spotifried.shownCards
import com.spotifry.music.data.spotifried.spotifriedStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** The story's own black, on either theme, as on the phone. */
internal val StoryBlack = Color(0xFF0B0B0C)

/** The phone's card size in dp; a saved picture is this at three times, 1080 by 1920. */
private const val CARD_W = 360
private const val CARD_H = 640
/** Above the card: the progress on screen, the watermark in a picture. */
private val WatermarkBand = 52.dp

data class SpotifriedState(
    val stats: SpotifriedStats, val cards: List<CardId>, val choices: List<WrappedPeriod>,
    /** Songs of the period with / without a year lookup done yet. */
    val checked: Int, val unchecked: Int,
    /** Plays missing for a story; null when the period is over and nothing can be added to it. */
    val morePlays: Int?,
    val name: String,
)

/** The phone's SpotifriedViewModel.build: without a [pick], the newest period that has a story. */
internal fun spotifriedState(rows: List<ListenRow>, withYear: List<SongYearEntity>, pick: WrappedPeriod?, name: String, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): SpotifriedState {
    val first = rows.minOfOrNull { it.startedAt }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: now
    val choices = periodChoices(first, now)
    val years = withYear.mapNotNull { r -> r.year?.let { r.songId to it } }.toMap()
    val checked = withYear.map { it.songId }.toSet()
    fun statsOf(p: WrappedPeriod) = spotifriedStats(rows, years, p, zone, checked)
    val stats = if (pick != null) statsOf(pick) else choices.asSequence().map(::statsOf).firstOrNull { shownCards(it).isNotEmpty() } ?: statsOf(calendarYear(now.year, now))
    return SpotifriedState(
        stats, shownCards(stats), choices, stats.age.songsTotal - stats.age.unchecked, stats.age.unchecked,
        if (stats.period.to < now) null else morePlaysNeeded(stats), name,
    )
}

private val looking = AtomicBoolean(false)

/**
 * Release years for played songs, from MusicBrainz, one a second, most played first; what the listening age card needs.
 * One run at a time; it ends when every played song has been asked. Offline or turned away, it waits longer each time.
 */
fun lookUpSongYears(library: Library, scope: CoroutineScope, mb: MusicBrainz) {
    if (!looking.compareAndSet(false, true)) return
    scope.launch {
        try {
            var wait = 60_000L
            while (true) {
                val plays = library.db.listens().playCounts().associate { it.songId to it.n }
                val ids = nextToLookUp(plays, library.db.songYears().all(), System.currentTimeMillis())
                val songs = ids.mapNotNull { library.db.songs().get(it) }
                // a played song with no row would be picked again for ever
                if (songs.isEmpty()) break
                if (lookUpYears(songs, mb, library.db.songYears(), System::currentTimeMillis) { delay(1000) }) wait = 60_000L
                else { delay(wait); wait = minOf(wait * 2, 30 * 60_000L) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { /* the database closed for a restore; the next start asks again */ }
        finally { looking.set(false) }
    }
}

/** The story is always dark, whatever the window's theme; the accent is the user's, in its dark-theme shade. */
@Composable
internal fun StoryTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        background = StoryBlack, surface = StoryBlack, onBackground = Color(0xFFF2F3F5), onSurface = Color(0xFFF2F3F5),
        primary = Palette.accentChoice.dark, onPrimary = Color(0xFF2A1205),
    ),
    typography = Typography(),
) { CompositionLocalProvider(LocalContentColor provides Color(0xFFF2F3F5), content = content) }

/** A cover filling what it is given, or a quiet square while it loads. */
@Composable
internal fun StoryCover(url: String?, modifier: Modifier, shape: Shape) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { Covers.load(artAt(it, true)) } }
    Box(modifier.clip(shape).background(Color.White.copy(alpha = .08f))) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
internal fun StoryCard(id: CardId, state: SpotifriedState, still: Boolean = false, onSave: (() -> Unit)?, working: Boolean = false, message: String? = null) {
    when (id) {
        CardId.INTRO -> IntroCard(state.stats, still)
        CardId.TOP_SONG -> TopSongCard(state.stats, still)
        CardId.HOURS -> HoursCard(state.stats, still)
        CardId.AGE -> AgeCard(state.stats, state.checked, state.unchecked, still)
        CardId.DAY -> DayCard(state.stats, still)
        CardId.MONTHS -> MonthsCard(state.stats, still)
        CardId.SUN -> SunCard(state.stats, still)
        CardId.TYPE -> TypeCard(state.stats, onSave, still, working, message, state.name)
    }
}

/** Spotifried: the period's story as the phone's cards, one at a time, at the phone's shape. */
@Composable
fun SpotifriedPage(services: Services, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    LaunchedEffect(Unit) { services.lookUpYears() }
    val rows by remember(lib) { lib.db.listens().observeAll() }.collectAsState(null)
    val years by remember(lib) { lib.db.songYears().observeAll() }.collectAsState(null)
    val name by lib.settings.name.collectAsState()
    var picked by remember { mutableStateOf<WrappedPeriod?>(null) }
    val loaded by produceState<SpotifriedState?>(null, rows, years, picked, name) {
        val r = rows ?: return@produceState; val y = years ?: return@produceState
        value = withContext(Dispatchers.Default) { spotifriedState(r, y, picked, name) }
    }
    val focus = remember { FocusRequester() }
    Column(
        modifier.focusRequester(focus).focusable()
            // the window takes the keys back on any click; this page asks for them again, after it
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { if (awaitPointerEvent().type == PointerEventType.Press) focus.requestFocus() } } },
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Text("‹ Your rotation", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
        val state = loaded ?: return@Column
        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString { append("Spotifried"); withStyle(SpanStyle(color = Palette.accent)) { append(".") } },
                fontFamily = Heading, fontSize = 30.sp, color = Palette.ink, modifier = Modifier.padding(end = 24.dp),
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.choices.forEach { p -> Chip(p.label, p == state.stats.period) { picked = p } }
            }
        }
        Story(services, state, focus, Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp, bottom = 16.dp + LocalBottomInset.current))
    }
}

@Composable
private fun Story(services: Services, state: SpotifriedState, focus: FocusRequester, modifier: Modifier) {
    val cards = state.cards
    // a new period starts again from the first card
    var index by remember(state.stats.period) { mutableIntStateOf(0) }
    fun go(to: Int) { if (to in cards.indices) index = to }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var message by remember(state.stats.period) { mutableStateOf<String?>(null) }
    val save = {
        if (!working) {
            working = true; message = null
            scope.launch {
                message = try {
                    val dir = savePictures(state)
                    "Saved ${cards.size} pictures in ${dir.path.replaceFirst(System.getProperty("user.home"), "~")}"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { "Couldn't save the pictures. Try again." }
                working = false
            }
        }
    }
    Row(
        modifier.onKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (it.key) {
                Key.DirectionRight, Key.Spacebar -> { go(index + 1); true }
                Key.DirectionLeft -> { go(index - 1); true }
                else -> false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cards.isNotEmpty()) Round(Icons.Prev, "Previous card", index > 0) { go(index - 1); focus.requestFocus() }
        // the phone's card, laid out at the phone's 360 by 640 and scaled to the room: what shows is what a picture saves
        BoxWithConstraints(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
            val k = minOf(maxWidth.value / CARD_W, maxHeight.value / CARD_H)
            Box(Modifier.size((CARD_W * k).dp, (CARD_H * k).dp).clip(RoundedCornerShape((22 * k).dp)).background(StoryBlack)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density * k, 1f)) {
                    StoryTheme {
                        if (cards.isEmpty()) NotEnough(state.morePlays)
                        else Column(Modifier.fillMaxSize()) {
                            Segments(cards.size, index)
                            Box(
                                Modifier.weight(1f).fillMaxWidth().pointerInput(cards, index) {
                                    detectTapGestures { at -> go(if (at.x > size.width / 2) index + 1 else index - 1) }
                                },
                                Alignment.Center,
                            ) {
                                // the card going out fades while the next draws its first lines, so there's never an empty card
                                AnimatedContent(
                                    cards[index.coerceIn(cards.indices)], Modifier.fillMaxSize(),
                                    transitionSpec = { EnterTransition.None togetherWith fadeOut(tween(300)) },
                                    contentAlignment = Alignment.Center, label = "card",
                                ) { StoryCard(it, state, onSave = save, working = working, message = message) }
                            }
                        }
                    }
                }
            }
        }
        if (cards.isNotEmpty()) Round(Icons.Next, "Next card", index < cards.size - 1) { go(index + 1); focus.requestFocus() }
    }
}

/** The thin bars over the card, one per card, filled up to the one shown. */
@Composable
private fun Segments(count: Int, index: Int) {
    Row(Modifier.fillMaxWidth().height(WatermarkBand).padding(horizontal = 20.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
        repeat(count) { i ->
            val fill by animateFloatAsState(if (i <= index) 1f else .18f, tween(300, 150), label = "segment")
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFF2F3F5).copy(alpha = fill)))
        }
    }
}

@Composable
private fun NotEnough(more: Int?) = Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), Arrangement.Center) {
    Text("Not enough listening yet", fontFamily = Heading, fontSize = 30.sp, lineHeight = 34.sp)
    if (more != null) Text("$more more ${if (more == 1) "play" else "plays"} to go", Modifier.padding(top = 8.dp), Color(0xFFF2F3F5).copy(alpha = .64f), fontSize = 16.sp, fontFamily = Albert)
}

/** A round button beside the card; faint when there is nowhere further to go. */
@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Palette.ink.copy(alpha = if (on) .1f else .04f)).clickable(enabled = on, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = Palette.ink.copy(alpha = if (on) 1f else .3f)) }
}

/** The Spotifry mark on a picture 360 dp wide: the phone's drawWatermark. [bars] tints the three bars. */
@Composable
internal fun Watermark(modifier: Modifier = Modifier, text: Color = Color.White, bars: Color = MaterialTheme.colorScheme.primary) {
    val em = 18f * .78f // the phone's em is a twentieth of the width, the mark .78 of it
    val accent = bars
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(((3 * .2f + 2 * .14f) * em).dp, em.dp)) {
            val u = size.height
            listOf(.55f, 1f, .7f).forEachIndexed { i, k ->
                val h = .9f * u * k
                drawRoundRect(accent.copy(alpha = .88f), Offset(i * .34f * u, u * .95f - h), Size(.2f * u, h), CornerRadius(.1f * u))
            }
        }
        Spacer(Modifier.width((.4f * em).dp))
        Text("Spotifry", color = text.copy(alpha = text.alpha * .88f), fontSize = em.sp, fontFamily = Albert, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = (-.01f * em).sp)
    }
}

/** Where pictures go: the desktop's Pictures folder, or ~/Pictures. */
internal fun picturesDir(): File = Platform.userFolder("Pictures")

/** Every card of [state] as a 1080 by 1920 picture, still, with the watermark, in "Spotifried <period>" under Pictures. */
internal suspend fun savePictures(state: SpotifriedState, into: File = picturesDir()): File = withContext(Dispatchers.IO) {
    // the cover is fetched first, so the card has it when it is drawn
    state.stats.topSong?.song?.let { s -> (s.thumbnailUrl ?: s.thumbPath)?.let { Covers.load(artAt(it, true)) } }
    val dir = File(into, "Spotifried ${state.stats.period.label}").apply { mkdirs() }
    state.cards.forEachIndexed { i, id ->
        val scene = ImageComposeScene(CARD_W * 3, CARD_H * 3, Density(3f)) {
            StoryTheme {
                Box(Modifier.fillMaxSize().background(StoryBlack)) {
                    Watermark(Modifier.padding(start = 28.8.dp, top = 30.6.dp))
                    Box(Modifier.padding(top = WatermarkBand)) { StoryCard(id, state, still = true, onSave = null) }
                }
            }
        }
        try {
            var t = 0L
            repeat(6) { scene.render(t); t += 16_000_000; Thread.sleep(30) }
            File(dir, "%d %s.png".format(i + 1, id.name.lowercase().replace('_', ' '))).writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }
    dir
}

/**
 * On Your rotation: the way into Spotifried, the phone's record sleeve beside what it holds. A click on either opens it.
 */
@Composable
fun SpotifriedBand(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val year = remember { LocalDate.now().year }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        SpotifriedGate(year, onOpen, Modifier.widthIn(max = 420.dp).weight(1f, fill = false))
        Column(Modifier.weight(1f)) {
            Kicker("Spotifried")
            Text(
                buildAnnotatedString { append("Your year, in cards"); withStyle(SpanStyle(color = Palette.accent)) { append(".") } },
                fontFamily = Heading, fontSize = 34.sp, lineHeight = 38.sp, color = Palette.ink, modifier = Modifier.padding(top = 6.dp),
            )
            Text(SPOTIFRIED_LINE, color = Palette.ink2, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private const val SPOTIFRIED_LINE = "Your number one song, your hours, your listening age and what kind of listener you are. " +
    "Saved as pictures, the same as the phone's; nothing leaves this computer."

/** The same, stacked: the sleeve with the words under it, for beside the numbers on a wide window. */
@Composable
fun SpotifriedTall(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SpotifriedGate(remember { LocalDate.now().year }, onOpen)
        Text(
            buildAnnotatedString { append("Your year, in cards"); withStyle(SpanStyle(color = Palette.accent)) { append(".") } },
            fontFamily = Heading, fontSize = 24.sp, lineHeight = 28.sp, color = Palette.ink, modifier = Modifier.padding(top = 16.dp),
        )
        Text(SPOTIFRIED_LINE, color = Palette.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
