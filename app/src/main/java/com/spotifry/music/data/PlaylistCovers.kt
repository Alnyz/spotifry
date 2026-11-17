package com.spotifry.music.data

import android.graphics.Bitmap
import java.io.File
import kotlin.math.max

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
