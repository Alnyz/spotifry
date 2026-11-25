package com.spotifry.music.player

import android.os.Bundle
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionCommandsTest {
    @Test fun androidAutoMayBrowseTheLibrary() {
        // without these, Android Auto's request for the library root was refused and it kept reconnecting
        val commands = PlaybackService.sessionCommands(browse = true)
        listOf(
            SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM,
        ).forEach { assertTrue("command $it", commands.contains(it)) }
    }

    @Test fun otherAppsMayNotBrowseTheLibrary() {
        val commands = PlaybackService.sessionCommands(browse = false)
        assertFalse(commands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT))
        assertFalse(commands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
    }

    @Test fun theAppsOwnCommandsStayAllowed() {
        val commands = PlaybackService.sessionCommands(browse = true)
        assertTrue(commands.contains(SessionCommand(PlaybackService.SMART_SHUFFLE, Bundle.EMPTY)))
        assertTrue(commands.contains(SessionCommand(PlaybackService.SET_UPCOMING, Bundle.EMPTY)))
    }
}
