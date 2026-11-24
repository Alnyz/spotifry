package com.spotifry.music

import android.app.Application
import com.spotifry.music.data.source.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class MusicApp : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.spotifry.music.data.AppVersion.name = BuildConfig.VERSION_NAME
        container = AppContainer(this)
        val errors = container.errorLog
        com.spotifry.music.data.Log.sink = { tag, message -> android.util.Log.w(tag, message); errors.add(tag, message) }
        // a crash is written down before Android closes the app, so it can be pasted into a bug report next time
        val crash = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            errors.add("crash", "on ${thread.name}: ${e.stackTraceToString().take(8_000)}")
            crash?.uncaughtException(thread, e)
        }
        NewPipe.init(OkHttpDownloader(container.client), Localization("en", "US"))
        com.spotifry.music.data.spotifried.enqueueYearLookup(this)
        Thread { runCatching { com.spotifry.music.ui.share.clearSharedPictures(cacheDir) } }.start()
    }
}
