package com.spotifry.music.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.spotifry.music.R
import com.spotifry.music.ui.theme.contrast
import com.spotifry.music.ui.theme.onAccent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CardBackground(val label: String) { COVER("From cover"), ACCENT("Your colour"), BLACK("Black") }

/** The colours of one card: background from [top] to [bottom], [text], the artist line in [soft], the watermark's [bars]. */
internal data class CardInk(val top: Color, val bottom: Color, val text: Color, val soft: Color, val bars: Color)

private val Black = Color(0xFF0C0D10)

/** [tint] is the cover's colour (already dark enough for white text), [accent] the accent's dark-theme shade. */
internal fun cardInk(bg: CardBackground, tint: Color?, accent: Color): CardInk = when (bg) {
    CardBackground.COVER -> (tint ?: Color(0xFF22262E)).let { CardInk(it, lerp(it, Color.Black, .45f), Color.White, Color.White.copy(alpha = .8f), Color.White) }
    // the accent is bright in the dark theme, so the text turns dark when white would not read on it
    CardBackground.ACCENT -> {
        // a mid-tone (some picked colours) where neither white nor dark text reads: deepen it until white does
        var a = accent
        while (contrast(onAccent(a), a) < 4.5f) a = lerp(a, Color.Black, .08f)
        onAccent(a).let { CardInk(a, a, it, it.copy(alpha = .78f), it) }
    }
    CardBackground.BLACK -> CardInk(Black, Black, Color.White, Color(0xFFA6ADB6), accent)
}

/**
 * The picture a song or playlist is shared as, drawn the way design/share.html's `.card` is: 1080 px wide, 1920 tall
 * for a story or 1080 for a square. Sizes are in [em] (a twentieth of the width) so the numbers match the mockup.
 * [covers]: one for a song; up to four for a playlist, shown as a grid when there are four.
 */
fun drawCard(
    context: Context, title: String, subtitle: String, line: String?, covers: List<Bitmap?>, playlist: Boolean,
    story: Boolean, bg: CardBackground, tint: Color?, accent: Color,
): Bitmap {
    val w = 1080f
    val h = if (story) 1920f else 1080f
    val em = w / 20
    val ink = cardInk(bg, tint, accent)
    val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, ink.top.toArgb(), ink.bottom.toArgb(), Shader.TileMode.CLAMP) })

    val font = ResourcesCompat.getFont(context, R.font.albertsans)
    fun paint(size: Float, color: Color, weight: Int = 700, tight: Float = -.02f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = size * em; this.color = color.toArgb(); fontVariationSettings = "'wght' $weight"; letterSpacing = tight
    }
    fun layout(text: String, p: TextPaint, width: Float, maxLines: Int, lineHeight: Float? = null): StaticLayout {
        val natural = p.fontMetrics.let { it.descent - it.ascent }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt())
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
            .setLineSpacing(lineHeight?.let { it * p.textSize - natural } ?: 0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build()
    }
    fun StaticLayout.drawAt(x: Float, y: Float) { c.save(); c.translate(x, y); draw(c); c.restore() }

    val side = if (story) 1.6f * em else 1.5f * em
    val top = if (story) 1.7f * em else 1.5f * em
    val bottom = h - 1.5f * em
    val inner = w - 2 * side

    val wmBottom = drawWatermark(c, font, em, side, top, ink)

    // the art and the names, from the bottom up
    val artTop: Float
    if (story) {
        val artist = layout(subtitle, paint(.92f, ink.soft, 400, 0f), inner, 1)
        val name = layout(title, paint(1.25f, ink.text), inner, 2)
        val nameTop = bottom - artist.height - name.height
        artist.drawAt(side, bottom - artist.height)
        name.drawAt(side, nameTop)
        val size = 14.5f * em
        artTop = nameTop - 1.1f * em - size
        drawArt(c, covers, RectF((w - size) / 2, artTop, (w + size) / 2, artTop + size), em, ink)
    } else {
        val size = (if (playlist) 9f else 7.6f) * em
        artTop = bottom - size
        drawArt(c, covers, RectF(side, artTop, side + size, bottom), em, ink)
        val x = side + size + 1.1f * em
        val artist = layout(subtitle, paint(.84f, ink.soft, 400, 0f), w - side - x, 1)
        val name = layout(title, paint(if (playlist) 1.5f else 1.08f, ink.text), w - side - x, if (playlist) 3 else 2, if (playlist) 1.1f else null)
        artist.drawAt(x, bottom - artist.height)
        name.drawAt(x, bottom - artist.height - name.height)
    }

    // the lyric line in the space left above the art; a long one gets smaller until it fits
    if (line != null && !playlist) {
        var size = if (story) 1.62f else 1.36f
        val lyricTop = wmBottom + (if (story) 1.5f else 1.1f) * size * em
        val room = artTop - .8f * em - lyricTop
        while (true) {
            val p = paint(size, ink.text, tight = -.025f)
            val lines = (room / (1.14f * size * em)).toInt().coerceAtLeast(1)
            val l = layout(line, p, inner, lines, 1.14f)
            if (l.getEllipsisCount(l.lineCount - 1) == 0 || size < .8f) { l.drawAt(side, lyricTop); break }
            size *= .92f
        }
    }
    return out
}

/** The watermark at ([side], [top]): three bars, then the name. Returns its bottom. */
internal fun drawWatermark(c: Canvas, font: Typeface?, em: Float, side: Float, top: Float, ink: CardInk): Float {
    val wm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = .78f * em; color = ink.text.copy(alpha = ink.text.alpha * .88f).toArgb(); fontVariationSettings = "'wght' 700"; letterSpacing = -.01f
    }
    val wmEm = .78f * em
    val fm = wm.fontMetrics
    val mid = top + (fm.descent - fm.ascent) / 2
    val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.bars.copy(alpha = .88f).toArgb() }
    listOf(.55f, 1f, .7f).forEachIndexed { i, k ->
        val x = side + i * .34f * wmEm
        c.drawRoundRect(x, mid + .45f * wmEm - .9f * wmEm * k, x + .2f * wmEm, mid + .45f * wmEm, .1f * wmEm, .1f * wmEm, bars)
    }
    c.drawText("Spotifry", side + 3 * .2f * wmEm + 2 * .14f * wmEm + .4f * wmEm, top - fm.ascent, wm)
    return top + fm.descent - fm.ascent
}

/** One cover, or four in a 2 by 2 grid, with rounded corners and a soft shadow. No cover: a plain tile. */
private fun drawArt(c: Canvas, covers: List<Bitmap?>, r: RectF, em: Float, ink: CardInk) {
    val radius = .7f * em
    c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink.top.toArgb(); setShadowLayer(.8f * em, 0f, .5f * em, android.graphics.Color.argb(110, 0, 0, 0))
    })
    c.save()
    c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
    val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.text.copy(alpha = .14f).toArgb() }
    fun one(b: Bitmap?, d: RectF) {
        if (b == null) { c.drawRect(d, tile); return }
        // cut the middle square out of wide video frames
        val s = minOf(b.width, b.height)
        val src = Rect((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
        c.drawBitmap(b, src, d, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    if (covers.size >= 4) {
        val gap = .25f * em
        val half = (r.width() - gap) / 2
        covers.take(4).forEachIndexed { i, b ->
            val x = r.left + (i % 2) * (half + gap)
            val y = r.top + (i / 2) * (half + gap)
            one(b, RectF(x, y, x + half, y + half))
        }
    } else one(covers.firstOrNull(), r)
    c.restore()
}

/** Hands [card] to Android's share list as a PNG, through the app's file provider. */
suspend fun sharePicture(context: Context, card: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        // one picture kept at a time; a new name each time so apps don't show a copy they cached
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "spotifry-${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** Hands [text] to Android's share list. */
fun sendText(context: Context, text: String) =
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
