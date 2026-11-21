package com.spotifry.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityUpgraderTest {
    @Test fun `a song kept from YouTube is below Lossless and JioSaavn, a lossless one is below nothing`() {
        assertTrue(isBelow("youtube", 3))
        assertTrue(isBelow(null, 2)) // no note means YouTube
        assertTrue(isBelow("saavn", 3))
        assertFalse(isBelow("monochrome", 3))
        assertFalse(isBelow("youtube", 1)) // nothing better allowed, so nothing to forget
    }

    @Test fun `a download is whole only when it ends at the length it announced`() {
        val data = ByteArray(200_000) { it.toByte() }
        fun read(from: ByteArray, upTo: Int): (ByteArray) -> Int { var at = 0; return { b -> if (at >= upTo) -1 else minOf(b.size, upTo - at).also { System.arraycopy(from, at, b, 0, it); at += it } } }
        val f = java.io.File.createTempFile("copy", ".part")
        assertTrue(fetchWhole(data.size.toLong(), read(data, data.size), f) { true })
        assertTrue(java.util.Arrays.equals(data, f.readBytes()))
        assertFalse(fetchWhole(data.size.toLong(), read(data, 150_000), f) { true }) // cut short
        assertTrue(fetchWhole(-1, read(data, 1_000), f) { true }) // length not announced: what came is taken
        f.delete()
    }

    @Test fun `whole better copies outlive a restart, cut downloads and copies no better than the stored one go`() {
        val dir = kotlin.io.path.createTempDirectory("upgrades").toFile()
        fun put(f: java.io.File) = f.apply { writeText("x") }
        val lossless = put(betterFile(dir, "a/b.c", "monochrome")) // an id with a slash and a dot comes back whole
        val poorer = put(betterFile(dir, "a/b.c", "saavn")) // the same song's poorer copy
        val part = put(java.io.File(betterFile(dir, "cut", "monochrome").path + ".part"))
        val notBetter = put(betterFile(dir, "kept", "saavn"))
        val old = put(java.io.File(dir, "123.copy")) // named the old way
        val stored = mapOf("kept" to "saavn")
        val ready = waitingCopies(dir) { stored[it] }
        assertEquals(mapOf("a/b.c" to (lossless to "monochrome")), ready)
        assertTrue(lossless.exists())
        for (f in listOf(poorer, part, notBetter, old)) assertFalse(f.name, f.exists())
        dir.deleteRecursively()
    }
}
