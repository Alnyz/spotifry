package com.spotifry.music.ui

/** The list with the item at [from] taken out and put back at [to], as a drag or a Move up / Move down action leaves it. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
