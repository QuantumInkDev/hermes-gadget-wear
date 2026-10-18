package dev.quantumink.hermesgadget.wear

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class PresentationMode { PORTRAIT, PET }

/** Opaque page + endpoint identity. Neither diagnostic output nor disk contains the inputs. */
class PresentationKey private constructor(internal val digest: String) {
    override fun equals(other: Any?) = other is PresentationKey && digest == other.digest
    override fun hashCode() = digest.hashCode()
    override fun toString() = "PresentationKey(redacted)"
    companion object {
        fun of(page: String, endpoint: String?): PresentationKey {
            val bytes =
                "${page.length}:$page${endpoint?.length ?: -1}:${endpoint.orEmpty()}".toByteArray()
            return PresentationKey(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            )
        }
    }
}

class PresentationChoices internal constructor(
    internal val entries: Map<String, PresentationMode> = emptyMap()
) {
    operator fun get(key: PresentationKey) = entries[key.digest] ?: PresentationMode.PORTRAIT
    override fun toString() = "PresentationChoices(redacted)"
}

/** Private watch-local bounded map. Failed/corrupt reads never authorize replacement. */
class PresentationStore(
    private val file: File,
    private val replace: (File, File) -> Unit = { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }
) {
    fun read(): PresentationChoices = synchronized(lock) { load() }
    fun set(key: PresentationKey, mode: PresentationMode): PresentationChoices =
        synchronized(lock) {
            val current = load()
            if (key.digest !in current.entries && current.entries.size == 64) {
                throw IOException(
                    "Presentation storage is full (64 profiles). Existing choices were kept."
                )
            }
            val next = PresentationChoices(current.entries + (key.digest to mode))
            var temporary: File? = null
            try {
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                temporary = File(file.path + ".new")
                FileOutputStream(temporary).use { output ->
                    output.write(
                        (
                            HEADER + next.entries.entries.joinToString("\n", postfix = "\n") {
                                "${it.key}:${it.value.name}"
                            }
                            ).toByteArray()
                    )
                    output.fd.sync()
                }
                replace(temporary, file)
            } catch (_: Exception) {
                throw IOException(
                    "Could not save presentation. Check watch storage and retry; choices were kept."
                )
            } finally {
                temporary?.delete()
            }
            next
        }
    private fun load(): PresentationChoices {
        if (!file.exists()) return PresentationChoices()
        try {
            val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size in 1..MAX_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            require(text.startsWith(HEADER) && text.endsWith('\n'))
            val lines = text.removePrefix(HEADER).dropLast(1).split('\n')
            require(lines.size in 1..64)
            val entries = lines.associate { line ->
                val parts = line.split(':')
                require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")))
                parts[0] to PresentationMode.valueOf(parts[1])
            }
            require(entries.size == lines.size)
            return PresentationChoices(entries)
        } catch (_: Exception) {
            throw IOException(
                "Saved presentation needs attention. Restore the private watch store or contact support; bytes were kept."
            )
        }
    }
    companion object {
        private val lock = Any()
        private const val HEADER = "watch-presentation-v1\n"
        private const val MAX_BYTES = 8192
    }
}
