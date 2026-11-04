package com.spotifry.music.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The last 10 searches, newest first. [save] writes the list wherever it is kept. */
class RecentSearches(initial: List<String>, private val save: (List<String>) -> Unit = {}) {
    private val _list = MutableStateFlow(initial)
    val list: StateFlow<List<String>> = _list

    fun add(query: String) {
        val q = query.trim()
        if (q.none { it.isLetterOrDigit() }) return
        update((listOf(q) + _list.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }
    fun remove(query: String) = update(_list.value - query)
    fun clear() = update(emptyList())

    private fun update(l: List<String>) { _list.value = l; save(l) }

    private companion object { const val MAX = 10 }
}
