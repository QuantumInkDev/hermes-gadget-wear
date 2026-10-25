package dev.quantumink.hermesgadget.wear

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetCacheQuotaTest {
    private fun pet(root: File, name: String, age: Long, vararg sizes: Int): File =
        File(root, name).apply {
            mkdirs()
            File(this, "manifest.json").apply { writeText("{}") }.setLastModified(1_000)
            sizes.forEachIndexed { index, size ->
                File(this, "sheet$index.png").apply {
                    writeBytes(ByteArray(size))
                }.setLastModified(age)
            }
        }

    @Test
    fun evictsWholeLeastRecentlyUsedPetsAndKeepsManifestsWithTheirSheets() {
        val root = Files.createTempDirectory("pets").toFile()
        val old = pet(root, "old", 10_000, 400, 100)
        val used = pet(root, "used", 30_000, 400, 100)
        val fresh = pet(root, "fresh", 20_000, 400, 100)
        PetCacheQuota.evict(root, fresh, maxBytes = 1_200)
        assertFalse(old.exists())
        assertTrue(File(used, "manifest.json").isFile && File(fresh, "manifest.json").isFile)
        assertEquals(3, used.listFiles()!!.size)
    }

    @Test
    fun neverEvictsThePetBeingWritten() {
        val root = Files.createTempDirectory("pets").toFile()
        val writing = pet(root, "writing", 1, 2_000)
        PetCacheQuota.evict(root, writing, maxBytes = 100)
        assertTrue(File(writing, "manifest.json").isFile)
    }

    @Test
    fun elevenFullPetsFitTheDefaultQuota() {
        val root = Files.createTempDirectory("pets").toFile()
        val pets = (0 until 11).map { pet(root, "p$it", 1_000L + it, 2_097_152, 600_000) }
        PetCacheQuota.evict(root, pets.last())
        assertTrue(pets.all { File(it, "manifest.json").isFile })
    }
}
