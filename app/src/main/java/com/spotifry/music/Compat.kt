package com.spotifry.music

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/** Stand-ins for Android 13 functions the YouTube reader calls; the build points its calls here (see buildSrc/OldAndroidCompat). */
object Compat {
    @JvmStatic fun urlEncode(s: String, cs: Charset): String = URLEncoder.encode(s, cs.name())
    @JvmStatic fun urlDecode(s: String, cs: Charset): String = URLDecoder.decode(s, cs.name())
}
