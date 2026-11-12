package com.spotifry.music.data

/** "0.10.0" is newer than "0.9.2"; a leading "v" is ignored. */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
