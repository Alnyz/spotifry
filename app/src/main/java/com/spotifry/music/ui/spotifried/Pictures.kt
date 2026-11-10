package com.spotifry.music.ui.spotifried

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.spotifry.music.MusicApp
import com.spotifry.music.R
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.spotifried.CardId
import com.spotifry.music.ui.components.loadCover
import com.spotifry.music.ui.share.CardBackground
import com.spotifry.music.ui.share.cardInk
import com.spotifry.music.ui.share.drawWatermark
import com.spotifry.music.ui.theme.Accent
import com.spotifry.music.ui.theme.AccentChoice
import com.spotifry.music.ui.theme.LocalAccent
import com.spotifry.music.ui.theme.accentFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val WIDTH = 1080
private const val HEIGHT = 1920
private const val EM = WIDTH / 20f

/** Above the card, where the watermark goes; on screen the progress bar sits there. Its bottom is about 47 dp down. */
internal val WatermarkBand = 52.dp

/**
 * [id] as a 1080 x 1920 picture: the card itself, still, on the story's dark theme with the user's accent, and the
 * Spotifry watermark drawn over it afterwards. Gives up with an exception when the card is not drawn within 10 s.
 * Nothing here keeps the picture: the caller recycles it.
 */
suspend fun captureStoryCard(activity: Activity, id: CardId, state: SpotifriedState): Bitmap {
    val c = (activity.application as MusicApp).container
    val accent = accentFor(AccentChoice.of(c.settings.accent.value), c.settings.accentColor.value, activity)
    return captureStoryCard(activity, id, state, accent) { loadCover(c.imageClient, it.thumbnailUrl, it.thumbPath, activity.contentResolver) }
}

internal suspend fun captureStoryCard(
    activity: Activity, id: CardId, state: SpotifriedState, accent: Accent, limitMs: Long = 10_000, cover: suspend (SongEntity) -> Unit,
): Bitmap = withContext(Dispatchers.Main) {
    // the cover goes into memory first so the card shows it at once; one that doesn't come in time leaves the placeholder
    val song = state.stats.topSong?.song
    if (id == CardId.TOP_SONG && song != null) withTimeoutOrNull(limitMs) { cover(song) }

    val drawn = CompletableDeferred<GraphicsLayer>()
    val view = ComposeView(activity).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setContent {
            // fontScale 1: the picture is the same whatever the phone's text size
            CompositionLocalProvider(LocalAccent provides accent, LocalDensity provides Density(WIDTH / 360f, 1f)) {
                val layer = rememberGraphicsLayer()
                Box(Modifier.size(360.dp, 640.dp).drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer); drawn.complete(layer) }) {
                    StoryTheme { Box(Modifier.padding(top = WatermarkBand)) { StoryCard(id, state, onShare = {}, still = true) } }
                }
            }
        }
    }
    val decor = activity.window.decorView as ViewGroup
    // behind the app's own views: covered, yet drawn every frame like any child. One moved off screen would not be drawn.
    decor.addView(view, 0, FrameLayout.LayoutParams(WIDTH, HEIGHT))
    try {
        val shot = withTimeout(limitMs) { drawn.await().also { awaitFrame() }.toImageBitmap() }.asAndroidBitmap()
        // on a phone the shot is a hardware bitmap, which can't be drawn on
        val out = shot.copy(Bitmap.Config.ARGB_8888, true)
        if (out !== shot) shot.recycle()
        drawWatermark(Canvas(out), ResourcesCompat.getFont(activity, R.font.albertsans), EM, 1.6f * EM, 1.7f * EM, cardInk(CardBackground.BLACK, null, accent.dark))
        out
    } finally {
        decor.removeView(view)
    }
}
