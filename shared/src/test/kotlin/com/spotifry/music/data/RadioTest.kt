package com.spotifry.music.data

import com.spotifry.music.data.source.parseStations
import com.spotifry.music.data.source.toSong
import com.spotifry.music.data.source.isRadio
import org.junit.Assert.*
import org.junit.Test

class RadioTest {
    private val json = """[
      {"stationuuid":"a1","name":" Hits FM ","url_resolved":"http://s.example/live.mp3","url":"http://old","favicon":"https://x/y.png","country":"Indonesia","tags":"pop, hits,top40,extra","codec":"MP3","bitrate":128},
      {"stationuuid":"a2","name":"No address","url_resolved":"","url":"","country":"X"},
      {"stationuuid":"a3","name":"","url_resolved":"http://z","country":"X"},
      {"stationuuid":"a4","name":"Plain","url_resolved":"","url":"https://p.example/stream","favicon":"","country":"","tags":""}
    ]"""

    @Test fun keepsPlayableStationsOnly() {
        val s = parseStations(json)
        assertEquals(listOf("a1", "a4"), s.map { it.uuid })
        assertEquals("Hits FM", s[0].name)
        assertEquals("pop,hits,top40", s[0].tags)
        assertNull(s[1].logo)
        assertEquals("https://p.example/stream", s[1].url)
    }

    @Test fun aStationIsASongWithNoLength() {
        val song = parseStations(json)[0].toSong()
        assertEquals("radio:a1", song.id)
        assertEquals(0L, song.durationSec)
        assertEquals("Indonesia · pop", song.artist)
        assertEquals("http://s.example/live.mp3", song.streamUrl)
        assertTrue(song.isRadio)
    }

    @Test fun favouritesToggleAndSurviveAReload() {
        val prefs = MemoryPrefs()
        val st = parseStations(json)
        val fav = RadioFavourites(prefs)
        fav.toggle(st[0]); fav.toggle(st[1])
        assertEquals(listOf("a4", "a1"), fav.stations.value.map { it.uuid })
        fav.toggle(st[0])
        assertEquals(listOf("a4"), RadioFavourites(prefs).stations.value.map { it.uuid })
    }
}

class RadioServersTest {
    @Test fun keepsOnlyTheListsOwnServersOnceEach() {
        val found = com.spotifry.music.data.source.serverNames(
            listOf("de1.api.radio-browser.info.", "95.217.1.2", "fi1.api.radio-browser.info", "de1.api.radio-browser.info", "example.com"),
            kotlin.random.Random(1),
        )
        assertEquals(setOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info"), found.toSet())
        assertEquals(2, found.size)
    }

    @Test fun noServerNamesMeansEmpty() =
        assertTrue(com.spotifry.music.data.source.serverNames(listOf("1.2.3.4")).isEmpty())
}
