package dev.quantumink.hermesgadget.mobile.widgets

import android.util.AtomicFile
import dev.quantumink.hermesgadget.mobile.SetupTransferGate
import dev.quantumink.hermesgadget.protocol.QuickActions
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A portable widget layout that is not placed on any launcher. It never carries an Android
 * appWidgetId, provider class or PendingIntent; choosing it fills a new widget's editor draft.
 */
data class WidgetTemplate(
    val id: String,
    val preset: HomeWidgetPreset,
    val slots: List<String?>,
    val appearance: WidgetAppearance
) {
    init {
        require(QuickActions.validId(id) && slots.size == preset.capacity)
        val keys = slots.filterNotNull()
        require(keys.all(::validWidgetKey) && keys.distinct().size == keys.size)
    }
    override fun toString(): String = "WidgetTemplate(preset=$preset, content=redacted)"
}

/** Credential-free local list of imported templates, separate from launcher instance state. */
class HomeWidgetTemplateStore(private val directory: File) {
    private val file get() = AtomicFile(File(directory, "templates.json"))

    /** Missing means none; unreadable data throws so it is never silently overwritten. */
    fun templates(): List<WidgetTemplate> = synchronized(lock) { read() }

    fun save(templates: List<WidgetTemplate>) = SetupTransferGate.mutation {
        synchronized(lock) {
            read() // Refuse to replace unreadable data.
            write(templates)
        }
    }

    fun remove(id: String) = SetupTransferGate.mutation {
        synchronized(lock) { write(read().filterNot { it.id == id }) }
    }

    /** Journal recovery only: replaces whatever is present with a previously encoded list. */
    internal fun restore(templates: List<WidgetTemplate>) = SetupTransferGate.mutation {
        synchronized(lock) { write(templates) }
    }

    private fun read(): List<WidgetTemplate> {
        val atomic = file
        if (!atomic.baseFile.exists() && !File(directory, "templates.json.bak").exists()) {
            return emptyList()
        }
        val text = atomic.openRead().use { input ->
            require(input.channel.size() in 1..MAX_BYTES.toLong())
            input.readBytes().toString(Charsets.UTF_8)
        }
        return decode(text)
    }

    private fun write(templates: List<WidgetTemplate>) {
        val bytes = encode(templates).toByteArray(Charsets.UTF_8)
        check(directory.isDirectory || directory.mkdirs())
        val atomic = file
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        const val MAX_TEMPLATES = 128
        private const val MAX_BYTES = 512 * 1024
        private val lock = Any()

        fun encode(templates: List<WidgetTemplate>): String {
            require(templates.size <= MAX_TEMPLATES)
            require(templates.map { it.id }.distinct().size == templates.size)
            return buildJsonObject {
                put("version", 1)
                put(
                    "templates",
                    JsonArray(
                        templates.map { template ->
                            buildJsonObject {
                                put("id", template.id)
                                put("preset", template.preset.name)
                                put("appearance", template.appearance.name)
                                put(
                                    "slots",
                                    JsonArray(
                                        template.slots.map { it?.let(::JsonPrimitive) ?: JsonNull }
                                    )
                                )
                            }
                        }
                    )
                )
            }.toString().also { require(it.toByteArray().size <= MAX_BYTES) }
        }

        fun decode(text: String): List<WidgetTemplate> = try {
            val item = Json.parseToJsonElement(text) as JsonObject
            require(item.keys == setOf("version", "templates"))
            require((item["version"] as JsonPrimitive).let { !it.isString && it.content == "1" })
            (item["templates"] as JsonArray).also { require(it.size <= MAX_TEMPLATES) }.map {
                val template = it as JsonObject
                require(template.keys == setOf("id", "preset", "appearance", "slots"))
                fun string(key: String) = (template.getValue(key) as JsonPrimitive).also { value ->
                    require(value.isString)
                }.content
                WidgetTemplate(
                    string("id"),
                    HomeWidgetPreset.valueOf(string("preset")),
                    (template.getValue("slots") as JsonArray).map { value ->
                        if (value == JsonNull) {
                            null
                        } else {
                            (value as JsonPrimitive).also { slot -> require(slot.isString) }.content
                        }
                    },
                    WidgetAppearance.valueOf(string("appearance"))
                )
            }.also { encode(it) }
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid local widget templates.", error)
        }
    }
}
