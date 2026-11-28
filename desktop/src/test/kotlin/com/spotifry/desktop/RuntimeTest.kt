package com.spotifry.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

/** The Discord bot's voice encryption (JDAVE) needs Java 25 or newer at run time. */
class RuntimeTest {
    @Test fun runsOnJava25() = assertTrue("runs on Java ${Runtime.version().feature()}", Runtime.version().feature() >= 25)
}
