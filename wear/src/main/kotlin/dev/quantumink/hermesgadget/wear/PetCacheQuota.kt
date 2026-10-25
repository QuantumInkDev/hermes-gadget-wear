package dev.quantumink.hermesgadget.wear

import java.io.File

/**
 * Whole-pet LRU for the pet cache. Each endpoint directory is one pet, aged by its newest file
 * (loading a pet touches its sheets). Evicting single files dropped manifests first, because a
 * manifest is written once and never touched, which emptied every pet but the newest.
 */
internal object PetCacheQuota {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_FILES = 256

    fun evict(root: File, keep: File?, maxBytes: Long = MAX_BYTES, maxFiles: Int = MAX_FILES) {
        // Measure before deleting: a deleted file reports length 0.
        class Pet(val directory: File, val bytes: Long, val files: Int, val used: Long)
        val pets = root.listFiles { file -> file.isDirectory }.orEmpty().map { directory ->
            val files = directory.walkTopDown().filter { it.isFile }.toList()
            Pet(
                directory,
                files.sumOf { it.length() },
                files.size,
                files.maxOfOrNull { it.lastModified() } ?: 0L
            )
        }
        var total = pets.sumOf { it.bytes }
        var count = pets.sumOf { it.files }
        for (pet in pets.filter { it.directory != keep }.sortedBy { it.used }) {
            if (total <= maxBytes && count <= maxFiles) break
            if (pet.directory.deleteRecursively()) {
                total -= pet.bytes
                count -= pet.files
            }
        }
    }
}
