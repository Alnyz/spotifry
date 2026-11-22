package com.spotifry.music.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** Logo pictures for playlist and artist names: PNGs in [dir], at most [MAX_WIDTH] px wide. */
class NameLogos(private val dir: File) {
    fun file(name: String?): File? = name?.takeIf(::isPlainFileName)?.let { File(dir, it) }?.takeIf { it.isFile }

    /** Saves the picture at [uri] as a PNG and returns its name; null when it can't be read. An SVG is drawn once, here. */
    suspend fun import(files: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bmp = (if (isSvg(files, uri)) svgBitmap(files, uri) else decodePicked(files, uri, maxSide = 2400) ?: plainBitmap(files, uri)) ?: return@withContext null
        val fitted = if (bmp.width > MAX_WIDTH) Bitmap.createScaledBitmap(bmp, MAX_WIDTH, (bmp.height * MAX_WIDTH / bmp.width).coerceAtLeast(1), true) else bmp
        val name = "${System.currentTimeMillis()}-${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}.png"
        runCatching {
            dir.mkdirs()
            File(dir, name).outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name
        }.getOrElse { File(dir, name).delete(); null }
    }

    fun delete(name: String?) { file(name)?.delete() }

    private fun isSvg(files: ContentResolver, uri: Uri) =
        files.getType(uri) == "image/svg+xml" || uri.lastPathSegment.orEmpty().endsWith(".svg", ignoreCase = true)

    /** For pictures the newer decoder refuses; logos are small, so no sampling. */
    private fun plainBitmap(files: ContentResolver, uri: Uri): Bitmap? = runCatching { files.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun svgBitmap(files: ContentResolver, uri: Uri): Bitmap? = runCatching {
        val svg = files.openInputStream(uri)!!.use { SVG.getFromInputStream(it) }
        val box = svg.documentViewBox
        val (w, h) = if (box != null && box.width() > 0 && box.height() > 0) box.width() to box.height() else 1200f to 472f
        val outH = (h * MAX_WIDTH / w).toInt().coerceIn(1, 4 * MAX_WIDTH)
        svg.setDocumentWidth(MAX_WIDTH.toFloat()); svg.setDocumentHeight(outH.toFloat())
        Bitmap.createBitmap(MAX_WIDTH, outH, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }.getOrNull()

    companion object { const val MAX_WIDTH = 1200 }
}
