package com.spotifry.desktop

import com.spotifry.music.data.CropBox
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/** The pictures chosen as playlist covers: square JPEGs in [dir], named in the playlist's `cover` field, as on the phone. */
class PlaylistCovers(val dir: File) {
    /** The file for a cover name, or null when there is none (default cover) or the file is gone. */
    fun file(name: String?): File? = name?.let { File(dir, it) }?.takeIf { it.isFile }

    /** Keeps the [box] of [picture], at most [maxSide] px, as playlist [id]'s cover and returns the name to store. Never overwrites, so Undo can bring the old one back. */
    fun save(id: Long, picture: Image, box: CropBox, maxSide: Int = 800): String {
        dir.mkdirs()
        val side = minOf(box.size, maxSide)
        val surface = Surface.makeRasterN32Premul(side, side)
        surface.canvas.drawImageRect(picture, Rect.makeXYWH(box.x.toFloat(), box.y.toFloat(), box.size.toFloat(), box.size.toFloat()), Rect.makeWH(side.toFloat(), side.toFloat()))
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
        val name = "$id-${System.currentTimeMillis()}.jpg"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun delete(name: String?) { name?.let { File(dir, it).delete() } }
}
