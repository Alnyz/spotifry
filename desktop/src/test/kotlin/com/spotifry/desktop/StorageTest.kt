package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StorageTest {
    private fun File.fill(name: String, bytes: Int) = resolve(name).also { it.parentFile.mkdirs() }.writeBytes(ByteArray(bytes))

    @Test fun eachFolderCountsOnItsOwn() {
        val dir = java.nio.file.Files.createTempDirectory("spotifry-storage").toFile()
        dir.fill("songs/a.m4a", 1000); dir.fill("songs/b.webm", 500); dir.fill("songs/a.jpg", 70)
        dir.fill("replay/x.m4a", 300); dir.fill("replay/parts.json", 20)
        dir.fill("music.db", 40); dir.fill("music.db-wal", 4); dir.fill("settings.json", 6); dir.fill("autoeq/big.txt", 9999)
        val use = measureStorage(dir)
        assertEquals(StorageUse(songs = 1500, covers = 70, replay = 320, database = 50), use)
        assertEquals(1940, use.total)
    }

    @Test fun anEmptyFolderIsZero() = assertEquals(0L, measureStorage(java.nio.file.Files.createTempDirectory("spotifry-storage").toFile()).total)

    @Test fun sizesReadInTheRightUnit() {
        assertEquals("512 KB", sizeText(512L shl 10)); assertEquals("612 MB", sizeText(612L shl 20)); assertEquals("1.5 GB", sizeText(3L shl 29))
    }
}
