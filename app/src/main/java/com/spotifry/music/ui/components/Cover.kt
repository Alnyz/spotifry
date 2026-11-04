package com.spotifry.music.ui.components

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.source.artAt
import com.spotifry.music.ui.theme.placeholderTones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Set once at the app root so covers can be fetched. Absent in tests, where the striped fallback shows. */
val LocalImageClient = staticCompositionLocalOf<OkHttpClient?> { null }

// sized in bytes: about 30 big covers or a few hundred row covers
private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Forgets the covers held in memory (Settings > clear covers); they load again when next shown. */
fun clearCoverMemory() = cache.evictAll()

@Composable
fun Cover(seed: String, thumbnailUrl: String?, localPath: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 4.dp, shape: Shape? = null) =
    CoverArt(seed, thumbnailUrl, localPath, modifier.size(size), shape ?: RoundedCornerShape(radius), large = size > 96.dp)

/** Same as [Cover], but takes whatever size [modifier] gives it (banners, grid tiles). */
@Composable
fun CoverArt(seed: String, thumbnailUrl: String?, localPath: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(0.dp), large: Boolean = true) {
    val client = LocalImageClient.current
    val files = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val image by produceState(cached(thumbnailUrl, localPath, large), thumbnailUrl, localPath, client, large) {
        // a new song: drop the previous cover at once rather than showing it until the new one arrives
        value = cached(thumbnailUrl, localPath, large)
        if (value == null) value = loadRetrying(client, thumbnailUrl, localPath, large, files)
    }
    Box(modifier.clip(shape)) {
        val img = image
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else if (isFileArt(thumbnailUrl)) NoCover(Modifier.matchParentSize())
        else Box(Modifier.matchParentSize().background(stripes(seed)))
    }
}

/** A music file with no cover in it: a plain note. */
@Composable
private fun NoCover(modifier: Modifier) = Box(modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = androidx.compose.ui.Alignment.Center) {
    androidx.compose.material3.Icon(com.spotifry.music.ui.Icons.Note, null, Modifier.fillMaxSize(.42f), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Album art Android pulls out of a music file on the phone. */
private fun isFileArt(url: String?) = url?.startsWith("content://") == true

/** Placeholder art for a song with no cover: a quiet two-tone gradient, picked by song id. */
private fun stripes(seed: String): Brush {
    val h = seed.hashCode().let { if (it < 0) -it else it }
    val a = placeholderTones[h % placeholderTones.size]
    return Brush.linearGradient(listOf(Color(0xFF22262E), a), start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(0f, 200f))
}

private fun key(url: String?, localPath: String?, large: Boolean) = if (url == null && localPath == null) null else "$localPath|$url|$large"
private fun cached(url: String?, localPath: String?, large: Boolean) = key(url, localPath, large)?.let { cache.get(it) }

private suspend fun load(client: OkHttpClient?, url: String?, localPath: String?, large: Boolean = true, files: ContentResolver? = null): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = key(url, localPath, large) ?: return@withContext null
    cache.get(key)?.let { return@withContext it }
    try {
        if (isFileArt(url)) {
            val bytes = files?.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() } ?: return@withContext null
            return@withContext decode(bytes, large)?.asImageBitmap()?.also { cache.put(key, it) }
        }
        val local = localPath?.takeIf { File(it).exists() }?.let { path ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            // saved covers are big; a row only needs a small one, so it doesn't fill the memory cache
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, large) })
        }
        // songs saved before covers were fetched large keep a tiny file; use the sharp one online, the file offline
        val bmp = local?.takeIf { !large || it.width >= 300 }
            ?: url?.let { u -> client?.let { c -> runCatching { fetch(c, artAt(u, large), large) ?: fetch(c, u, large) }.getOrNull() } }
            ?: local
        bmp?.asImageBitmap()?.also { cache.put(key, it) }
    } catch (_: Exception) { null }
}

/**
 * [load], tried again after 2, 4, 8 and 16 seconds, then every 30, until it works. A cover that failed on a bad
 * connection comes back by itself. The caller's scope ends the tries when the cover leaves the screen.
 * ponytail: a cover that is gone for good is retried every 30 s while shown; cheap, but a "gave up" mark would stop it.
 */
private suspend fun loadRetrying(client: OkHttpClient?, url: String?, localPath: String?, large: Boolean = true, files: ContentResolver? = null): ImageBitmap? {
    load(client, url, localPath, large, files)?.let { return it }
    // a file with no cover won't grow one: no retries
    if (url == null || client == null || isFileArt(url)) return null
    var wait = 2_000L
    while (true) {
        delay(wait)
        load(client, url, localPath, large)?.let { return it }
        wait = (wait * 2).coerceAtMost(30_000L)
    }
}

/**
 * How much to shrink a cover [width] px wide while decoding. Large covers: halve anything wider than 800 px (the 720p
 * frames). Small covers (rows, about 150 px on screen): the most halving that still leaves at least 240 px.
 */
internal fun sampleFor(width: Int, large: Boolean): Int {
    if (large) return if (width > 800) 2 else 1
    var s = 1
    while (width / (s * 2) >= 240) s *= 2
    return s
}

/** Downloads and decodes a cover, shrunk by [sampleFor] to keep memory down. */
private fun fetch(client: OkHttpClient, url: String, large: Boolean): android.graphics.Bitmap? =
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return null
        decode(r.body?.bytes() ?: return null, large)
    }

/** Decodes a cover, shrunk by [sampleFor] to keep memory down. */
private fun decode(bytes: ByteArray, large: Boolean): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, large) })
}

/**
 * A dark colour taken from the cover, for the Now playing background. The cover's average colour, kept grey when the
 * cover is grey, then darkened until white text on it passes 7:1. Null until the cover has loaded.
 */
suspend fun coverTint(client: OkHttpClient?, url: String?, localPath: String?, files: ContentResolver? = null): Color? {
    val img = loadRetrying(client, url, localPath, files = files) ?: return null
    return withContext(Dispatchers.Default) { tintOf(img.asAndroidBitmap()) }
}

/** The [coverTint] colour of a cover already loaded. */
fun tintOf(cover: android.graphics.Bitmap): Color {
    val one = android.graphics.Bitmap.createScaledBitmap(cover, 1, 1, true)
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(one.getPixel(0, 0), it) }
    if (hsv[1] > .12f) hsv[1] = (hsv[1] * 1.3f).coerceIn(.3f, .75f)
    hsv[2] = .45f
    var c = Color(android.graphics.Color.HSVToColor(hsv))
    while (com.spotifry.music.ui.theme.contrastRatio(Color.White, c) < 7.0) c = androidx.compose.ui.graphics.lerp(c, Color.Black, .12f)
    return c
}

/** The large cover as a plain bitmap, for the pictures Share draws. One try, no retries; null when there is none. */
suspend fun coverBitmap(client: OkHttpClient?, url: String?, localPath: String?, files: ContentResolver?): android.graphics.Bitmap? =
    load(client, url, localPath, large = true, files = files)?.asAndroidBitmap()
