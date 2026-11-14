package com.spotifry.music.data

/** The running app's version, set once at start by the phone and by the desktop, for the User-Agent that tells a site who is asking. */
object AppVersion {
    @Volatile var name: String = "dev"
}
