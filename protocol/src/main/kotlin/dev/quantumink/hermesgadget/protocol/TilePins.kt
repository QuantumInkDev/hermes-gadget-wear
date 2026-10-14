package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Stable opaque keys only. No endpoint, identity or wall clock participates in ordering. */
data class TilePins(
    val revision: Long = 0,
    val focus: String? = null,
    val grid: List<String?> = List(4) { null }
) {
    init {
        require(revision >= 0)
        validate(focus, grid)
    }
    companion object {
        fun validKey(key: String): Boolean =
            key.matches(Regex("[A-Za-z0-9_-]{1,64}(:[A-Za-z0-9_-]{1,64})?"))
        fun validate(focus: String?, grid: List<String?>) {
            require(grid.size <= 4)
            require((listOfNotNull(focus) + grid.filterNotNull()).all(::validKey))
            require(grid.filterNotNull().distinct().size == grid.filterNotNull().size)
        }
    }
}
data class TilePinEdit(
    val baseRevision: Long,
    val requestId: String,
    val focus: String?,
    val grid: List<String?>
)
sealed interface TilePinResult {
    val pins: TilePins
    data class Accepted(override val pins: TilePins) : TilePinResult

    /** Exact acknowledgement of an already accepted request, even after subsequent local edits. */
    data class Current(override val pins: TilePins) : TilePinResult
    data class Conflict(override val pins: TilePins) : TilePinResult
    data class Rejected(override val pins: TilePins, val reason: String) : TilePinResult
}
data class TilePinReceipt(val edit: TilePinEdit, val pins: TilePins)

/** Immutable transition result lets the native store commit pins and receipts together. */
data class TilePinState(
    val pins: TilePins = TilePins(),
    val receipts: List<TilePinReceipt> = emptyList()
) {
    init {
        require(receipts.size <= 64)
    }
    fun editLocal(focus: String?, grid: List<String?>): TilePinState {
        TilePins.validate(focus, grid)
        require(pins.revision < Long.MAX_VALUE)
        return copy(
            pins = TilePins(
                pins.revision + 1,
                focus,
                grid.toList() + List(4 - grid.size) { null }
            )
        )
    }
    fun apply(edit: TilePinEdit, knownKeys: Set<String>): Pair<TilePinState, TilePinResult> {
        val invalid = runCatching {
            require(edit.baseRevision >= 0)
            require(edit.requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            TilePins.validate(edit.focus, edit.grid)
        }.isFailure
        if (invalid) return this to TilePinResult.Rejected(pins, "Invalid tile configuration")
        receipts.singleOrNull { it.edit.requestId == edit.requestId }?.let {
            return this to
                if (it.edit ==
                    edit
                ) {
                    TilePinResult.Current(it.pins)
                } else {
                    TilePinResult.Rejected(pins, "Request ID already used")
                }
        }
        if (edit.baseRevision != pins.revision) return this to TilePinResult.Conflict(pins)
        if ((listOfNotNull(edit.focus) + edit.grid.filterNotNull()).any { it !in knownKeys }) {
            return this to TilePinResult.Rejected(pins, "Profile is unavailable")
        }
        if (pins.revision ==
            Long.MAX_VALUE
        ) {
            return this to TilePinResult.Rejected(pins, "Revision limit reached")
        }
        val next = editLocal(edit.focus, edit.grid)
        val receipt = TilePinReceipt(edit.copy(grid = edit.grid.toList()), next.pins)
        return next.copy(receipts = (receipts + receipt).takeLast(64)) to
            TilePinResult.Accepted(next.pins)
    }
    fun encode(): String = JsonObject(
        mapOf(
            "version" to JsonPrimitive(1),
            "pins" to encodePins(pins),
            "receipts" to JsonArray(
                receipts.map {
                    JsonObject(
                        mapOf(
                            "base" to JsonPrimitive(it.edit.baseRevision),
                            "id" to JsonPrimitive(it.edit.requestId),
                            "focus" to nullable(it.edit.focus),
                            "grid" to JsonArray(it.edit.grid.map(::nullable)),
                            "pins" to encodePins(it.pins)
                        )
                    )
                }
            )
        )
    ).toString().also { require(it.toByteArray().size <= MAX_BYTES) }
    companion object {
        const val MAX_BYTES = 131072
        private fun nullable(value: String?): JsonPrimitive =
            value?.let(::JsonPrimitive) ?: JsonNull
        private fun encodePins(pins: TilePins) = JsonObject(
            mapOf(
                "revision" to JsonPrimitive(pins.revision),
                "focus" to nullable(pins.focus),
                "grid" to JsonArray(pins.grid.map(::nullable))
            )
        )
        private fun key(value: kotlinx.serialization.json.JsonElement?): String? =
            if (value == JsonNull) null else requireNotNull(value).jsonPrimitive.content
        private fun grid(value: kotlinx.serialization.json.JsonElement?) =
            requireNotNull(value).jsonArray.map(::key)
        private fun decodePins(value: JsonObject) = TilePins(
            value.getValue("revision").jsonPrimitive.long,
            key(value["focus"]),
            grid(value["grid"])
        )
        fun decode(text: String): TilePinState {
            require(text.toByteArray().size <= MAX_BYTES)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.long == 1L)
            val pins = decodePins(root.getValue("pins").jsonObject)
            val receipts = root.getValue("receipts").jsonArray.map { element ->
                val item = element.jsonObject
                val edit = TilePinEdit(
                    item.getValue("base").jsonPrimitive.long,
                    item.getValue("id").jsonPrimitive.content,
                    key(item["focus"]),
                    grid(item["grid"])
                )
                require(
                    edit.baseRevision >= 0 && edit.requestId.matches(Regex("[A-Za-z0-9_-]{1,64}"))
                )
                TilePins.validate(edit.focus, edit.grid)
                val acknowledged = decodePins(item.getValue("pins").jsonObject)
                require(
                    acknowledged.revision <= pins.revision && edit.baseRevision < Long.MAX_VALUE &&
                        acknowledged.revision == edit.baseRevision + 1
                )
                require(
                    acknowledged.focus == edit.focus &&
                        acknowledged.grid == edit.grid + List(4 - edit.grid.size) { null }
                )
                TilePinReceipt(edit, acknowledged)
            }
            require(receipts.map { it.edit.requestId }.distinct().size == receipts.size)
            return TilePinState(pins, receipts)
        }
    }
}
