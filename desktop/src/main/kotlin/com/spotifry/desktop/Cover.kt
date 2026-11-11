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
import java.util.concurrent.ConcurrentHashMap

/** Covers fetched once and kept for the session. */
object Covers {
    lateinit var client: OkHttpClient
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(url: String): ImageBitmap? = cache[url] ?: withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                org.jetbrains.skia.Image.makeFromEncoded(r.body!!.bytes()).toComposeImageBitmap()
            }
        }.getOrNull()?.also { cache[url] = it }
    }
}

/** A cover, or a plain square while it loads or when there is none. */
@Composable
fun Cover(url: String?, size: Dp, shape: Shape, large: Boolean = false, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { Covers.load(artAt(it, large)) } }
    Box(modifier.size(size).clip(shape).background(Palette.high)) {
        image?.let { Image(it, null, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}
