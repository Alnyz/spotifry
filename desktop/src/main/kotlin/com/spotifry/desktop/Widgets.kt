package com.spotifry.desktop

import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.spotifry.music.data.NOTE_MAX
import com.spotifry.music.data.Settings
import com.spotifry.music.data.cleanNote
import com.spotifry.music.data.cutName
import com.spotifry.music.data.db.SongNoteEntity
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.data.speedPitchLabel
import com.spotifry.music.player.SleepState
import com.spotifry.music.player.Trim
import com.spotifry.music.player.canEndAt
import com.spotifry.music.player.canStartAt
import com.spotifry.music.player.clipText
import com.spotifry.music.player.clockText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Page heading with a quiet line under it and the page's buttons on the right. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Heading, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1).sp, color = Palette.ink)
            if (subtitle != null) Text(subtitle, color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, primary: Boolean = false, accent: Boolean = false, glass: Boolean = false, onClick: () -> Unit) {
    // accent: filled with the accent colour; glass: a dark see-through fill, for buttons over a picture
    val fill = when { accent -> Palette.accent; primary -> Palette.ink; glass -> Color.Black.copy(alpha = 0.35f); else -> Color.Transparent }
    val ink = when { accent -> Color.White; primary -> Palette.bg; else -> Palette.ink }
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(fill)
            .then(if (primary || accent) Modifier else Modifier.border(1.dp, Palette.ink.copy(alpha = 0.2f), RoundedCornerShape(22.dp))).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = ink)
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ink)
    }
}

/** The window's background and Home's picture, for frosted glass over them (Home's rotation card). */
val LocalBannerHaze = androidx.compose.runtime.compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Frosted glass: what is behind is blurred, then darkened. No fill colour of its own, so in the moment Home's picture
 * comes or goes it never flashes another colour.
 */
@Composable
fun Modifier.frosted(darken: Float = 0.45f): Modifier {
    val haze = LocalBannerHaze.current
    val tint = (if (Palette.dark) Color.Black else Color.White).copy(alpha = if (Palette.dark) darken else 0.6f)
    return if (haze == null) background(tint)
    else hazeEffect(haze) { style = dev.chrisbanes.haze.HazeStyle(backgroundColor = Color.Transparent, tint = dev.chrisbanes.haze.HazeTint(tint), blurRadius = 6.dp) }
}

/**
 * The search bar's and gear's fill: dark and see-through, the same on every page. No blur here: the blur catches up a
 * frame late when Home's picture comes or goes, and on the top row, seen on every page, that showed as a flash.
 */
@Composable
fun Modifier.glassFill(): Modifier = background((if (Palette.dark) Color.Black else Color.White).copy(alpha = if (Palette.dark) 0.45f else 0.6f))

/** A hairline down the left edge: where a side panel meets the page. */
fun Modifier.edgeLine() = drawBehind { drawLine(Palette.ink.copy(alpha = 0.1f), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx()) }

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) Palette.accent.copy(alpha = 0.18f) else Palette.high).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Palette.accent else Palette.ink2) }
}

@Composable
fun SectionTitle(text: String, line: Boolean = true) = Column(Modifier.padding(top = 28.dp, bottom = 8.dp)) {
    Text(text, fontFamily = Heading, fontSize = 22.sp, color = Palette.ink, modifier = Modifier.padding(bottom = if (line) 8.dp else 4.dp))
    if (line) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.ink.copy(alpha = 0.14f)))
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontFamily = Heading, fontSize = 24.sp, color = Palette.ink)
        Text(text, color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Up to four covers in a square, for a playlist. */
@Composable
fun Mosaic(covers: List<String?>, size: Dp, radius: Dp = 14.dp) {
    val shape = RoundedCornerShape(radius)
    Box(Modifier.size(size).clip(shape).background(Palette.high)) {
        val tile = size / 2
        if (covers.size <= 1) Cover(covers.firstOrNull(), size, shape, large = true)
        else Column {
            for (r in 0..1) Row { for (c in 0..1) Cover(covers.getOrNull(r * 2 + c), tile, RoundedCornerShape(0.dp)) }
        }
    }
}

/** A cover with the name under it, the way Home and Playlists show a song or a playlist. */
@Composable
fun CoverCard(title: String, subtitle: String, cover: @Composable (Dp) -> Unit, width: Dp = 180.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hot) 1.04f else 1f, tween(200))
    // no grey box: the cover lifts a little and the name warms up
    Column(Modifier.width(width).hoverable(src).clickable(src, null, onClick = onClick)) {
        Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) { cover(width) }
        Text(title, fontFamily = Display, fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = if (hot) Palette.accent else Palette.ink, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, color = Palette.ink2, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A name typed into a small window: new playlist, rename. */
@Composable
fun NameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text(title, fontFamily = Heading, fontSize = 22.sp) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.onEnter { if (text.isNotBlank()) onDone(text.trim()) },
            )
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) onDone(text.trim()) }) { Text(confirm, color = Palette.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onDone: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Palette.surface, title = { Text(title, fontFamily = Heading, fontSize = 22.sp) }, text = { Text(text, color = Palette.ink2) },
    confirmButton = { TextButton(onDone) { Text(confirm, color = Palette.danger) } },
    dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
)

/**
 * A song in a list: number, cover, name, length, and a menu. [extra] adds entries to the menu (Remove from this playlist).
 */
@Composable
fun SongLine(services: Services, number: Int?, s: Song, playing: Boolean, saved: Boolean = false, extra: @Composable (close: () -> Unit) -> Unit = {}, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) Text(number.toString(), fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Palette.ink.copy(alpha = 0.4f), fontSize = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(36.dp))
        Cover(s.thumbnailUrl ?: s.localThumbPath, 44.dp, RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, fontFamily = Display, fontSize = 18.sp, maxLines = 1, color = if (playing) Palette.accent else Palette.ink, modifier = Modifier.weight(1f, fill = false))
                if (saved) Icon(Icons.Saved, "Saved on this computer", Modifier.size(15.dp), tint = Palette.accent)
            }
            Text(s.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
        }
        TimeText(clock(s.durationSec * 1000), Palette.ink2, 13.sp)
        Box {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { Icon(Icons.More, "More", Modifier.size(20.dp), tint = Palette.ink2) }
            if (menu) SongMenu(services, s, { menu = false }, extra)
        }
    }
}

/** The ⋮ menu of a song. The sleep timer, speed and pitch and the trim show only for the song that plays. */
@Composable
fun SongMenu(services: Services, song: Song, close: () -> Unit, extra: @Composable (() -> Unit) -> Unit) {
    val lib = services.library
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Boolean?>(null) }
    // what the menu shows: its main list, the playlists to add to, or the sleep timer's choices
    var view by remember { mutableStateOf("main") }
    var naming by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    LaunchedEffect(song.id) { saved = lib.db.songs().get(song.id)?.isSaved == true }
    val lists by lib.db.playlists().observePlaylists().collectAsState(emptyList())
    val note by remember(song.id) { lib.db.notes().observe(song.id) }.collectAsState(null)
    val state by services.player.state.collectAsState()
    val playingThis = state.current?.id == song.id
    DropdownMenu(!noting && !tuning, close, containerColor = Palette.surface) {
        when (view) {
            "main" -> {
                // the playing song is already there, and a song already waiting is moved or left alone, never listed twice
                val waiting = (state.currentIndex + 1 until state.queue.size).filter { state.queue[it].id == song.id }
                if (!playingThis) {
                    DropdownMenuItem({ Text("Play next") }, onClick = {
                        if (state.queue.isEmpty()) services.player.playList(listOf(song), 0)
                        else { waiting.sortedDescending().forEach { services.player.remove(it, song.id) }; services.player.insert(state.currentIndex + 1, song, null) }
                        close()
                    })
                    if (waiting.isEmpty()) DropdownMenuItem({ Text("Add to queue") }, onClick = {
                        if (state.queue.isEmpty()) services.player.playList(listOf(song), 0) else services.player.insert(state.queue.size, song, null)
                        close()
                    })
                }
                if (!song.isLocal) DropdownMenuItem({ Text(if (saved) "Remove from saved" else "Save on this computer") }, onClick = {
                    scope.launch { if (saved) lib.downloads.remove(song.id) else lib.downloads.save(song) }
                    close()
                })
                // a saved song as a plain file in Music/Spotifry; the label says how it went, since the menu stays open
                if (saved && !song.isLocal) DropdownMenuItem({ Text(when (exported) { null -> "Copy to Music folder"; true -> "Copied to Music/Spotifry"; false -> "Couldn't copy it" }) }, onClick = {
                    if (exported == null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val file = lib.db.songs().get(song.id)?.filePath?.let { java.io.File(it) }
                        exported = file != null && exportToMusic(Platform.userFolder("Music"), file, song.title, song.artist) != null
                        // On this computer is read at start and when Saved opens: read it again now so the copy shows without leaving the page
                        if (exported == true) lib.musicFolder.refresh()
                    }
                })
                DropdownMenuItem({ Text("Add to a playlist") }, onClick = { view = "lists" })
                // the link (the phone's words, which Spotifry reads back in Import) or a picture
                DropdownMenuItem({ Text("Share…") }, onClick = { services.sharing = ShareTarget.of(song); close() })
                DropdownMenuItem({ Text(if (note == null) "Add a note" else "Edit note") }, onClick = { noting = true })
                extra(close)
                // a Smart shuffle suggestion: out of the queue (the playing one skips on) and never suggested again
                val tip = state.queue.indexOfFirst { it.id == song.id && it.suggested }
                if (song.suggested && tip >= 0) DropdownMenuItem({ Text("Not this song") }, onClick = {
                    lib.setNotSuggested(song.id, true); services.player.remove(tip, song.id); close()
                })
                if (playingThis) PlayingItems(services, state, { view = "sleep" }, { tuning = true }, close)
            }
            "lists" -> {
                DropdownMenuItem({ Text("New playlist…", color = Palette.accent) }, onClick = { naming = true })
                lists.forEach { p ->
                    DropdownMenuItem({ Text(p.name) }, onClick = {
                        scope.launch { lib.db.songs().upsert(song.toEntity()); lib.db.playlists().addSong(p.id, song.id) }
                        close()
                    })
                }
            }
            "sleep" -> services.sleep?.let { SleepChoices(it, close) }
        }
    }
    if (naming) NameDialog("New playlist", "Create", "", { naming = false }) { name ->
        scope.launch { val id = lib.db.playlists().create(name); lib.db.songs().upsert(song.toEntity()); lib.db.playlists().addSong(id, song.id) }
        naming = false; close()
    }
    if (noting) NoteDialog(services, song.id, note.orEmpty(), close)
    if (tuning) SpeedPitchDialog(lib.settings, close)
}

/** The playing song's own entries: sleep timer, speed and pitch, and where it starts and ends. */
@Composable
private fun PlayingItems(services: Services, state: com.spotifry.music.player.PlayerUiState, onSleep: () -> Unit, onTune: () -> Unit, close: () -> Unit) {
    val player = services.player
    val song = state.current ?: return
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.ink.copy(alpha = 0.1f))
    services.sleep?.let { timer ->
        val sleep by timer.state.collectAsState()
        val left by produceState("", sleep) {
            while (true) {
                value = when (sleep) {
                    is SleepState.Minutes -> " · ${clockText(timer.remainingMs() ?: 0)} left"
                    SleepState.EndOfSong -> " · after this song"
                    SleepState.Off -> ""
                }
                delay(1_000)
            }
        }
        DropdownMenuItem({ Text("Sleep timer$left") }, onClick = onSleep)
    }
    val speed by services.library.settings.speed.collectAsState()
    val pitch by services.library.settings.pitch.collectAsState()
    DropdownMenuItem({ Text("Speed and pitch" + (speedPitchLabel(speed, pitch)?.let { " · $it" } ?: "")) }, onClick = onTune)
    // a point is set where the song is now, so these need it playing; once trimmed, one tap undoes it
    val old = state.trim
    if (old == null) {
        val position by player.position.collectAsState()
        if (canStartAt(position, state.wholeMs)) DropdownMenuItem({ Text("Start this song here · ${clipText(position)}") }, onClick = {
            player.setTrim(song.id, Trim(position)); close()
        })
        if (canEndAt(position)) DropdownMenuItem({ Text("End this song here · ${clipText(position)}") }, onClick = {
            player.setTrim(song.id, Trim(endMs = position), skip = true); close()
        })
    } else DropdownMenuItem({ Text("Play the whole song · now ${old.note(state.wholeMs)}") }, onClick = { player.setTrim(song.id, null); close() })
}

/** The sleep timer's choices, inside the menu. Each one starts over from now. */
@Composable
private fun SleepChoices(timer: SleepTimer, close: () -> Unit) {
    val sleep by timer.state.collectAsState()
    val note by produceState("", sleep) {
        while (true) {
            value = when (sleep) {
                SleepState.Off -> "Music fades out and stops. Play starts again from there."
                is SleepState.Minutes -> "Stops in ${clockText(timer.remainingMs() ?: 0)}. Pick a new time to start over."
                SleepState.EndOfSong -> "Stops after this song. Pick a new time to start over."
            }
            delay(1_000)
        }
    }
    Text(note, color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp))
    if (sleep != SleepState.Off) DropdownMenuItem({ Text("Turn off timer", color = Palette.danger) }, onClick = { timer.off(); close() })
    listOf(15, 30, 45, 60).forEach { m ->
        val on = (sleep as? SleepState.Minutes)?.minutes == m
        DropdownMenuItem({ Text(if (m == 60) "1 hour" else "$m minutes", color = if (on) Palette.accent else Color.Unspecified) }, onClick = { timer.start(m); close() })
    }
    DropdownMenuItem({ Text("End of this song", color = if (sleep == SleepState.EndOfSong) Palette.accent else Color.Unspecified) }, onClick = { timer.startEndOfSong(); close() })
}

/** Writes, changes or deletes the note on a song. Empty saves as no note. */
@Composable
fun NoteDialog(services: Services, songId: String, initial: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    fun save(raw: String) {
        val clean = cleanNote(raw)
        val notes = services.library.db.notes()
        services.scope.launch { if (clean.isEmpty()) notes.delete(songId) else notes.put(SongNoteEntity(songId, clean, System.currentTimeMillis())) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text(if (initial.isEmpty()) "Add a note" else "Edit note", fontFamily = Heading, fontSize = 22.sp) },
        text = {
            OutlinedTextField(
                text, { text = cutName(it.replace('\n', ' '), NOTE_MAX) }, maxLines = 4, placeholder = { Text("What this song means to you") },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.onEnter { save(text) },
            )
        },
        confirmButton = { TextButton({ save(text) }) { Text("Save", color = Palette.accent) } },
        dismissButton = {
            Row {
                if (initial.isNotEmpty()) TextButton({ save("") }) { Text("Delete", color = Palette.danger) }
                TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) }
            }
        },
    )
}

/** Speed (half to double) and pitch (6 steps either way), heard at once and kept for every song until set back. */
@Composable
fun SpeedPitchDialog(settings: Settings, onDismiss: () -> Unit) {
    val speed by settings.speed.collectAsState()
    val pitch by settings.pitch.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text("Speed and pitch", fontFamily = Heading, fontSize = 22.sp) },
        text = {
            Column(Modifier.width(340.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed", color = Palette.ink)
                    Text(speedPitchLabel(speed, 0) ?: "1×", color = Palette.accent, fontWeight = FontWeight.SemiBold)
                }
                // steps of 0.05 between 0.5 and 2
                Track(speed - .5f, 1.5f, Palette.accent, { settings.setSpeed(((it + .5f) * 20).roundToInt() / 20f) }, {}, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(.75f, 1f, 1.25f, 1.5f).forEach { x -> Chip(speedPitchLabel(x, 0) ?: "1×", abs(speed - x) < .001f) { settings.setSpeed(x) } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pitch", color = Palette.ink)
                    Text(speedPitchLabel(1f, pitch) ?: "Normal", color = Palette.accent, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("−", false) { if (pitch > -6) settings.setPitch(pitch - 1) }
                    Text(
                        when { pitch == 0 -> "Normal"; pitch > 0 -> "$pitch ${if (pitch == 1) "step" else "steps"} higher"; else -> "${-pitch} ${if (pitch == -1) "step" else "steps"} lower" },
                        color = Palette.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    Chip("+", false) { if (pitch < 6) settings.setPitch(pitch + 1) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done", color = Palette.accent) } },
        dismissButton = {
            if (speed != 1f || pitch != 0) TextButton({ settings.setSpeed(1f); settings.setPitch(0) }) { Text("Back to normal", color = Palette.ink2) }
        },
    )
}

/** The artist under a song's name; a Smart shuffle suggestion gets the sparkle and says so. */
@Composable
fun Artist(s: Song, size: androidx.compose.ui.unit.TextUnit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (s.suggested) Icon(Icons.Sparkle, null, Modifier.size(11.dp), tint = Palette.accent)
    Text(if (s.suggested) "${s.artist} · suggested for you" else s.artist, color = Palette.ink2, fontSize = size, maxLines = 1)
}

/**
 * A time like 3:52 with every digit in a slot as wide as the widest one, so times in a column line up. Albert Sans has no
 * equal-width digits of its own (no "tnum"); its 4 is the widest, .655 of the size.
 */
@Composable
fun TimeText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit) = Row {
    text.forEach { c ->
        if (c.isDigit()) Box(Modifier.width((size.value * .66f).dp), contentAlignment = Alignment.Center) { Text(c.toString(), color = color, fontSize = size, maxLines = 1, softWrap = false) }
        else Text(c.toString(), color = color, fontSize = size)
    }
}
