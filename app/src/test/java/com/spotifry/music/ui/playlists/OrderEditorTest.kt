package com.spotifry.music.ui.playlists

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrderEditorTest {
    @get:Rule val compose = createComposeRule()
    private val songs = listOf("A", "B", "C").map { SongEntity(it, "Song $it", "Artist", 100, null, null, null, null) }

    private fun actions(description: String, run: String? = null): List<String> {
        val list = compose.onNodeWithContentDescription(description).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        if (run != null) compose.runOnIdle { list.first { it.label == run }.action() }
        return list.map { it.label }
    }

    @Test fun talkBackCanMoveASongAndDoneKeepsTheNewOrder() {
        var saved: List<String>? = null
        compose.setContent { MusicTheme { OrderEditor(songs, 0.dp) { saved = it } } }
        // the handle says what it moves, and the first and last only go one way
        assertEquals(listOf("Move down"), actions("Move Song A"))
        assertEquals(listOf("Move up", "Move down"), actions("Move Song B"))
        assertEquals(listOf("Move up"), actions("Move Song C"))
        actions("Move Song C", run = "Move up")
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("A", "C", "B"), saved)
    }

    @Test fun theHintTellsTalkBackUsersWhatToDo() {
        compose.setContent { MusicTheme { OrderEditor(songs, 0.dp) {} } }
        compose.onNodeWithText("With TalkBack, use Move up and Move down", substring = true).assertExists()
    }
}
