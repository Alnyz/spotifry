package com.spotifry.music.data.spotifried

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRulesTest {
    private fun type(s: SpotifriedStats) = listenerType(s)

    @Test fun nightReplayer() = assertEquals(ListenerType.NIGHT_REPLAYER, type(stats(nightShare = 0.34f, replayedSongs = 5)))
    @Test fun nightListenerWithFewReplaysIsNotOne() = assertEquals(ListenerType.REGULAR, type(stats(nightShare = 0.5f, replayedSongs = 4)))
    @Test fun timeTraveller() = assertEquals(ListenerType.TIME_TRAVELLER, type(stats(age = ageStats(typicalYear = 2001))))
    @Test fun twentyFourYearsIsNotEnough() = assertEquals(ListenerType.REGULAR, type(stats(age = ageStats(typicalYear = 2002))))
    @Test fun streakKeeper() = assertEquals(ListenerType.STREAK_KEEPER, type(stats(longestStreak = 30)))
    @Test fun explorer() = assertEquals(ListenerType.EXPLORER, type(stats(songs = 10, newSongs = 6)))
    @Test fun halfNewIsNotAnExplorer() = assertEquals(ListenerType.REGULAR, type(stats(songs = 10, newSongs = 5)))
    @Test fun loyalist() = assertEquals(ListenerType.LOYALIST, type(stats(topArtist = ArtistShare("A", 0.34f))))
    @Test fun marathoner() = assertEquals(ListenerType.MARATHONER, type(stats(daySeconds = listOf(0, 8 * 3600L + 1))))
    @Test fun earlyBird() = assertEquals(ListenerType.EARLY_BIRD, type(stats(earlyShare = 0.51f)))
    @Test fun regular() = assertEquals(ListenerType.REGULAR, type(stats()))

    @Test fun earlierRuleWinsWhenTwoMatch() =
        assertEquals(ListenerType.NIGHT_REPLAYER, type(stats(nightShare = 0.4f, replayedSongs = 9, songs = 10, newSongs = 9)))

    @Test fun regularIsNamedByTheUsualTimeOfDay() {
        val hours = List(24) { if (it == 20) 100L else 1L }
        assertEquals("The Evening Regular", typeTitle(stats(hours = hours)))
        assertEquals("The Explorer", typeTitle(stats(songs = 10, newSongs = 6, hours = hours)))
    }

    @Test fun factsAreThreeAndContainTheRealNumbers() {
        val s = stats(
            songs = 10, plays = 321, seconds = 90_000, daySeconds = listOf(0, 9 * 3600L), hours = List(24) { if (it == 3) 50L else 1L },
            longestStreak = 41, newSongs = 7, replayedSongs = 12, topArtist = ArtistShare("Someone", 0.4f),
            earlyShare = 0.6f, nightShare = 0.5f, age = ageStats(typicalYear = 1995, oldestYear = 1971, songsWithYear = 40, songsTotal = 50),
        )
        ListenerType.entries.forEach { t ->
            val f = typeFacts(s, t)
            assertEquals(t.name, 3, f.size)
            f.forEach { assertTrue("$t: $it", it.any(Char::isDigit)); assertFalse(it.contains("NaN")) }
        }
        assertTrue(typeFacts(s, ListenerType.STREAK_KEEPER)[0].contains("41"))
        assertTrue(typeFacts(s, ListenerType.NIGHT_REPLAYER)[0].contains("50%"))
    }

    @Test fun factsSurviveEmptyStats() = ListenerType.entries.forEach { t ->
        typeFacts(stats(), t).also { assertEquals(3, it.size) }.forEach { assertFalse(it.contains("NaN")) }
    }
}
