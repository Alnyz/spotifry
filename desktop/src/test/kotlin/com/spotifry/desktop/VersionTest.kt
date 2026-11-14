package com.spotifry.desktop

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals

class VersionTest {
    /** The installer's version and the one the app reports must be the same, or every new install offers an update to itself. */
    @Test fun appVersionMatchesInstallerVersion() {
        val packaged = Regex("packageVersion = \"([^\"]+)\"").find(File("build.gradle.kts").readText())!!.groupValues[1]
        assertEquals(packaged, desktopVersion)
    }
}
