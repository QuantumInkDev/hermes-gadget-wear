package dev.quantumink.hermesgadget.mobile.widgets

import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Credential-free state. A single atomic config map also makes ID restoration atomic. */
class HomeWidgetStore(private val directory: File) {
    fun config(id: Int): WidgetConfig? = synchronized(lock) {
        require(id > 0)
        configs()[id]
    }

    fun save(id: Int, config: WidgetConfig, expectedRevision: Long): WidgetConfig =
        synchronized(lock) {
            require(id > 0)
            val previous = configs()
            require((previous[id]?.revision ?: 0L) == expectedRevision) {
                "Widget changed. Reopen its settings before saving."
            }
            val saved = config.copy(
                slots = config.slots.toList(),
                revision = Math.addExact(expectedRevision, 1)
            )
            saveConfigs(previous + (id to saved))
            saved
        }

    fun delete(id: Int) = synchronized(lock) {
        require(id > 0)
        val previous = configs()
        if (id in previous) saveConfigs(previous - id)
    }

    fun restore(oldIds: IntArray, newIds: IntArray, preset: HomeWidgetPreset) = synchronized(lock) {
        require(oldIds.size == newIds.size && oldIds.size <= 128)
        require((oldIds + newIds).all { it > 0 })
        require(oldIds.distinct().size == oldIds.size && newIds.distinct().size == newIds.size)
        val before = configs()
        var moves = oldIds.zip(newIds).filter { (old, new) ->
            before[old]?.preset == preset && (
                new !in before ||
                    (new in oldIds && before[new]?.preset == preset)
                )
        }
        // Removing a blocked terminal move can make earlier overlapping moves unsafe too.
        // Prune to a fixed point before assigning anything from the original snapshot.
        while (true) {
            val relocated = moves.map { it.first }.toSet()
            val safe = moves.filter { (old, new) ->
                old == new || new !in before || new in relocated
            }
            if (safe.size == moves.size) break
            moves = safe
        }
        val after = before.toMutableMap()
        moves.forEach { (old, new) ->
            val original = before.getValue(old)
            val occupied = before[new]
            after[new] = if (old != new && occupied != null) {
                original.copy(
                    revision = Math.addExact(maxOf(original.revision, occupied.revision), 1)
                )
            } else {
                original
            }
        }
        val destinations = moves.map { it.second }.toSet()
        moves.filter { it.first !in destinations }.forEach { after.remove(it.first) }
        if (moves.isNotEmpty()) saveConfigs(after)
    }

    fun profiles(): List<WidgetProfile> = synchronized(lock) {
        read("profiles.json", ProfileCatalog.MAX_BYTES)?.let {
            runCatching { WidgetJson.decodeProfiles(it) }.getOrNull()
        }.orEmpty()
    }

    fun saveProfiles(profiles: List<WidgetProfile>) = synchronized(lock) {
        write("profiles.json", WidgetJson.encodeProfiles(profiles), ProfileCatalog.MAX_BYTES)
    }

    private fun configs(): Map<Int, WidgetConfig> {
        val destination = File(directory, "configs.json")
        if (destination.exists() || File(directory, "configs.json.bak").exists()) {
            return runCatching {
                val objectValue = Json.parseToJsonElement(
                    requireNotNull(read("configs.json", MAX_CONFIG_BYTES))
                ) as JsonObject
                require(objectValue.keys == setOf("version", "instances"))
                val version = objectValue["version"] as JsonPrimitive
                require(!version.isString && version.content == "1")
                val items = objectValue["instances"] as JsonObject
                require(items.size <= 128)
                items.entries.associate { (key, value) ->
                    val id = key.toInt()
                    require(id > 0 && id.toString() == key)
                    id to WidgetJson.decodeConfig(value.toString())
                }
            }.getOrDefault(emptyMap())
        }
        // Preserve builds from before bulk restoration became an atomic map.
        val legacy = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("config-[1-9][0-9]{0,9}\\.json"))
        }
        if (legacy.size > 128) return emptyMap()
        return legacy.mapNotNull { file ->
            runCatching {
                val id = file.name.removePrefix("config-").removeSuffix(".json").toInt()
                id to
                    WidgetJson.decodeConfig(
                        requireNotNull(read(file.name, ProfileCatalog.MAX_BYTES))
                    )
            }.getOrNull()
        }.toMap()
    }

    private fun saveConfigs(configs: Map<Int, WidgetConfig>) {
        require(configs.size <= 128 && configs.keys.all { it > 0 })
        val text = buildJsonObject {
            put("version", 1)
            put(
                "instances",
                JsonObject(
                    configs.mapKeys { it.key.toString() }.mapValues {
                        Json.parseToJsonElement(WidgetJson.encodeConfig(it.value))
                    }
                )
            )
        }.toString()
        // Invalidate in-flight launches before the new configuration can become visible on disk.
        changes.incrementAndGet()
        write("configs.json", text, MAX_CONFIG_BYTES)
        directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("config-[1-9][0-9]{0,9}\\.json"))
        }.forEach { AtomicFile(it).delete() }
    }

    private fun read(name: String, limit: Int): String? = runCatching {
        AtomicFile(File(directory, name)).openRead().use { input ->
            val size = input.channel.size()
            require(size in 0..limit.toLong())
            val buffer = ByteArray(size.toInt() + 1)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            require(used <= limit)
            String(buffer, 0, used, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun write(name: String, text: String, limit: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit)
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
        private val changes = AtomicLong()
        private const val MAX_CONFIG_BYTES = 1024 * 1024
        fun version(): Long = changes.get()
    }
}
