package com.spotifry.music.data

import android.util.Log
import com.spotifry.music.data.db.ListenDao
import com.spotifry.music.player.ListenWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes log lines one at a time, in order: an update must not pass the line it updates. It outlives the player's
 * service, so the last line of a stopped song still lands. A failed write never touches the music.
 */
class ListenLog(private val dao: ListenDao, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val writes = Channel<ListenWrite>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (w in writes) try {
                when (w) {
                    is ListenWrite.New -> dao.insert(w.listen)
                    is ListenWrite.Heard -> dao.update(w.startedAt, w.seconds, w.skipped)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("ListenLog", "couldn't write: $e") }
        }
    }

    fun write(w: ListenWrite?) { if (w != null) writes.trySend(w) }
}
