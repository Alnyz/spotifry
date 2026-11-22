package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEntitiesTest {
    @Test fun webCodesBecomeTheirCharacters() =
        assertEquals("don't 'bout \"me\" & you <3 é", decodeEntities("don&#x27;t &#39;bout &quot;me&quot; &amp; you &lt;3 &#233;"))

    @Test fun unknownOrBrokenCodesStay() = assertEquals("a &bogus; b &#xZZ; c", decodeEntities("a &bogus; b &#xZZ; c"))

    @Test fun everyKindOfLyricsIsCleaned() {
        val plain = Lyrics.Plain(listOf("don&#x27;t")).cleaned() as Lyrics.Plain
        assertEquals(listOf("don't"), plain.lines)
        val timed = Lyrics.Timed(listOf(LyricLine(0, "it&#x27;s", listOf(LyricWord(0, 1, "it&#x27;s"))))).cleaned() as Lyrics.Timed
        assertEquals("it's", timed.lines[0].text); assertEquals("it's", timed.lines[0].words[0].text)
    }
}
