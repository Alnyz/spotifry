package com.spotifry.music.ui.names

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isToggleable
import com.spotifry.music.data.NameLook
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.Shown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NameStyleSheetTest {
    @get:Rule val compose = createComposeRule()
    private var picked: NameStyle? = null
    private var tint: Boolean? = null

    private fun show(onRename: (() -> Unit)?, official: OfficialChoice?, shown: Shown = Shown.Text(NameStyle.CLASSIC)) = compose.setContent {
        NameStyleSheet(
            "Late Night Drive", "24 songs", NameLook(), shown, null, null, official, Color.Red, {},
            onRename, { picked = it }, {}, {}, if (official != null) ({}) else null, {}, { tint = it }, {},
        )
    }

    @Test fun tappingAStyleReportsIt() {
        show({}, null)
        compose.onNodeWithText("Gothic").performScrollTo().performClick()
        assertEquals(NameStyle.GOTHIC, picked)
    }

    @Test fun renameOnlyWhenGiven() {
        show(null, null)
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun logoTabWithoutKeyOffersOnlyOwn() {
        show({}, null)
        compose.onNodeWithText("Logo").performClick()
        compose.onNodeWithText("Official logo").assertDoesNotExist()
        compose.onNodeWithText("Choose a picture").assertExists()
    }

    @Test fun artistLogoTabOffersOfficialAndOwn() {
        show(null, OfficialChoice(null))
        compose.onNodeWithText("Logo").performClick()
        compose.onNodeWithText("Official logo").assertExists()
        compose.onNodeWithText("Your own logo").assertExists()
    }

    @Test fun tintSwitchReports() {
        show({}, null)
        compose.onNodeWithText("Logo").performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertEquals(false, tint)
    }
}
