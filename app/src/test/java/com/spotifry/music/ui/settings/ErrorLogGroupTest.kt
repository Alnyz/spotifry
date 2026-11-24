package com.spotifry.music.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.BuildConfig
import com.spotifry.music.data.ErrorLog
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorLogGroupTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val log = ErrorLog(File(Files.createTempDirectory("errorlog").toFile(), "error_log.txt"))

    @Test fun copiesTheLogWithTheVersionsOnTop() {
        log.add("player", "song=abc ERROR_CODE_IO_BAD_HTTP_STATUS")
        compose.setContent { MusicTheme { ErrorLogGroup(log) } }
        compose.onNodeWithText("Copy error log").performClick()
        val copied = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.startsWith("Spotifry ${BuildConfig.VERSION_NAME} · NewPipe Extractor "))
        assertTrue(copied.endsWith("player: song=abc ERROR_CODE_IO_BAD_HTTP_STATUS\n"))
    }

    @Test fun clearEmptiesTheLogAndHidesItself() {
        log.add("player", "failed")
        compose.setContent { MusicTheme { ErrorLogGroup(log) } }
        compose.onNodeWithText("Clear error log").performClick()
        assertEquals("", log.text())
        compose.onNodeWithText("Clear error log").assertDoesNotExist()
        compose.onNodeWithText("Nothing went wrong lately.").assertExists()
    }
}
