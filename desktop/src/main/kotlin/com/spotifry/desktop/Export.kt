package com.spotifry.desktop

import java.io.File
import java.nio.ByteBuffer
import java.util.Base64

/** "Artist - Title.ext", safe as a file name on Linux and Windows. */
fun exportName(title: String, artist: String, ext: String): String {
    val by = artist.removeSuffix(" - Topic").trim()
    return (if (by.isEmpty()) title else "$by - $title").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim() + ".$ext"
}

/** YouTube's audio comes as .webm, which music players and the On this computer list don't take; its Opus sound is the same as in a plain .opus file. */
internal fun exportExtension(ext: String) = if (ext.equals("webm", true)) "opus" else ext.lowercase()

/** A picture as the FLAC picture block that Ogg files keep (base64) in their METADATA_BLOCK_PICTURE tag; null when it is not a JPEG or PNG. */
internal fun pictureBlock(image: ByteArray): String? {
    val mime = when {
        image.size > 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() -> "image/jpeg"
        image.size > 4 && image[1] == 'P'.code.toByte() && image[2] == 'N'.code.toByte() && image[3] == 'G'.code.toByte() -> "image/png"
        else -> return null
    }
    val m = mime.toByteArray()
    val b = ByteBuffer.allocate(4 + 4 + m.size + 4 + 16 + 4 + image.size)
    b.putInt(3).putInt(m.size).put(m).putInt(0).putInt(0).putInt(0).putInt(24).putInt(0).putInt(image.size).put(image)
    return Base64.getEncoder().encodeToString(b.array())
}

/**
 * Copies a saved song into [music]/Spotifry, where other players and file managers see it. A .webm becomes .opus, and
 * when ffmpeg is there the file also gets its title, artist and [cover] inside it, so it shows with its picture in the
 * On this computer list. Without ffmpeg, or if it fails, the file is copied as it is. A song already copied is not
 * copied again, and a file is never overwritten. Null when the copy could not be made. Run off the window's thread.
 */
fun exportToMusic(music: File, saved: File, title: String, artist: String, cover: File? = null, ffmpeg: String? = Platform.find("ffmpeg")): File? {
    if (!saved.isFile) return null
    val dir = File(music, "Spotifry")
    return try {
        dir.mkdirs()
        val tagged = if (ffmpeg != null) tagged(ffmpeg, saved, title, artist, cover, dir) else null
        val ext = if (tagged != null) exportExtension(saved.extension) else saved.extension
        val target = File(dir, exportName(title, artist, ext))
        // ponytail: the same artist and title counts as the same song; a byte-by-byte check is not worth it here
        if (target.exists()) { tagged?.delete(); return target }
        // written under another name first, so a copy cut short never looks like a finished song
        val part = tagged ?: File(dir, "${target.name}.part").also { saved.copyTo(it, overwrite = true) }
        if (part.renameTo(target)) target else { part.delete(); null }
    } catch (_: java.io.IOException) { null }
}

/** [saved] rewritten by ffmpeg with its tags and picture (still the same sound, nothing is re-encoded) into a temporary file in [dir], or null when ffmpeg could not do it. */
private fun tagged(ffmpeg: String, saved: File, title: String, artist: String, cover: File?, dir: File): File? {
    val ext = exportExtension(saved.extension)
    val format = when (ext) { "flac" -> "flac"; "opus" -> "opus"; "mp3" -> "mp3"; "m4a" -> "mp4"; else -> return null }
    val out = File(dir, "export-${System.nanoTime()}.part")
    val image = cover?.takeIf { it.isFile && it.length() < 8_000_000 }?.readBytes()
    val meta = File(dir, "${out.name}.meta")
    try {
        val args = mutableListOf(ffmpeg, "-v", "error", "-y", "-i", saved.path)
        if (format == "opus") {
            // an Ogg file keeps its picture as a tag; the text is long, so it goes in through a file, not the command line
            val picture = image?.let(::pictureBlock)
            meta.writeText(buildString { appendLine(";FFMETADATA1"); appendLine("title=${esc(title)}"); appendLine("artist=${esc(artist)}"); if (picture != null) appendLine("METADATA_BLOCK_PICTURE=$picture") })
            args += listOf("-f", "ffmetadata", "-i", meta.path, "-map", "0:a", "-map_metadata", "1")
        } else if (image != null) {
            val img = File(dir, "${out.name}.img").apply { writeBytes(image) }
            try { return run(args + listOf("-i", img.path, "-map", "0:a", "-map", "1", "-disposition:v", "attached_pic") + tags(title, artist), format, out) }
            finally { img.delete() }
        } else args += listOf("-map", "0:a")
        if (format != "opus") args += tags(title, artist)
        return run(args, format, out)
    } finally { meta.delete() }
}

private fun esc(s: String) = s.replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", "\\\n")
private fun tags(title: String, artist: String) = listOf("-metadata", "title=$title", "-metadata", "artist=${artist.removeSuffix(" - Topic")}")

private fun run(args: List<String>, format: String, out: File): File? = try {
    val p = ProcessBuilder(args + listOf("-c", "copy", "-f", format, out.path)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if (p.waitFor() == 0 && out.length() > 0) out else { out.delete(); null }
} catch (_: java.io.IOException) { out.delete(); null }
