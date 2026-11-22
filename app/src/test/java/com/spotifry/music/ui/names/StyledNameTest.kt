package com.spotifry.music.ui.names

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.Shown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StyledNameTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyStyleShowsTheName() {
        var style by androidx.compose.runtime.mutableStateOf(NameStyle.CLASSIC)
        compose.setContent { StyledName("Radiohead", Shown.Text(style), null, Color.White, Color.Red, 50.sp) }
        NameStyle.entries.forEach { s ->
            style = s
            compose.waitForIdle()
            val shown = if (s.caps) "RADIOHEAD" else "Radiohead"
            compose.onNodeWithText(shown).assertExists()
        }
    }

    @Test fun missingLogoFileShowsTheTextStyle() {
        compose.setContent { StyledName("Radiohead", Shown.Own("gone.png", true), null, Color.White, Color.Red, 50.sp, fallback = NameStyle.GOTHIC) }
        compose.onNodeWithText("Radiohead").assertExists()
    }

    @Test fun longNameStaysTwoLines() {
        val long = "Very long name ".repeat(8).trim()
        compose.setContent { StyledName(long, Shown.Text(NameStyle.BLOCK), null, Color.White, Color.Red, 50.sp) }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long.uppercase()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertTrue(results.single().lineCount <= 2)
    }
}
