package dev.quantumink.hermesgadget.mobile.widgets

import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Labels use rows × columns; Android provider metadata uses columns × rows. */
enum class HomeWidgetPreset(
    val rows: Int,
    val columns: Int,
    val provider: String,
    val label: String
) {
    FOCUS(1, 1, "FocusHomeWidget", "1×1 · Focus"),
    FOUR(2, 2, "FourHomeWidget", "2×2 · Four bots"),
    EIGHT(2, 4, "EightHomeWidget", "2×4 · Eight bots"),
    STRIP_FOUR(1, 4, "FourStripHomeWidget", "1×4 · Four-bot strip"),
    SIXTEEN(4, 4, "SixteenHomeWidget", "4×4 · Sixteen bots"),
    STRIP_FIVE(1, 5, "FiveStripHomeWidget", "1×5 · Five-bot strip"),
    TEN(2, 5, "TenHomeWidget", "2×5 · Ten bots");

    val capacity: Int get() = rows * columns
    val className: String get() = "dev.quantumink.hermesgadget.mobile.widgets.$provider"
}

enum class WidgetAppearance(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    MOONLIGHT("Electronic Moonlight")
}

data class WidgetConfig(
    val preset: HomeWidgetPreset,
    val slots: List<String?> = List(preset.capacity) { null },
    val appearance: WidgetAppearance = WidgetAppearance.SYSTEM,
    val revision: Long = 0
) {
    init {
        require(slots.size == preset.capacity && revision >= 0)
        val keys = slots.filterNotNull()
        require(keys.all(::validWidgetKey) && keys.distinct().size == keys.size)
    }
    override fun toString(): String = "WidgetConfig(preset=$preset, content=redacted)"
}

data class WidgetProfile(
    val key: String,
    val name: String,
    val source: String,
    val status: CatalogStatus,
    val portraitHash: String?
) {
    init {
        require(validWidgetKey(key))
        require(
            listOf(name, source).all {
                it.isNotBlank() && it.length <= 32 &&
                    it.none(Char::isISOControl)
            }
        )
        require(portraitHash == null || validWidgetHash(portraitHash))
    }
    override fun toString(): String = "WidgetProfile(content=redacted)"
}

internal fun validWidgetKey(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}"))
internal fun validWidgetHash(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}"))

/** Small credential-free local projections, deliberately unrelated to endpoint vault formats. */
object WidgetJson {
    fun encodeConfig(value: WidgetConfig): String = buildJsonObject {
        put("version", 1)
        put("preset", value.preset.name)
        put("appearance", value.appearance.name)
        put("revision", value.revision)
        put("slots", JsonArray(value.slots.map { it?.let(::JsonPrimitive) ?: JsonNull }))
    }.toString().also(::bound)

    fun decodeConfig(text: String): WidgetConfig = parse(text) { item ->
        require(item.keys == setOf("version", "preset", "appearance", "revision", "slots"))
        WidgetConfig(
            HomeWidgetPreset.valueOf(item.string("preset")),
            (item["slots"] as JsonArray).map { value ->
                if (value ==
                    JsonNull
                ) {
                    null
                } else {
                    (value as JsonPrimitive).also { require(it.isString) }.content
                }
            },
            WidgetAppearance.valueOf(item.string("appearance")),
            item.number("revision")
        )
    }

    fun encodeProfiles(value: List<WidgetProfile>): String {
        require(
            value.size <= ProfileCatalog.MAX_PROFILES &&
                value.map { it.key }.distinct().size == value.size
        )
        return buildJsonObject {
            put("version", 1)
            put(
                "profiles",
                JsonArray(
                    value.map { profile ->
                        buildJsonObject {
                            put("key", profile.key)
                            put("name", profile.name)
                            put("source", profile.source)
                            put("status", profile.status.name)
                            put("portrait", profile.portraitHash?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                )
            )
        }.toString().also(::bound)
    }

    fun decodeProfiles(text: String): List<WidgetProfile> = parse(text) { item ->
        require(item.keys == setOf("version", "profiles"))
        (item["profiles"] as JsonArray).also {
            require(it.size <= ProfileCatalog.MAX_PROFILES)
        }.map { value ->
            val profile = value as JsonObject
            require(profile.keys == setOf("key", "name", "source", "status", "portrait"))
            WidgetProfile(
                profile.string("key"),
                profile.string("name"),
                profile.string("source"),
                CatalogStatus.valueOf(profile.string("status")),
                if (profile["portrait"] ==
                    JsonNull
                ) {
                    null
                } else {
                    profile.string("portrait")
                }
            )
        }.also { encodeProfiles(it) }
    }

    private fun bound(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= ProfileCatalog.MAX_BYTES)
    }
    private fun JsonObject.string(key: String): String =
        (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.number(key: String): Long =
        (getValue(key) as JsonPrimitive).also { require(!it.isString) }.content.toLong()
    private fun <T> parse(text: String, decode: (JsonObject) -> T): T = try {
        bound(text)
        val item = Json.parseToJsonElement(text) as JsonObject
        require(item.number("version") == 1L)
        decode(item)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid local widget data.", error)
    }
}
