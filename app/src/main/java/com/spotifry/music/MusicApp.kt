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
        com.spotifry.music.data.Log.sink = { tag, message -> android.util.Log.w(tag, message) }
        container = AppContainer(this)
        NewPipe.init(OkHttpDownloader(container.client), Localization("en", "US"))
        com.spotifry.music.data.spotifried.enqueueYearLookup(this)
        Thread { runCatching { com.spotifry.music.ui.share.clearSharedPictures(cacheDir) } }.start()
    }
}
