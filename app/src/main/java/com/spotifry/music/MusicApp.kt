package com.spotifry.music

import android.app.Application
import com.spotifry.music.data.source.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class MusicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NewPipe.init(OkHttpDownloader(container.client), Localization("en", "US"))
    }
}
