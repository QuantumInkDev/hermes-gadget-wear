package dev.quantumink.hermesgadget.mobile.widgets

import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File

/** Credential-free device-local state. Every instance shares the process write lock. */
class HomeWidgetStore(private val directory: File) {
    fun config(id: Int): WidgetConfig? = synchronized(lock) {
        require(id > 0)
        read("config-$id.json")?.let { runCatching { WidgetJson.decodeConfig(it) }.getOrNull() }
    }

    fun save(id: Int, config: WidgetConfig, expectedRevision: Long): WidgetConfig =
        synchronized(lock) {
            require(id > 0)
            require((config(id)?.revision ?: 0L) == expectedRevision) {
                "Widget changed. Reopen its settings before saving."
            }
            val saved = config.copy(
                slots = config.slots.toList(),
                revision = Math.addExact(expectedRevision, 1)
            )
            write("config-$id.json", WidgetJson.encodeConfig(saved))
            saved
        }

    fun delete(id: Int) = synchronized(lock) {
        require(id > 0)
        AtomicFile(File(directory, "config-$id.json")).delete()
    }

    fun restore(oldIds: IntArray, newIds: IntArray, preset: HomeWidgetPreset) = synchronized(lock) {
        require(oldIds.size == newIds.size && oldIds.size <= 128)
        require((oldIds + newIds).all { it > 0 })
        require(oldIds.distinct().size == oldIds.size && newIds.distinct().size == newIds.size)
        require(oldIds.none { it in newIds })
        oldIds.zip(newIds).forEach { (old, new) ->
            val previous = config(old)
            if (previous?.preset == preset && !File(directory, "config-$new.json").exists()) {
                write("config-$new.json", WidgetJson.encodeConfig(previous))
                delete(old)
            }
        }
    }

    fun profiles(): List<WidgetProfile> = synchronized(lock) {
        read("profiles.json")?.let {
            runCatching { WidgetJson.decodeProfiles(it) }.getOrNull()
        }.orEmpty()
    }

    fun saveProfiles(profiles: List<WidgetProfile>) = synchronized(lock) {
        write("profiles.json", WidgetJson.encodeProfiles(profiles))
    }

    private fun read(name: String): String? = runCatching {
        AtomicFile(File(directory, name)).openRead().use { input ->
            val buffer = ByteArray(ProfileCatalog.MAX_BYTES + 1)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            require(used <= ProfileCatalog.MAX_BYTES)
            String(buffer, 0, used, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun write(name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ProfileCatalog.MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = Any()
    }
}
