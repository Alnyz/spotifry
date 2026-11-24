package com.spotifry.music.player

import androidx.media3.common.MediaItem
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarLibraryTest {
    private fun song(n: Int) = Song("id$n", "Song $n", "Artist", 100, null)
    private val library = CarLibrary(
        recent = { listOf(song(1), song(2)) },
        saved = { listOf(song(3)) },
        mostPlayed = { emptyList() },
        playlists = { listOf(7L to "Road trip") },
        playlist = { if (it == 7L) listOf(song(4), song(5), song(6)) else emptyList() },
        song = { id -> (1..6).map(::song).firstOrNull { it.id == id } },
        search = { q -> searched += q; listOf(Song("web1", "Hello", "Adele", 200, null)) },
    )
    private val searched = mutableListOf<String>()

    @Test fun rootListsTheFourShelvesAndOpensPlaylists() = runBlocking {
        assertEquals(listOf("Recently played", "Saved", "Most played", "Playlists"), library.children(CarLibrary.ROOT)!!.map { it.mediaMetadata.title.toString() })
        val lists = library.children(CarLibrary.PLAYLISTS)!!
        assertEquals(listOf("Road trip"), lists.map { it.mediaMetadata.title.toString() })
        assertTrue(lists.single().mediaMetadata.isBrowsable == true)
        val songs = library.children(lists.single().mediaId)!!
        assertEquals(listOf("Song 4", "Song 5", "Song 6"), songs.map { it.mediaMetadata.title.toString() })
        assertTrue(songs.all { it.mediaMetadata.isPlayable == true })
    }

    @Test fun anUnknownListIsNull() = runBlocking {
        assertNull(library.children("nope"))
        assertNull(library.children("playlist/x"))
    }

    @Test fun aSongPickedInAListPlaysTheWholeListFromThere() = runBlocking {
        val picked = library.children("playlist/7")!![1]
        val (items, at) = library.wholeList(listOf(MediaItem.Builder().setMediaId(picked.mediaId).build()))!!
        assertEquals(listOf("id4", "id5", "id6"), items.map { it.mediaId })
        assertEquals(1, at)
        // what goes to the player is a normal song, the same as the app's own queue
        assertTrue(items.all { it.localConfiguration != null })
    }

    @Test fun itemsKnownOnlyByIdAreLookedUpAndReadyOnesKept() = runBlocking {
        val ready = song(1).toMediaItem()
        assertSame(ready, library.resolve(ready))
        assertEquals("id3", library.resolve(MediaItem.Builder().setMediaId("saved|id3").build())!!.mediaId)
        assertNull(library.resolve(MediaItem.Builder().setMediaId("gone").build()))
        assertNull(library.wholeList(listOf(ready)))
    }

    @Test fun itemsAreFoundByTheirId() = runBlocking {
        assertEquals("Saved", library.item(CarLibrary.SAVED)!!.mediaMetadata.title.toString())
        assertEquals("Road trip", library.item("playlist/7")!!.mediaMetadata.title.toString())
        assertEquals("Song 2", library.item("recent|id2")!!.mediaMetadata.title.toString())
    }

    @Test fun voiceRequestsPlayFromThePhoneFirst() = runBlocking {
        // nothing named: what was played lately
        assertEquals(listOf("id1", "id2"), library.forQuery(" ").map { it.mediaId })
        // a match on the phone plays without searching
        assertEquals(listOf("id3"), library.forQuery("song 3").map { it.mediaId })
        assertTrue(searched.isEmpty())
        // nothing on the phone: searched like the app's own search
        assertEquals(listOf("web1"), library.forQuery("adele").map { it.mediaId })
        assertEquals(listOf("adele"), searched)
    }
}
