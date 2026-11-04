package com.spotifry.music.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.SizeF
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.spotifry.music.MainActivity
import com.spotifry.music.MusicApp
import com.spotifry.music.R
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.ui.theme.AccentChoice
import com.spotifry.music.ui.theme.accentFor
import com.spotifry.music.ui.theme.darkColors
import com.spotifry.music.ui.theme.lightColors
import java.io.File

/** The home screen widget. Android asks it to draw when placed or resized; the player redraws it on every change. */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widget.draw(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = Widget.draw(context)
}

/**
 * What the widget shows, kept on the phone so it still shows the last song after the app was closed or the phone restarted.
 * The cover is saved as a small picture next to it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object Widget {
    private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
    private fun coverFile(c: Context) = File(c.filesDir, "widget_cover.png")

    /** Called by the player: new song, play or pause. [cover] null keeps the saved one when the song is the same. */
    fun show(c: Context, id: String?, title: String, artist: String, playing: Boolean, cover: Bitmap? = null) {
        val p = prefs(c)
        if (id != p.getString("id", null)) coverFile(c).delete()
        p.edit().putString("id", id).putString("title", title).putString("artist", artist).putBoolean("playing", playing).apply()
        // small enough for the widget's memory limit, sharp enough for a 120 dp cover
        cover?.let { b -> runCatching { coverFile(c).outputStream().use { Bitmap.createScaledBitmap(b, 300, 300 * b.height / b.width, true).compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        draw(c)
    }

    fun draw(c: Context) {
        val manager = AppWidgetManager.getInstance(c)
        val ids = manager.getAppWidgetIds(ComponentName(c, MusicWidget::class.java))
        if (ids.isEmpty()) return
        val p = prefs(c)
        val settings = (c.applicationContext as MusicApp).container.settings
        val dark = when (settings.theme.value) {
            ThemeMode.DARK -> true; ThemeMode.LIGHT -> false
            ThemeMode.PHONE -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val accent = accentFor(AccentChoice.of(settings.accent.value), settings.accentColor.value, c)
        val scheme = if (dark) darkColors(accent, settings.pureBlack.value) else lightColors(accent)
        val cover = coverFile(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val playing = p.getBoolean("playing", false)

        fun views(layout: Int) = RemoteViews(c.packageName, layout).apply {
            setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(scheme.surface.toArgb()))
            setTextViewText(R.id.widget_title, p.getString("title", null) ?: "Spotifry")
            setTextViewText(R.id.widget_artist, p.getString("artist", null) ?: "Tap to open")
            setTextColor(R.id.widget_title, scheme.onSurface.toArgb())
            setTextColor(R.id.widget_artist, scheme.onSurfaceVariant.toArgb())
            if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_bg)
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
            setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
            setColorStateList(R.id.widget_play, "setBackgroundTintList", ColorStateList.valueOf(scheme.primary.toArgb()))
            setColorStateList(R.id.widget_play, "setImageTintList", ColorStateList.valueOf(scheme.onPrimary.toArgb()))
            for (b in listOf(R.id.widget_prev, R.id.widget_next)) {
                setColorStateList(b, "setBackgroundTintList", ColorStateList.valueOf(scheme.surfaceContainerHigh.toArgb()))
                setColorStateList(b, "setImageTintList", ColorStateList.valueOf(scheme.onSurface.toArgb()))
            }
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
            ))
            setOnClickPendingIntent(R.id.widget_play, key(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            setOnClickPendingIntent(R.id.widget_next, key(c, KeyEvent.KEYCODE_MEDIA_NEXT))
            setOnClickPendingIntent(R.id.widget_prev, key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        }
        // the launcher picks the biggest layout that fits: two rows get the big one, one row the small one
        manager.updateAppWidget(ids, RemoteViews(mapOf(SizeF(200f, 50f) to views(R.layout.widget_small), SizeF(200f, 110f) to views(R.layout.widget_big))))
    }

    /** A press of a media key, the same as a headset button: it wakes the player even when the app is closed. */
    private fun key(c: Context, code: Int) = PendingIntent.getBroadcast(
        c, code,
        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(c, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code)),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
