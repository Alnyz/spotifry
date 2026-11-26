package com.spotifry.music.ui.names

import android.content.ContentResolver
import android.net.Uri
import com.spotifry.music.data.NameLook
import com.spotifry.music.data.NameLogos
import com.spotifry.music.data.NameShow
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.Settings
import com.spotifry.music.data.spotifried.ArtistLogos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Favourite artists' name looks ([Settings.artistNames], keyed by [com.spotifry.music.data.PickedArtist.key]), their own logos, and official logos. */
class ArtistNames(private val settings: Settings, private val own: NameLogos, private val officialLogos: ArtistLogos) {
    val looks get() = settings.artistNames
    val hasKey get() = com.spotifry.music.BuildConfig.FANART_PROJECT_KEY.isNotEmpty() || settings.fanartKey.value.isNotEmpty()
    val fanartKey get() = settings.fanartKey

    fun ownFile(look: NameLook?): File? = own.file(look?.logoFile)
    suspend fun official(name: String): File? = officialLogos.logo(name)

    private fun edit(key: String, change: (NameLook) -> NameLook) = settings.setArtistName(key, change(settings.artistNames.value[key] ?: NameLook()))

    fun setStyle(key: String, style: NameStyle) = edit(key) { it.copy(show = NameShow.TEXT, style = style) }
    fun useOwn(key: String) = edit(key) { if (it.logoFile != null) it.copy(show = NameShow.OWN) else it }
    fun useOfficial(key: String) = edit(key) { it.copy(show = NameShow.OFFICIAL) }
    fun setTint(key: String, on: Boolean) = edit(key) { it.copy(tint = on) }

    /** Keeps the picture as the artist's own logo and shows it; false when it can't be read. */
    suspend fun setOwnLogo(key: String, files: ContentResolver, uri: Uri): Boolean {
        val new = own.import(files, uri) ?: return false
        val old = settings.artistNames.value[key]?.logoFile
        // a picture with no transparent parts would turn into a plain box in the page colour
        edit(key) { it.copy(show = NameShow.OWN, logoFile = new.name, tint = new.seeThrough) }
        withContext(Dispatchers.IO) { own.delete(old) }
        return true
    }

    fun removeOwnLogo(key: String) {
        val old = settings.artistNames.value[key]?.logoFile
        edit(key) { it.copy(show = NameShow.TEXT, logoFile = null) }
        own.delete(old)
    }
}
