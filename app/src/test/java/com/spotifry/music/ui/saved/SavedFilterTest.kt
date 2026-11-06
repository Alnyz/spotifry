package com.spotifry.music.ui.saved

import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedFilterTest {
    private fun saved(id: String, title: String = id, artist: String = "A", sec: Long = 200) = SongEntity(id, title, artist, sec, null, 1, "/f/$id", null)
    private fun file(id: Long, title: String, artist: String = "A", sec: Long = 200) = Song("local:$id", title, artist, sec, null)
    private fun ids(f: SavedFilter, s: List<SongEntity>, p: List<Song>, r: List<SongEntity> = emptyList()) = filterSaved(f, s, p, r).map { it.song.id }

    private val s = listOf(saved("y1", "One"), saved("y2", "Two"))
    private val p = listOf(file(1, "Alpha"), file(2, "Beta"))

    @Test fun allIsSavedThenFilesAndKeepsEachOrder() = assertEquals(listOf("y1", "y2", "local:1", "local:2"), ids(SavedFilter.All, s, p))

    @Test fun offlineIsOnlySavedAndPhoneIsOnlyFiles() {
        assertEquals(listOf("y1", "y2"), ids(SavedFilter.Offline, s, p))
        assertEquals(listOf("local:1", "local:2"), ids(SavedFilter.Phone, s, p))
    }

    @Test fun aSongSavedAndAlsoAFileShowsOnceInAllAsTheSavedOne() {
        val both = listOf(file(9, "ONE", "a", 202), file(3, "Gamma"))
        assertEquals(listOf("y1", "y2", "local:3"), ids(SavedFilter.All, s, both))
        // the phone list itself still shows every file
        assertEquals(listOf("local:9", "local:3"), ids(SavedFilter.Phone, s, both))
        assertTrue(filterSaved(SavedFilter.All, s, both, emptyList()).first().saved)
    }

    @Test fun sameNameButAnotherLengthIsAnotherSong() = assertEquals(listOf("y1", "y2", "local:9"), ids(SavedFilter.All, s, listOf(file(9, "One", "A", 260))))

    @Test fun playedLatelyKeepsHistoryOrderAndDropsWhatThePageCantList() {
        val recent = listOf(saved("gone"), saved("local:2"), saved("y1"))
        assertEquals(listOf("local:2", "y1"), ids(SavedFilter.Lately, s, p, recent))
        assertTrue(filterSaved(SavedFilter.Lately, s, p, recent).let { !it[0].saved && it[1].saved })
    }

    @Test fun theSameSongIsFoundWhateverTheCaseAndSpaces() =
        assertEquals(listOf("y1", "y2"), ids(SavedFilter.All, s, listOf(file(9, "  oNe ", " a "))))

    @Test fun lettersWithTwoCasesCountAsTheSame() {
        val saved = listOf(saved("y1", "ΣΟΦΙΑΣ", "Kırık"), saved("y2", "İki", "A"))
        assertEquals(listOf("y1", "y2"), ids(SavedFilter.All, saved, listOf(file(8, "σοφιας", "KIRIK"), file(9, "iki", "A"))))
    }

    @Test fun anotherArtistIsAnotherSong() = assertEquals(listOf("y1", "y2", "local:9"), ids(SavedFilter.All, s, listOf(file(9, "One", "B"))))

    @Test fun threeSecondsApartIsTheSameSongAndFourIsNot() =
        assertEquals(listOf("y1", "y2", "local:8", "local:9"), ids(SavedFilter.All, s, listOf(file(7, "One", "A", 203), file(8, "One", "A", 204), file(9, "One", "A", 196))))

    @Test fun anyOfTwoSavedCopiesOfANameCanMatchAFile() {
        val two = listOf(saved("y1", "One", sec = 200), saved("y3", "One", sec = 300))
        assertEquals(listOf("y1", "y3"), ids(SavedFilter.All, two, listOf(file(9, "One", "A", 301))))
    }

    @Test fun allIsPlayedAsYourMusicAsItCanHoldPhoneFiles() {
        assertEquals("Your music", SavedFilter.All.queueName)
        assertEquals("Saved", SavedFilter.Offline.queueName)
        assertEquals("On this phone", SavedFilter.Phone.queueName)
        assertEquals("Played lately", SavedFilter.Lately.queueName)
    }

    @Test fun nothingInNothingOut() = SavedFilter.entries.forEach { assertTrue(ids(it, emptyList(), emptyList()).isEmpty()) }
}
