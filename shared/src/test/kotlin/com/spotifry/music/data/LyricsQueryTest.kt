package com.spotifry.music.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsQueryTest {
    @Test fun channelLabelsComeOffTheArtist() {
        assertEquals("Nella Kharisma", artistForLyrics("Nella Kharisma Official"))
        assertEquals("AronChupa", artistForLyrics("AronChupa VEVO"))
        assertEquals("Indila", artistForLyrics("Indila - Topic"))
        assertEquals("Official", artistForLyrics("Official"))
    }

    @Test fun uploadPackagingComesOffTheTitle() {
        assertEquals("Konco Mesra", titleForLyrics("Konco Mesra (Official Music Video)", "Nella Kharisma"))
        assertEquals("Dracula", titleForLyrics("Dracula (feat. JENNIE)", "Tame Impala"))
        assertEquals("I'm an Albatraoz", titleForLyrics("AronChupa, Little Sis Nora - I'm an Albatraoz | OFFICIAL VIDEO", "AronChupa & Little Sis Nora"))
    }

    @Test fun anotherRecordingKeepsItsName() {
        assertEquals("Song (Remix)", titleForLyrics("Song (Remix) [HD]", "Artist"))
        assertEquals("Song (Live)", titleForLyrics("Song (Live)", "Artist"))
        // the part before a dash stays when it isn't the artist
        assertEquals("Love - Remix", titleForLyrics("Love - Remix", "Someone Else"))
    }

    @Test fun aTitleThatWasOnlyPackagingIsKept() {
        assertEquals("(Official Video)", titleForLyrics("(Official Video)", "Artist"))
    }

    @Test fun kuGouHashesAreThisSongsNearestCutsWithin8Seconds() {
        val lists = JSONArray("""[
            {"SongName":"Konco Mesra","SingerName":"Nella Kharisma","Duration":330,"FileHash":"far"},
            {"SongName":"Konco Mesra","SingerName":"Someone Else","Duration":301,"FileHash":"other artist"},
            {"SongName":"Menggapai Cinta","SingerName":"Nella Kharisma","Duration":301,"FileHash":"other song"},
            {"SongName":"Konco Mesra","SingerName":"Nella Kharisma","Duration":305,"FileHash":"close"},
            {"SongName":"Konco Mesra","SingerName":"Nella Kharisma、Various Artists","Duration":301,"FileHash":"near"}]""")
        assertEquals(listOf("near", "close"), LyricsSource.kugouHashes(lists, "Konco Mesra", "Nella Kharisma", 301))
    }

    @Test fun kuGouCandidatesNeedTheSameTitleArtistAndLength() {
        val c = JSONArray("""[
            {"id":"1","song":"Konco Mesra","singer":"Nella Kharisma","duration":309000},
            {"id":"2","song":"Konco Mesra","singer":"Nella Kharisma","duration":302000},
            {"id":"3","song":"Konco Mesra","singer":"Nella Kharisma","duration":301000},
            {"id":"4","song":"Another","singer":"Nella Kharisma","duration":301000}]""")
        assertEquals(listOf("3", "2"), LyricsSource.kugouPick(c, "Konco Mesra", "Nella Kharisma", 301).map { it.getString("id") })
    }

    @Test fun kuGouKaraokeFileIsReadWordByWord() {
        val krc = "[id:\$00000000]\n[ti:Konco Mesra]\n[343,177]<0,35,0>Konco <35,35,0>Mesra <70,36,0>- <106,35,0>Nella <141,36,0>Kharisma\n" +
            "[525,423]<0,35,0>Written <35,36,0>by<71,35,0>：<106,36,0>Angga\n[1375,3212]<0,211,0>Yen <211,225,0>tak <436,339,0>sawang\n"
        val lines = LyricsSource.parseKrc(krc)!!
        assertEquals(listOf("Yen tak sawang"), lines.map { it.text })
        // the word times count from the start of the line
        assertEquals(listOf(1375L, 1586L, 1811L), lines[0].words.map { it.startMs })
        assertEquals("Yen ", lines[0].words[0].text)
    }

    @Test fun kuGouFileDecodesFromItsWrapping() {
        val text = "[1,2]<0,1,0>hi"
        val key = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)
        val packed = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
        val wrapped = "krc1".toByteArray() + ByteArray(packed.size) { (packed[it].toInt() xor key[it % 16]).toByte() }
        assertEquals(text, LyricsSource.decodeKrc(java.util.Base64.getEncoder().encodeToString(wrapped)))
    }

    @Test fun lyricsOfAnotherEditionArePassedOver() {
        assertEquals(true, LyricsSource.otherEdition("[ti:Thriller(Single Version)]\n[00:01.00]x", "Thriller"))
        assertEquals(false, LyricsSource.otherEdition("[ti:Song (Live)]", "Song (Live)"))
        assertEquals(false, LyricsSource.otherEdition("[ti:Konco Mesra]", "Konco Mesra"))
    }

    @Test fun kuGouCreditLinesAreNotSung() {
        val lrc = "[00:00.00]Konco Mesra - Nella Kharisma\n[00:01.00]作词：Someone\n[00:02.00]作曲：Someone\n[00:10.00]first line\n[00:20.00]second line\n[00:30.00]制作人：Someone"
        assertEquals(listOf("first line", "second line"), LyricsSource.kugouLines(lrc)!!.map { it.text })
    }

    @Test fun kuGouPureMusicNoticeIsNoLyrics() {
        assertNull(LyricsSource.kugouLines("[00:00.00]纯音乐，请欣赏"))
    }

    private fun hit(title: String, artist: String, sec: Int) =
        org.json.JSONObject().put("trackName", title).put("artistName", artist).put("duration", sec).put("syncedLyrics", "[00:01.00] words")

    @Test fun titleOnlySearchNeedsTheSameTitleLengthAndAWordOfTheArtist() {
        val results = JSONArray().put(hit("Love Story", "Taylor Swift", 235)).put(hit("Love Story", "Indila", 180)).put(hit("Love Story", "Indila", 236))
        // the right artist at the wrong length, and the right length by another artist, are both left out
        assertNull(LyricsSource.pickSameTitle(results, "Love Story", "Someone Else", 235))
        assertEquals(1, (LyricsSource.pickSameTitle(results, "Love Story", "Indila Official", 236) as Lyrics.Timed).lines.size)
        assertNull(LyricsSource.pickSameTitle(results, "Love Story", "Indila Official", 200))
    }
}
