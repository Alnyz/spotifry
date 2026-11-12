package com.spotifry.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.spotifry.music.data.source.artAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Covers fetched once and kept for the session. */
object Covers {
    lateinit var client: OkHttpClient
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(url: String): ImageBitmap? = cache[url] ?: withContext(Dispatchers.IO) {
        runCatching {
            // a song from the music folder: its picture is read from the disk
            val bytes = if (url.startsWith("file://")) MusicFolder.art(url.removePrefix("file://"))!!
            else client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }
            ImageIO.read(bytes.inputStream())?.let(::trimBars)?.toComposeImageBitmap()
                ?: org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()?.also { cache[url] = it }
    }
}

/**
 * The picture without the black bars a video's picture often has baked in (a wide film in a 16:9 frame), so a square
 * cover shows the picture and not a band. A row or column counts as a bar when every pixel in it is near black.
 */
internal fun trimBars(img: BufferedImage): BufferedImage {
    fun dark(x: Int, y: Int) = img.getRGB(x, y).let { maxOf(it shr 16 and 255, it shr 8 and 255, it and 255) < 28 }
    fun row(y: Int) = (0 until img.width step 4).all { dark(it, y) }
    fun col(x: Int) = (0 until img.height step 4).all { dark(x, it) }
    var top = 0; var bottom = img.height; var left = 0; var right = img.width
    while (top < bottom - 1 && row(top)) top++
    while (bottom - 1 > top && row(bottom - 1)) bottom--
    while (left < right - 1 && col(left)) left++
    while (right - 1 > left && col(right - 1)) right--
    // an all-dark picture, or one trimmed to a sliver, is left whole
    if (bottom - top < img.height * 0.3 || right - left < img.width * 0.3) return img
    return if (top == 0 && left == 0 && bottom == img.height && right == img.width) img else img.getSubimage(left, top, right - left, bottom - top)
}

/** A cover, or a plain square while it loads or when there is none. */
@Composable
fun Cover(url: String?, size: Dp, shape: Shape, large: Boolean = false, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { Covers.load(artAt(it, large)) } }
    Box(modifier.size(size).clip(shape).background(Palette.high)) {
        image?.let { Image(it, null, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}
