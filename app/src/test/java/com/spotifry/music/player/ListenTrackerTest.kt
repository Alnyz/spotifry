package com.spotifry.music.player

import androidx.media3.common.Player
import com.spotifry.music.data.db.ListenEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        log += t.tick(id, sounding, 1000)
    }

    private fun move(id: String?, byListener: Boolean = true) { t.moved(id, byListener)?.let(log::add) }

    @Test fun aSongPassedInUnderASecondLeavesNoLineEvenIfATickLandsOnIt() {
        move("a"); clock += 300; log += t.tick("a", true, 1000); clock += 100
        move("b")
        assertTrue(lines().isEmpty())
    }

    @Test fun theClockSetBackMidSongNeverTakesTimeAway() {
        move("a"); play("a", 180)
        clock -= 3_600_000
        play("a", 60)
        move("b")
        assertEquals(240, lines().single().secondsHeard)
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

    @Test fun wakingAfterALongPauseAddsAtMostTheTimeBetweenTwoTicks() {
        move("a"); play("a", 5); play("a", 1, sounding = false)
        clock += 3_600_000; log += t.tick("a", true, 3_600_000)
        move(null, byListener = false)
        assertEquals(6, lines().single().secondsHeard)
    }

    @Test fun aBusyMomentWhilePlayingLosesNothingButALongGapIsNotBelieved() {
        move("a"); play("a", 2)
        clock += 3000; log += t.tick("a", true, 3000)
        move(null, byListener = false)
        assertEquals(5, lines().single().secondsHeard)
        move("b"); play("b", 2)
        clock += 60_000; log += t.tick("b", true, 60_000)
        move(null, byListener = false)
        assertEquals(7, lines().last().secondsHeard)
    }

    @Test fun aSongPlayingIsWrittenEveryTenSeconds() {
        move("a"); play("a", 25)
        assertTrue(lines().single().secondsHeard in 15..25)
    }

    @Test fun stoppingEarlyByMovingOnIsASkipHoweverShortTheSong() {
        move("a"); play("a", 12); move("b")
        assertTrue(lines().single().skipped)
        assertEquals(12, lines().single().secondsHeard)
    }

    @Test fun thirtySecondsIsNotASkip() {
        move("a"); play("a", 30); move("b")
        assertFalse(lines().single().skipped)
    }

    @Test fun aSongThatEndsByItselfIsNotASkip() {
        move("a"); play("a", 25); move("b", byListener = false)
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

    @Test fun theSameSongAgainIsANewListenAndNotASkip() {
        move("a"); play("a", 40); move("a"); play("a", 10); move("a", byListener = false); play("a", 40); move("b")
        assertEquals(listOf(40, 10, 40), lines().map { it.secondsHeard })
        assertFalse(lines().any { it.skipped })
    }

    @Test fun aSongAlreadyInPlaceWhenTheServiceComesUpIsCounted() {
        play("a", 3)
        assertEquals("a", lines().single().songId)
    }

    @Test fun noSongCountsNothing() {
        play(null, 5)
        assertTrue(lines().isEmpty())
    }

    @Test fun onlyAHandMovingOnIsByTheListener() {
        assertTrue(movedByListener(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, false))
        assertTrue(movedByListener(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, false))
        assertFalse(movedByListener(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, false))
        assertFalse(movedByListener(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, false))
        assertFalse(movedByListener(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, true))
    }

    /**
     * A crossfade as the service feeds it: the service leaves out the handover move, and names the song on the tail voice
     * until that's done. The old song keeps every second, however little of it had been heard, and isn't a skip.
     */
    @Test fun aCrossfadeCountsEverySecondOnceAndTheOldSongKeepsTheOverlap() {
        move("a"); play("a", 20)
        play("a", 9) // main has moved on, silent then fading in; the tail voice is heard
        play("b", 50) // the first of these seconds passed before the tail was done, so it's still the old song's
        move("c", byListener = false)
        assertEquals(listOf(30, 49), lines().map { it.secondsHeard })
        assertEquals(79, lines().sumOf { it.secondsHeard })
        assertEquals(listOf("a", "b"), lines().map { it.songId })
        assertFalse(lines().any { it.skipped })
    }

    /** The new song failed mid-overlap: when the tail is done nothing plays, so it gets no line. */
    @Test fun aNewSongThatNeverSoundedGetsNoLineAfterACrossfade() {
        move("a"); play("a", 100)
        play("b", 10, sounding = false)
        assertEquals(listOf("a"), lines().map { it.songId })
        assertEquals(100, lines().single().secondsHeard)
    }

    @Test fun theSecondsBeforeASongChangesUnderTheTickStayWithTheOldSong() {
        move("a"); play("a", 1)
        clock += 1000; log += t.tick("b", true, 1000)
        assertEquals(2, lines().single().secondsHeard)
    }

    @Test fun aSongJustCrossingASecondAsItChangesStillGetsItsLine() {
        move("a"); clock += 500; log += t.tick("a", true, 500)
        clock += 700; log += t.tick("b", true, 700)
        assertEquals(1, lines().single().secondsHeard)
    }
}
