package com.spotifry.music.data

/** Where warnings go. The phone app points [sink] at logcat; the desktop app can point it at its own log. */
object Log {
    @Volatile var sink: (tag: String, message: String) -> Unit = { tag, message -> System.err.println("$tag: $message") }
    fun w(tag: String, message: String) = sink(tag, message)
}
