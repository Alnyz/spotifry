package com.spotifry.music

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.player.PlayerController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on the phone. A saved file must play with the network resolver never needed. */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    @Test fun savedFilePlaysWithoutNetwork() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.filesDir, "tone.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tone.m4a").use { i -> file.outputStream().use { i.copyTo(it) } }
        val c = (ctx as MusicApp).container
        c.db.songs().upsert(SongEntity("tone", "Tone", "Test", 4, null, null, null, null))
        c.db.songs().markSaved("tone", file.absolutePath, null, 1)

        val controller = InstrumentationRegistry.getInstrumentation().runOnMainSyncReturn { PlayerController(ctx) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.playList(listOf(com.spotifry.music.data.source.Song("tone", "Tone", "Test", 4, null)), 0) }
        var playing = false
        repeat(50) { if (!playing) { Thread.sleep(100); playing = controller.state.value.isPlaying } }
        assertTrue("saved file should start playing", playing)
    }
}

private fun <T> android.app.Instrumentation.runOnMainSyncReturn(block: () -> T): T {
    var out: T? = null
    runOnMainSync { out = block() }
    @Suppress("UNCHECKED_CAST") return out as T
}
