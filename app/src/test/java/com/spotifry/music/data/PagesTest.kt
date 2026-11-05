package com.spotifry.music.data

import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.albumKind
import com.spotifry.music.data.source.from
import com.spotifry.music.data.source.linkOf
import com.spotifry.music.ui.home.pageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class PagesTest {
    private val artistUrl = "https://www.youtube.com/channel/UCr_iyUANcn9OX_yy9piYoLw"

    private fun image(url: String, h: Int) = Image(url, h, h, Image.ResolutionLevel.UNKNOWN)

    private fun album(name: String, by: String, byUrl: String) = PlaylistInfoItem(0, "https://music.youtube.com/playlist?list=OLAK$name", name).apply {
        uploaderName = by; uploaderUrl = byUrl
        thumbnails = listOf(image("https://yt3.googleusercontent.com/a=w120-h120", 120), image("https://yt3.googleusercontent.com/a=w544-h544", 544))
    }

    @Test fun albumBecomesALinkWithItsArtistAndBiggestCover() {
        assertEquals(
            PageLink(SearchType.ALBUMS, "https://music.youtube.com/playlist?list=OLAKKid A", "Kid A", "Radiohead", "https://yt3.googleusercontent.com/a=w544-h544"),
            linkOf(album("Kid A", "Radiohead", artistUrl), SearchType.ALBUMS),
        )
    }

    @Test fun artistLosesTopicEndingAndHasNoBy() {
        val ch = ChannelInfoItem(0, artistUrl, "Radiohead - Topic")
        assertEquals(PageLink(SearchType.ARTISTS, artistUrl, "Radiohead", "", null), linkOf(ch, SearchType.ARTISTS))
    }

    @Test fun songsAreNotPageLinks() {
        assertNull(linkOf(StreamInfoItem(0, "https://music.youtube.com/watch?v=abc", "Creep", StreamType.VIDEO_STREAM), SearchType.ALBUMS))
    }

    @Test fun artistPageKeepsOnlyWhatTheirChannelPutOut() {
        val own = StreamInfoItem(0, "https://music.youtube.com/watch?v=abc", "Creep", StreamType.VIDEO_STREAM).apply { uploaderUrl = artistUrl }
        val cover = StreamInfoItem(0, "https://music.youtube.com/watch?v=def", "Creep (cover)", StreamType.VIDEO_STREAM).apply { uploaderUrl = "https://www.youtube.com/channel/other" }
        val tribute = album("A Tribute to Radiohead", "HMG", "https://www.youtube.com/channel/hmg")
        val kidA = album("Kid A", "Radiohead", artistUrl)
        assertEquals(listOf("Creep", "Kid A"), listOf(own, cover, tribute, kidA).from(artistUrl).map { it.name })
    }

    @Test fun albumPageTitleSplitsIntoKindAndName() {
        assertEquals("Album" to "Kid A", albumKind("Album – Kid A"))
        assertEquals("Single" to "Creep", albumKind("Single – Creep"))
        assertEquals("EP" to "My Iron Lung", albumKind("EP – My Iron Lung"))
        assertEquals("Album" to "Hail – To the Thief", albumKind("Hail – To the Thief"))
    }

    @Test fun lineUnderThePageName() {
        val album = PageLink(SearchType.ALBUMS, "u", "Kid A", "Radiohead", null)
        assertEquals("Radiohead\nAlbum", pageLine(album, null))
        assertEquals("Radiohead\nSingle · 1 song", pageLine(album, PageContent("Creep", "Radiohead", "Single", null, listOf(testSong))))
        val list = PageLink(SearchType.PLAYLISTS, "u", "Greatest Hits", "Ali", null)
        assertEquals("by Ali · 2 songs", pageLine(list, PageContent("Greatest Hits", "Ali", "Playlist", null, listOf(testSong, testSong))))
        assertEquals("Artist", pageLine(PageLink(SearchType.ARTISTS, "u", "Radiohead", "", null), null))
    }
}
