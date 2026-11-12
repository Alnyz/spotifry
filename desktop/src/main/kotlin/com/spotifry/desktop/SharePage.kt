package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.ShareText
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.artAt
import com.spotifry.music.data.source.isLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** What a picture or a message is made of: one song, or a playlist (its name, "N songs" and its songs). */
data class ShareTarget(val title: String, val subtitle: String, val songs: List<Song>, val playlist: Boolean = false) {
    companion object {
        fun of(song: Song) = ShareTarget(song.title, song.artist, listOf(song))
        fun of(name: String, songs: List<Song>) = ShareTarget(name, "${songs.size} ${if (songs.size == 1) "song" else "songs"}", songs, playlist = true)
    }
}

enum class CardBackground(val label: String) { COVER("From cover"), ACCENT("Your colour"), BLACK("Black") }

/** The colours of one picture: background from [top] to [bottom], [text], the artist line in [soft], the mark's [bars]. */
internal data class CardInk(val top: Color, val bottom: Color, val text: Color, val soft: Color, val bars: Color)

private val DarkInk = Color(0xFF15181C)
private fun contrast(a: Color, b: Color): Float { val x = a.luminance(); val y = b.luminance(); return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f) }
private fun onAccent(c: Color) = if (contrast(c, Color.White) >= contrast(c, DarkInk)) Color.White else DarkInk

/** The phone's cardInk. [tint] is the cover's colour, [accent] the accent's dark-theme shade. */
internal fun cardInk(bg: CardBackground, tint: Color?, accent: Color): CardInk = when (bg) {
    CardBackground.COVER -> {
        // deep enough for white text to read on
        var c = tint?.let { lerp(it, Color.Black, .3f) } ?: Color(0xFF22262E)
        while (contrast(Color.White, c) < 4.5f) c = lerp(c, Color.Black, .08f)
        CardInk(c, lerp(c, Color.Black, .45f), Color.White, Color.White.copy(alpha = .8f), Color.White)
    }
    CardBackground.ACCENT -> {
        var a = accent
        while (contrast(onAccent(a), a) < 4.5f) a = lerp(a, Color.Black, .08f)
        onAccent(a).let { CardInk(a, a, it, it.copy(alpha = .78f), it) }
    }
    CardBackground.BLACK -> CardInk(Color(0xFF0C0D10), Color(0xFF0C0D10), Color.White, Color(0xFFA6ADB6), accent)
}

/** The picture's width in dp; it is saved at three times, 1080 px, like the phone's. */
private const val PIC_W = 360
private val EM = 18.dp // a twentieth of the width: the phone's sizes are in these

/**
 * The phone's share picture (drawCard), drawn by Compose: 360 by 640 for a story, 360 square otherwise. The mark at the
 * top, the lyric line in the room left (smaller until it fits), the cover and names at the bottom.
 */
@Composable
internal fun ShareCard(t: ShareTarget, line: String?, story: Boolean, ink: CardInk) {
    val side = EM * (if (story) 1.6f else 1.5f)
    val top = EM * (if (story) 1.7f else 1.5f)
    val urls = t.songs.take(4).map { it.thumbnailUrl ?: it.localThumbPath }
    Column(
        Modifier.size(PIC_W.dp, (if (story) 640 else PIC_W).dp).background(Brush.verticalGradient(listOf(ink.top, ink.bottom)))
            .padding(start = side, end = side, top = top, bottom = EM * 1.5f),
    ) {
        Watermark(text = ink.text, bars = ink.bars)
        // the lyric line takes what the art and names leave
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (line != null && !t.playlist) Lyric(line, story, ink.text)
        }
        if (story) {
            Art(urls, EM * 14.5f, ink, Modifier.align(Alignment.CenterHorizontally))
            Text(t.title, Modifier.padding(top = EM * 1.1f), ink.text, fontFamily = Albert, fontWeight = FontWeight.Bold, fontSize = (18 * 1.25f).sp, lineHeight = (18 * 1.25f * 1.2f).sp, letterSpacing = (-.02).em, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, color = ink.soft, fontFamily = Albert, fontSize = (18 * .92f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Row(verticalAlignment = Alignment.Bottom) {
            Art(urls, EM * (if (t.playlist) 9f else 7.6f), ink)
            Column(Modifier.padding(start = EM * 1.1f)) {
                val big = if (t.playlist) 1.5f else 1.08f
                Text(t.title, color = ink.text, fontFamily = Albert, fontWeight = FontWeight.Bold, fontSize = (18 * big).sp, lineHeight = (18 * big * (if (t.playlist) 1.1f else 1.2f)).sp, letterSpacing = (-.02).em, maxLines = if (t.playlist) 3 else 2, overflow = TextOverflow.Ellipsis)
                Text(t.subtitle, color = ink.soft, fontFamily = Albert, fontSize = (18 * .84f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The line, starting big and getting smaller (8 % a step) until it fits its room without being cut. */
@Composable
private fun Lyric(line: String, story: Boolean, color: Color) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun style(k: Float) = TextStyle(fontFamily = Albert, fontWeight = FontWeight.Bold, fontSize = (18 * k).sp, lineHeight = (18 * k * 1.14f).sp, letterSpacing = (-.025).em, lineBreak = LineBreak.Paragraph)
    fun gap(k: Float) = with(density) { (EM * (if (story) 1.5f else 1.1f) * k).roundToPx() }
    val k = remember(line, story, constraints) {
        generateSequence(if (story) 1.62f else 1.36f) { it * .92f }.first { k ->
            val room = constraints.maxHeight - gap(k) - with(density) { (EM * .8f).roundToPx() }
            k < .8f || measurer.measure(line, style(k), constraints = Constraints(maxWidth = constraints.maxWidth)).size.height <= room
        }
    }
    val gap = gap(k)
    Text(line, Modifier.padding(top = with(density) { gap.toDp() }), color, style = style(k), overflow = TextOverflow.Ellipsis)
}

/** One cover, or four in a 2 by 2 grid, rounded, with a soft shadow; no cover is a plain tile. */
@Composable
private fun Art(urls: List<String?>, size: Dp, ink: CardInk, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(EM * .7f)
    // a solid base first: the shadow would show through a see-through tile
    Box(modifier.size(size).shadow(EM * .8f, shape).clip(shape).background(ink.top).background(ink.text.copy(alpha = .14f))) {
        if (urls.size >= 4) Column(verticalArrangement = Arrangement.spacedBy(EM * .25f)) {
            val half = (size - EM * .25f) / 2
            for (r in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(EM * .25f)) {
                for (c in 0..1) StoryCover(urls[r * 2 + c], Modifier.size(half), RoundedCornerShape(0.dp))
            }
        } else StoryCover(urls.firstOrNull(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
    }
}

/** [t]'s picture as PNG bytes, 1080 wide. Covers are fetched first, so the picture has them. */
internal suspend fun picturePng(t: ShareTarget, line: String?, story: Boolean, ink: CardInk): ByteArray = withContext(Dispatchers.IO) {
    t.songs.take(4).forEach { s -> (s.thumbnailUrl ?: s.localThumbPath)?.let { Covers.load(artAt(it, true)) } }
    val scene = ImageComposeScene(PIC_W * 3, (if (story) 640 else PIC_W) * 3, Density(3f, 1f)) { ShareCard(t, line, story, ink) }
    try {
        var at = 0L
        repeat(6) { scene.render(at); at += 16_000_000; Thread.sleep(30) }
        scene.render(at).encodeToData(EncodedImageFormat.PNG)!!.bytes
    } finally { scene.close() }
}

/** Puts a PNG on the clipboard as a picture, so it can be pasted into a chat. */
private fun copyPicture(png: ByteArray) {
    val image = javax.imageio.ImageIO.read(png.inputStream())
    val pick = object : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) = f == java.awt.datatransfer.DataFlavor.imageFlavor
        override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any = image
    }
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(pick, null)
}

/** "<title>.png" under Pictures/Spotifry, with " (2)" and up when the name is taken. */
private fun pictureFile(title: String): File {
    val dir = File(picturesDir(), "Spotifry").apply { mkdirs() }
    val base = title.replace(Regex("[/\\\\:*?\"<>|]"), " ").trim().ifEmpty { "Spotifry" }.take(80)
    return generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) "$base.png" else "$base ($n).png") }.first { !it.exists() }
}

/**
 * Share, from a song's or a playlist's menu: copy the words and link, or make a picture. Nothing is sent from here; the
 * computer has no share list, so it is copied or saved and sent by the listener.
 */
@Composable
fun ShareDialog(services: Services, onPicture: (ShareTarget) -> Unit) {
    val t = services.sharing ?: return
    val close = { services.sharing = null }
    val text = remember(t) { if (t.playlist) ShareText.playlist(t.title, t.songs) else if (t.songs.first().isLocal) null else ShareText.song(t.songs.first()) }
    var copied by remember(t) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = close, containerColor = Palette.surface,
        title = { Text("Share “${t.title}”", fontFamily = Display, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nothing is sent from here. It is copied or saved, and you send it yourself.", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                when {
                    text == null -> Option(Icons.Queue, "Copy link", "This song is a file on this computer, so there's no link.", enabled = false) {}
                    else -> {
                        Option(
                            Icons.Queue, if (copied) "Copied" else if (t.playlist) "Copy the song list" else "Copy link",
                            if (t.playlist) "Each song with its YouTube link. A friend with Spotifry can turn it back into this playlist."
                            else "The song's name and its YouTube link. Anyone can open it, with or without Spotifry.",
                        ) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); copied = true }
                        Text(
                            text, maxLines = if (t.playlist) 6 else 3, overflow = TextOverflow.Ellipsis, color = Palette.ink2, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 62.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.high).padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                Option(
                    Icons.Sparkle, "Make a picture",
                    if (t.playlist) "The covers and the name, for a story. Shows the playlist, doesn't carry the songs." else "Cover, name and one line of the lyrics, for a story or a chat.",
                    Modifier.padding(top = 6.dp),
                ) { close(); onPicture(t) }
            }
        },
        confirmButton = { TextButton(close) { Text("Close", color = Palette.ink2) } },
    )
}

@Composable
private fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled, onClick = onClick).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.high), Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) Palette.accent else Palette.ink2)
        }
        Column {
            Text(title, color = if (enabled) (if (title == "Copied") Palette.accent else Palette.ink) else Palette.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Palette.ink2, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * Make a picture: the picture as it will be saved on the left, its shape, background and (songs only) lyric line on the
 * right. The line sung right now is picked first when the song is playing.
 */
@Composable
fun PicturePage(services: Services, t: ShareTarget, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var story by remember(t) { mutableStateOf(!t.playlist) }
    var bg by remember(t) { mutableStateOf(CardBackground.COVER) }
    var line by remember(t) { mutableStateOf<String?>(null) }
    var message by remember(t) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val tint by produceState<Color?>(null, t) {
        value = t.songs.firstOrNull()?.let { s -> (s.thumbnailUrl ?: s.localThumbPath)?.let { Covers.load(artAt(it, false))?.tint() } }
    }
    val lines by produceState<List<String>?>(null, t) {
        if (t.playlist) { value = emptyList(); return@produceState }
        val song = t.songs.first()
        val l = runCatching { services.lyrics(song) }.getOrNull()
        val all = when (l) { is Lyrics.Timed -> l.lines.map { it.text }; is Lyrics.Plain -> l.lines; null -> emptyList() }
        val clean = all.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val playing = services.player.state.value.let { it.current?.id == song.id && it.isPlaying }
        line = (if (playing && l is Lyrics.Timed) l.lines.lastOrNull { it.atMs <= services.player.position.value && it.text.isNotBlank() }?.text?.trim() else null) ?: clean.firstOrNull()
        value = clean
    }
    val ink = cardInk(bg, tint, Palette.accentChoice.dark)

    fun make(after: (ByteArray) -> String) {
        if (working) return
        working = true; message = null
        scope.launch {
            message = try { after(picturePng(t, line, story, ink)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "Couldn't make the picture. Try again." }
            working = false
        }
    }

    Column(modifier) {
        Text("‹ Back", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 16.dp + LocalBottomInset.current), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // the picture itself at 360 dp wide, scaled to the room
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val h = if (story) 640 else PIC_W
                val k = minOf(maxWidth.value / PIC_W, maxHeight.value / h)
                Box(Modifier.size((PIC_W * k).dp, (h * k).dp).clip(RoundedCornerShape((14 * k).dp))) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density * k, 1f)) { ShareCard(t, line, story, ink) }
                }
            }
            Column(Modifier.width(380.dp).fillMaxHeight()) {
                Kicker("Make a picture")
                Text(t.title, fontFamily = Display, fontSize = 28.sp, lineHeight = 32.sp, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(t.subtitle, color = Palette.ink2, fontSize = 15.sp, maxLines = 1)
                if (!t.playlist) {
                    Label("Shape")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Story", story) { story = true }
                        Chip("Square", !story) { story = false }
                    }
                }
                Label("Background")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardBackground.entries.forEach { b ->
                        val swatch = cardInk(b, tint, Palette.accentChoice.dark)
                        val on = b == bg
                        // no hover shade: the ring already shows the choice
                        Column(Modifier.weight(1f).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { bg = b }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().height(40.dp).border(2.dp, if (on) Palette.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                    .padding(if (on) 4.dp else 0.dp).clip(RoundedCornerShape(if (on) 8.dp else 12.dp)).background(Brush.verticalGradient(listOf(swatch.top, swatch.bottom))),
                            )
                            Text(b.label, color = if (on) Palette.ink else Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
                // no lyrics found: no list, the picture is the cover and the name
                val all = lines
                if (!all.isNullOrEmpty()) {
                    Label("Lyric line")
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(Palette.ink.copy(alpha = .04f))) {
                        items(listOf<String?>(null) + all) { l ->
                            val on = l == line
                            Text(
                                l ?: "No line, just the cover", color = if (on) Palette.accent else if (l == null) Palette.ink2 else Palette.ink,
                                fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().background(if (on) Palette.accent.copy(alpha = .12f) else Color.Transparent).clickable { line = l }.padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                } else if (all == null) Text("Looking for the lyrics…", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (working) "Making…" else "Save picture", Icons.Download, primary = true) {
                        make { png -> pictureFile(t.title).also { it.writeBytes(png) }.let { "Saved as ${it.path.replaceFirst(System.getProperty("user.home"), "~")}" } }
                    }
                    Pill("Copy picture") { make { png -> copyPicture(png); "Copied. Paste it into a chat." } }
                }
                message?.let { Text(it, color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = Palette.ink2, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
