package com.spotifry.music.data

import android.graphics.Bitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** The pictures users chose as playlist covers: square JPEGs in [dir], named in the playlist's `cover` field. */
class PlaylistCovers(private val dir: File) {
    /** The file for a cover name, or null when there is none (default cover) or the file is gone. */
    fun file(name: String?): File? = name?.let { File(dir, it) }?.takeIf { it.exists() }

    /** Keeps [square] as the cover of playlist [id] and returns the name to store. Never overwrites an older file, so Undo can bring it back. */
    fun save(id: Long, square: Bitmap): String {
        dir.mkdirs()
        val name = "$id-${System.currentTimeMillis()}.jpg"
        File(dir, name).outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return name
    }

    fun delete(name: String?) { name?.let { File(dir, it).delete() } }
}

/** Where the square cover window sits on a picture, in the picture's own pixels. */
data class CropBox(val x: Int, val y: Int, val size: Int)

/**
 * The square window over a [w] by [h] picture. [zoom] is 1 when the picture just fills the window and grows from there;
 * [panX] and [panY] are how far the picture was dragged, as a share of what it can move (-1 to 1, 0 is centred).
 */
fun cropBox(w: Int, h: Int, zoom: Float, panX: Float, panY: Float): CropBox {
    val z = max(zoom, 1f)
    val size = (min(w, h) / z).toInt().coerceAtLeast(1)
    val x = ((w - size) / 2f * (1f + panX.coerceIn(-1f, 1f))).toInt().coerceIn(0, w - size)
    val y = ((h - size) / 2f * (1f + panY.coerceIn(-1f, 1f))).toInt().coerceIn(0, h - size)
    return CropBox(x, y, size)
}

/** The cropped square, shrunk to [maxSide] px if bigger. */
fun Bitmap.cropped(box: CropBox, maxSide: Int = 800): Bitmap {
    val square = Bitmap.createBitmap(this, box.x, box.y, box.size, box.size)
    return if (box.size > maxSide) Bitmap.createScaledBitmap(square, maxSide, maxSide, true) else square
}

/** A picture the user picked from the gallery, turned the right way up and shrunk to at most [maxSide] px on its long side. Null when it can't be read. */
suspend fun decodePicked(files: android.content.ContentResolver, uri: android.net.Uri, maxSide: Int = 2400): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(files, uri)) { dec, info, _ ->
                dec.setTargetSampleSize((max(info.size.width, info.size.height) / maxSide).coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            files.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = (max(bounds.outWidth, bounds.outHeight) / maxSide).coerceAtLeast(1) }
            files.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()
}
