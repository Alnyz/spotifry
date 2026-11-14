package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilePrefsTest {
    @Test fun valuesComeBackWithTheirTypes() {
        val f = File.createTempFile("prefs", ".json").also { it.deleteOnExit() }
        FilePrefs(f).edit().putBoolean("b", true).putInt("i", 6).putLong("l", 1L shl 40).putFloat("f", 1.5f).putString("s", "x").putStringSet("set", setOf("a", "b")).apply()
        val again = FilePrefs(f)
        assertEquals(true, again.getBoolean("b", false)); assertEquals(6, again.getInt("i", 0)); assertEquals(1L shl 40, again.getLong("l", 0))
        assertEquals(1.5f, again.getFloat("f", 0f)); assertEquals("x", again.getString("s", null)); assertEquals(setOf("a", "b"), again.getStringSet("set", null))
        // a type that was written as an Int must still read as an Int, which the phone's backup relies on
        assertTrue(again.all["i"] is Int); assertTrue(again.all["f"] is Float)
    }

    @Test fun aMissingOrBrokenFileMeansEmpty() {
        assertEquals(emptyMap<String, Any?>(), FilePrefs(File("/nonexistent/x.json")).all)
        val broken = File.createTempFile("prefs", ".json").also { it.writeText("{nope"); it.deleteOnExit() }
        assertEquals(emptyMap<String, Any?>(), FilePrefs(broken).all)
    }

    @Test fun removeAndClearReachTheFile() {
        val f = File.createTempFile("prefs", ".json").also { it.deleteOnExit() }
        val p = FilePrefs(f); p.edit().putInt("a", 1).putInt("b", 2).apply(); p.edit().remove("a").apply()
        assertEquals(setOf("b"), FilePrefs(f).all.keys)
        p.edit().clear().apply(); assertEquals(emptySet<String>(), FilePrefs(f).all.keys)
    }
}
