package com.spotifry.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.RepeatMode
import com.spotifry.music.player.Trim
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

/** Draws the window to a picture without a screen: PNGs land in build/snapshots for a look. */
class SnapshotTest {
    private val songs = listOf(
        Song("a", "Atap Rumah", "Rumah Timur", 190, null), Song("b", "Lampu Kota", "Nara Senja", 197, null), Song("c", "Paper Boats", "The Paper Kites Club", 204, null),
        Song("d", "Jalan Pulang", "Kala Biru", 211, null), Song("e", "Sore Ini", "Dewi Lintang", 218, null), Song("f", "Halte Terakhir", "Rumah Timur", 225, null),
    )
    private val fakeSource = object : MusicSource {
        override suspend fun search(query: String) = songs
        override suspend fun resolve(song: Song, low: Boolean) = ResolvedStream("", "", 0, 0)
        override suspend fun related(song: Song) = emptyList<Song>()
    }
    private val fakePlayer = object : MusicPlayer {
        override val state = MutableStateFlow(PlayerUiState(current = songs[3], isPlaying = true, wantsPlay = true, durationMs = 211_000, queue = songs, currentIndex = 3, repeatMode = RepeatMode.ALL))
        override val position = MutableStateFlow(84_000L)
        override fun playList(songs: List<Song>, startIndex: Int, from: String?) = Unit
        override fun toggle() = Unit; override fun pause() = Unit; override fun next() = Unit; override fun previous() = Unit
        override fun seekTo(ms: Long) = Unit; override fun cycleShuffle() = Unit; override fun remove(index: Int, id: String) = Unit
        override fun insert(index: Int, song: Song, upcoming: List<Int>?) = Unit; override fun move(from: Int, to: Int) = Unit
        override fun cycleRepeat() = Unit; override fun skipTo(index: Int) = Unit
        override fun setTrim(id: String, trim: Trim?, skip: Boolean) = Unit; override fun listen(atMs: Long, forMs: Long?) = Unit
    }

    @Test fun searchPage() {
        val scene = ImageComposeScene(1280, 800, Density(1f)) { SpotifryTheme { App(fakePlayer, fakeSource, {}, initialQuery = "kala biru") } }
        var t = 0L
        repeat(30) { scene.render(t); t += 50_000_000; Thread.sleep(20) }
        val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File("build/snapshots").apply { mkdirs() }.resolve("search.png").writeBytes(png)
        scene.close()
    }
}
