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
import com.spotifry.music.data.db.toEntity
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

    private fun library(): Library {
        val db = androidx.room.Room.inMemoryDatabaseBuilder<com.spotifry.music.data.db.AppDatabase>(factory = { com.spotifry.music.data.db.AppDatabase_Impl() })
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver()).setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.IO).build()
        val client = okhttp3.OkHttpClient()
        val dir = java.nio.file.Files.createTempDirectory("spotifry-snap").toFile()
        val lib = Library(db, com.spotifry.music.data.MemoryPrefs(), com.spotifry.music.data.MemoryPrefs(), com.spotifry.music.data.MemoryPrefs(), dir, client,
            com.spotifry.music.data.StreamResolver(fakeSource))
        kotlinx.coroutines.runBlocking {
            songs.forEach { db.songs().upsert(it.toEntity()) }
            db.songs().markSaved("a", "/x/a", null, 1); db.songs().markSaved("c", "/x/c", null, 2); db.songs().markSaved("e", "/x/e", null, 3)
            songs.forEachIndexed { i, s -> db.history().record(s.id, 100L + i) }
            val road = db.playlists().create("Road trip 2019"); songs.forEach { db.playlists().addSong(road, it.id) }
            val rain = db.playlists().create("Hujan"); songs.take(3).forEach { db.playlists().addSong(rain, it.id) }
            db.playlists().create("Fokus").also { songs.takeLast(2).forEach { s -> db.playlists().addSong(it, s.id) } }
        }
        return lib
    }

    private fun shoot(name: String, route: Route, library: Library = library()) {
        val services = Services(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), fakeSource, library, fakePlayer, setVolume = {})
        val scene = ImageComposeScene(1280, 800, Density(1f)) { SpotifryTheme { App(services, startOn = route, initialQuery = if (route == Route.Search) "kala biru" else "") } }
        var t = 0L
        repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(25) }
        val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File("build/snapshots").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
        scene.close()
    }

    @Test fun settingsLightAndDark() {
        val lib = library()
        shoot("settings", Route.Settings, lib)
        lib.settings.setTheme(com.spotifry.music.data.ThemeMode.LIGHT); lib.settings.setAccent("BLUE", 0)
        shoot("settings-light", Route.Settings, lib)
        shoot("home-light", Route.Home, lib)
        Palette.dark = true; Palette.accentChoice = AccentChoice.EMBER
    }

    @Test fun pages() {
        val lib = library()
        shoot("search", Route.Search, lib)
        shoot("home", Route.Home, lib)
        shoot("saved", Route.Saved, lib)
        shoot("playlists", Route.Playlists, lib)
        shoot("playlist", Route.Playlist(1), lib)
    }

    /** With SPOTIFRY_BACKUP set: restores a real backup and draws the pages with it, covers included. Pictures stay in build/. */
    @Test fun pagesWithARealBackup() {
        val path = System.getenv("SPOTIFRY_BACKUP") ?: return
        Covers.client = okhttp3.OkHttpClient()
        val db = androidx.room.Room.inMemoryDatabaseBuilder<com.spotifry.music.data.db.AppDatabase>(factory = { com.spotifry.music.data.db.AppDatabase_Impl() })
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver()).setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.IO).build()
        val dir = java.nio.file.Files.createTempDirectory("spotifry-real").toFile()
        val lib = Library(db, com.spotifry.music.data.MemoryPrefs(), com.spotifry.music.data.MemoryPrefs(), com.spotifry.music.data.MemoryPrefs(), dir, okhttp3.OkHttpClient(), com.spotifry.music.data.StreamResolver(fakeSource))
        kotlinx.coroutines.runBlocking { lib.backups.restore(com.spotifry.music.data.readBackup(File(path).inputStream())) }
        for ((name, route) in listOf("real-home" to Route.Home, "real-playlists" to Route.Playlists, "real-playlist" to Route.Playlist(1), "real-saved" to Route.Saved)) {
            val services = Services(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), fakeSource, lib, fakePlayer, setVolume = {})
            val scene = ImageComposeScene(1280, 800, Density(1f)) { SpotifryTheme { App(services, startOn = route) } }
            var t = 0L
            repeat(120) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
            File("build/snapshots").apply { mkdirs() }.resolve("$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
