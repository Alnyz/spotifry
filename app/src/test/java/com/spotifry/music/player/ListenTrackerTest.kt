package com.spotifry.music.player

import com.spotifry.music.data.db.ListenEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTrackerTest {
    private var clock = 1_000_000L
    private val log = ArrayList<ListenWrite>()
    private val t = ListenTracker { clock }

    /** What the log would hold after these writes, as the real table does it. */
    private fun lines(): List<ListenEntity> {
        val rows = LinkedHashMap<Long, ListenEntity>()
        log.forEach { w ->
            when (w) {
                is ListenWrite.New -> rows.putIfAbsent(w.listen.startedAt, w.listen)
                is ListenWrite.Heard -> rows[w.startedAt]?.let { rows[w.startedAt] = it.copy(secondsHeard = w.seconds, skipped = w.skipped) }
            }
        }
        return rows.values.toList()
    }

    private fun play(id: String?, seconds: Int, sounding: Boolean = true) = repeat(seconds) {
        clock += 1000
        t.tick(id, sounding, 1000) { 200 }?.let(log::add)
    }

    private fun move(id: String?, length: Long = 200, again: Boolean = false, byListener: Boolean = true) {
        t.moved(id, length, again, byListener)?.let(log::add)
    }

    @Test fun aSongPassedInUnderASecondLeavesNoLine() {
        move("a"); clock += 400
        t.tick("a", true, 400) { 200 }?.let(log::add)
        move("b")
        assertTrue(lines().isEmpty())
    }

    @Test fun aLineStartsWhenTheSoundStarted() {
        move("a"); play("a", 1)
        assertEquals(ListenEntity(clock - 1000, "a", 1), lines().single())
    }

    @Test fun pausedTimeIsNotHeardAndAPauseWritesWhatWasHeard() {
        move("a"); play("a", 5); play("a", 60, sounding = false)
        assertEquals(5, lines().single().secondsHeard)
        play("a", 3)
        move(null, byListener = false)
        assertEquals(8, lines().single().secondsHeard)
    }

    @Test fun aSongPlayingIsWrittenEveryTenSeconds() {
        move("a"); play("a", 25)
        assertTrue(lines().single().secondsHeard in 15..25)
    }

    @Test fun stoppingEarlyByMovingOnIsASkip() {
        move("a"); play("a", 12); move("b")
        assertTrue(lines().single().skipped)
        assertEquals(12, lines().single().secondsHeard)
    }

    @Test fun thirtySecondsIsNotASkip() {
        move("a"); play("a", 30); move("b")
        assertFalse(lines().single().skipped)
    }

    @Test fun aSongThatEndsByItselfIsNotASkip() {
        move("a", 25); play("a", 25); move("b", byListener = false)
        assertFalse(lines().single().skipped)
    }

    @Test fun aShortSongIsNeverASkipEvenWhenACrossfadeMovesOnEarly() {
        // a crossfade hands over with a SEEK, which looks like the listener moving on
        move("a", 40); play("a", 28); move("b", byListener = true)
        assertFalse(lines().single().skipped)
    }

    @Test fun aSongStillPlayingIsNotASkipYet() {
        move("a"); play("a", 5)
        assertFalse(lines().single().skipped)
    }

    @Test fun anAppClosedOnASongIsNotASkip() {
        move("a"); play("a", 5); move(null, byListener = false)
        assertFalse(lines().single().skipped)
    }

    @Test fun aNewTrimReplacingTheSongIsTheSameListen() {
        move("a"); play("a", 20); assertNull(t.moved("a", 200, again = false, byListener = true)); play("a", 20); move("b")
        val only = lines().single()
        assertEquals(40, only.secondsHeard)
        assertFalse(only.skipped)
    }

    @Test fun repeatOneIsANewListen() {
        move("a"); play("a", 40); move("a", again = true, byListener = false); play("a", 40); move("b")
        assertEquals(listOf(40, 40), lines().map { it.secondsHeard })
    }

    @Test fun aSongAlreadyInPlaceWhenTheServiceComesUpIsCounted() {
        play("a", 3)
        assertEquals("a", lines().single().songId)
    }

    @Test fun noSongCountsNothing() {
        play(null, 5)
        assertTrue(lines().isEmpty())
    }

    /**
     * The handover: the service feeds "sounding" as main playing or the tail voice heard, so the seconds of a crossfade are
     * the real time that passed. Main moves on when the overlap's lead begins and is silent and loading for 3 s while the
     * old song is heard on its tail voice; then both sound for 6 s. Nothing is counted twice and nothing is lost.
     */
    @Test fun aCrossfadeCountsEverySecondOnceAcrossBothSongs() {
        move("a"); play("a", 100)
        move("b", byListener = true) // the handover
        play("b", 3, sounding = true) // main loading, tail heard
        play("b", 6, sounding = true) // the overlap
        play("b", 50)
        move("c", byListener = false)
        assertEquals(listOf(100, 59), lines().map { it.secondsHeard })
        assertEquals(159, lines().sumOf { it.secondsHeard })
        assertFalse(lines().any { it.skipped })
    }
}
