package com.spotifry.music.ui.saved

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.SongRow

@Composable
fun SavedScreen(vm: SavedViewModel, onFindSong: () -> Unit, onMore: (Song) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    SavedContent(saved, onFindSong, vm::play, onMore, modifier, bottomInset)
}

@Composable
fun SavedContent(
    saved: List<SongEntity>, onFindSong: () -> Unit, onPlay: (List<Song>, Int) -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item {
            ScreenHeader("Saved", subtitle = if (saved.isEmpty()) null else "${saved.size} ${if (saved.size == 1) "song" else "songs"} · plays without internet")
        }
        if (saved.isEmpty()) {
            item {
                EmptyState(
                    "Nothing saved yet",
                    "Open any song and tap “Save on this phone”. It will play here without internet.",
                    "Find a song to save", onFindSong,
                )
            }
        } else {
            val songs = saved.map { it.toSong() }
            item {
                PlayButtons({ onPlay(songs, 0) }, { onPlay(songs.shuffled(), 0) }, Modifier.padding(start = 20.dp, top = 16.dp))
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(saved, key = { _, s -> s.id }) { i, s ->
                SongRow(songs[i], true, { onPlay(songs, i) }, { onMore(songs[i]) }, Modifier.padding(start = 20.dp, end = 8.dp), localCover = s.thumbPath)
            }
        }
    }
}
