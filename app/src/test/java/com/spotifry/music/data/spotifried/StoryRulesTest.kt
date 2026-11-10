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

    private fun obs(plays: Int) = ObsessionDay(song("a"), java.time.LocalDate.of(2026, 3, 1), plays, emptyList())
    private fun month(m: Int) = MonthTop(java.time.YearMonth.of(2026, m), song("a"), 1)
    private val threeMonths = listOf(month(1), month(2), month(3))
    private val rich = stats(
        plays = 500, songs = 100, obsession = obs(9), months = threeMonths,
        age = ageStats(songsWithYear = 30, songsTotal = 40),
    )

    @Test fun newInstallShowsNothing() {
        assertEquals(emptyList<CardId>(), shownCards(stats()))
        assertEquals(10, morePlaysNeeded(stats()))
    }

    @Test fun morePlaysNeededIsAtLeastOne() {
        assertEquals(3, morePlaysNeeded(stats(plays = 7)))
        assertEquals(1, morePlaysNeeded(stats(plays = 50)))
    }

    @Test fun richHistoryShowsAllEight() = assertEquals(CardId.entries, shownCards(rich))

    @Test fun lowHistoryShowsNoneNotThree() =
        assertEquals(emptyList<CardId>(), shownCards(stats(plays = 5)))

    @Test fun dayCardNeedsEightPlaysInOneDay() {
        assertFalse(CardId.DAY in shownCards(rich.copy(obsession = obs(7))))
        assertTrue(CardId.DAY in shownCards(rich.copy(obsession = obs(8))))
        assertFalse(CardId.DAY in shownCards(rich.copy(obsession = null)))
    }

    @Test fun monthsNeedThreeMonths() {
        assertFalse(CardId.MONTHS in shownCards(rich.copy(months = threeMonths.take(2))))
        assertTrue(CardId.MONTHS in shownCards(rich))
    }

    @Test fun topSongAndHoursNeedTenPlays() {
        val nine = shownCards(rich.copy(plays = 9))
        assertFalse(CardId.TOP_SONG in nine); assertFalse(CardId.HOURS in nine)
        assertTrue(CardId.TOP_SONG in shownCards(rich.copy(plays = 10)))
    }

    private fun ageShown(w: Int, total: Int, unchecked: Int) =
        CardId.AGE in shownCards(rich.copy(age = ageStats(songsWithYear = w, songsTotal = total, unchecked = unchecked)))

    @Test fun ageCardShownWhileYearsAreStillBeingFound() = assertTrue(ageShown(5, 25, 20))
    @Test fun ageCardHiddenWhenLookupFinishedWithTooFewYears() = assertFalse(ageShown(5, 25, 0))
    @Test fun ageCardHiddenWhenTooFewSongsEvenIfUnchecked() = assertFalse(ageShown(0, 10, 10))
    @Test fun ageCardShownWithTwentyYears() = assertTrue(ageShown(20, 20, 0))

    private fun facts(s: SpotifriedStats, t: ListenerType) = typeFacts(s, t)

    @Test fun singularWording() {
        val s = stats(songs = 1, plays = 1, seconds = 3600, newSongs = 1, replayedSongs = 1, longestStreak = 1, topArtist = ArtistShare("A", 1f))
        ListenerType.entries.forEach { t ->
            facts(s, t).forEach { f ->
                listOf("1 songs", "1 plays", "1 days", "1 hours", "1 different songs", "1 of your 1 songs").forEach { assertFalse("$t: $f", f.contains(it)) }
            }
        }
        assertTrue(facts(s, ListenerType.EXPLORER)[0].contains("1 of 1 song was new"))
        assertTrue(facts(s, ListenerType.EXPLORER)[2].contains("Only 1 song got a replay"))
    }

    @Test fun zeroWording() {
        val s = stats()
        ListenerType.entries.forEach { t ->
            facts(s, t).forEach { f ->
                listOf("0 songs got a replay", "replayed 0", "0 of them").forEach { assertFalse("$t: $f", f.contains(it)) }
            }
        }
        assertEquals("None of your songs got a replay.", facts(s, ListenerType.EXPLORER)[2])
        assertEquals("You never replayed any of them.", facts(s, ListenerType.LOYALIST)[2])
        assertEquals("You never replayed a song.", facts(s, ListenerType.NIGHT_REPLAYER)[1])
    }

    @Test fun decimalsUseAPointWhateverTheLocale() {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val f = facts(stats(daySeconds = listOf(0, 8 * 3600L + 1800)), ListenerType.MARATHONER)[0]
            assertTrue(f, f.contains("8.5 hours"))
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun regularFactStatesTheBusiestHour() {
        val hours = List(24) { if (it == 20) 100L else 1L }
        assertEquals("Your busiest hour was 8 p.m.", facts(stats(hours = hours), ListenerType.REGULAR)[0])
    }

    @Test fun streakFactsStandOnTheirOwn() {
        val f = facts(stats(plays = 321, longestStreak = 41), ListenerType.STREAK_KEEPER)
        assertFalse(f[1].startsWith("That"))
        assertTrue(f[1].contains("321"))
    }
}
