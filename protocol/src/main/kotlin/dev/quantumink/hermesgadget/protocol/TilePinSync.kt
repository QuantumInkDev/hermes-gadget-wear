package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Wire and durable phone state are independent of the Gadget conversation protocol. */
data class TilePinSnapshot(val pins: TilePins, val keys: Set<String>)
data class TilePinRequest(val target: String, val requestId: String, val edit: TilePinEdit? = null)
data class TilePinResponse(
    val target: String,
    val requestId: String,
    val result: String,
    val acknowledged: TilePins,
    val current: TilePinSnapshot
)
data class TilePinPhoneState(
    val peer: String,
    val snapshot: TilePinSnapshot? = null,
    val pending: TilePinEdit? = null,
    val issue: String? = null
) {
    fun queue(
        id: String,
        focus: String?,
        grid: List<String?>,
        baseRevision: Long? = null
    ): TilePinPhoneState {
        val current = requireNotNull(snapshot) { "Read the watch first" }
        TilePins.validate(focus, grid)
        require((listOfNotNull(focus) + grid.filterNotNull()).all { it in current.keys })
        val base = baseRevision ?: current.pins.revision
        require(base in 0..current.pins.revision)
        val edit = TilePinEdit(base, id, focus, grid.toList())
        TilePinWire.request(TilePinRequest(peer, id, edit))
        return copy(pending = edit, issue = null)
    }
    fun receive(source: String, response: TilePinResponse): TilePinPhoneState {
        if (source != peer) return this
        val current = if (snapshot == null ||
            response.current.pins.revision >= snapshot.pins.revision
        ) {
            response.current
        } else {
            snapshot
        }
        if (pending?.requestId != response.requestId) return copy(snapshot = current)
        return when (response.result) {
            "accepted", "current" -> copy(snapshot = current, pending = null, issue = null)
            "conflict", "rejected" -> copy(snapshot = current, issue = response.result)
            else -> copy(snapshot = current)
        }
    }
}

/** Strict, small JSON envelopes carry only stable keys, revision, routing and request identity. */
object TilePinWire {
    const val REQUEST_PATH = "/hermes/tile-pins/v1/request"
    const val RESPONSE_PATH = "/hermes/tile-pins/v1/response"
    const val MAX_BYTES = 16384
    private fun id(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun key(value: JsonElement): String? = if (value == JsonNull) {
        null
    } else {
        require(value.jsonPrimitive.isString)
        value.jsonPrimitive.content.also { require(TilePins.validKey(it)) }
    }
    private fun pins(value: TilePins): JsonObject = JsonObject(
        mapOf(
            "revision" to JsonPrimitive(value.revision),
            "focus" to nullable(value.focus),
            "grid" to JsonArray(value.grid.map(::nullable))
        )
    )
    private fun pins(value: JsonObject): TilePins {
        require(value.keys == setOf("revision", "focus", "grid"))
        return TilePins(
            number(value.getValue("revision")),
            key(value.getValue("focus")),
            value.getValue("grid").jsonArray.map(::key)
        )
    }
    private fun encode(value: JsonObject): ByteArray = value.toString().toByteArray().also {
        require(it.size in 1..MAX_BYTES)
    }
    private fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(number(root.getValue("version")) == 1L)
        return root
    }
    private fun number(value: JsonElement): Long {
        require(!value.jsonPrimitive.isString)
        return value.jsonPrimitive.long
    }
    private fun string(root: JsonObject, field: String): String {
        val value = root.getValue(field).jsonPrimitive
        require(value.isString)
        return value.content
    }
    fun request(value: TilePinRequest): ByteArray {
        val fields = mutableMapOf<String, JsonElement>(
            "version" to JsonPrimitive(1),
            "target" to JsonPrimitive(id(value.target)),
            "id" to JsonPrimitive(id(value.requestId))
        )
        fields["edit"] = value.edit?.let {
            require(it.requestId == value.requestId)
            pins(TilePins(it.baseRevision, it.focus, it.grid))
        } ?: JsonNull
        return encode(JsonObject(fields))
    }
    fun request(bytes: ByteArray): TilePinRequest {
        val root = decode(bytes)
        require(root.keys == setOf("version", "target", "id", "edit"))
        val requestId = id(string(root, "id"))
        val edit = root.getValue("edit").takeIf {
            it != JsonNull
        }?.let { pins(it.jsonObject) }?.let {
            TilePinEdit(it.revision, requestId, it.focus, it.grid)
        }
        return TilePinRequest(id(string(root, "target")), requestId, edit)
    }
    fun response(value: TilePinResponse): ByteArray {
        require(value.result in setOf("snapshot", "accepted", "current", "conflict", "rejected"))
        require(value.current.keys.size <= 128 && value.current.keys.all(TilePins::validKey))
        require(value.acknowledged.revision <= value.current.pins.revision)
        return encode(
            JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "target" to JsonPrimitive(id(value.target)),
                    "id" to JsonPrimitive(id(value.requestId)),
                    "result" to JsonPrimitive(value.result),
                    "acknowledged" to pins(value.acknowledged),
                    "pins" to pins(value.current.pins),
                    "keys" to JsonArray(value.current.keys.sorted().map(::JsonPrimitive))
                )
            )
        )
    }
    fun response(bytes: ByteArray): TilePinResponse {
        val root = decode(bytes)
        require(
            root.keys == setOf("version", "target", "id", "result", "acknowledged", "pins", "keys")
        )
        val keys = root.getValue("keys").jsonArray.map { requireNotNull(key(it)) }
        require(keys.size == keys.distinct().size)
        return TilePinResponse(
            id(string(root, "target")),
            id(string(root, "id")),
            string(root, "result"),
            pins(root.getValue("acknowledged").jsonObject),
            TilePinSnapshot(pins(root.getValue("pins").jsonObject), keys.toSet())
        ).also { response(it) }
    }
    fun cache(value: TilePinPhoneState): ByteArray = encode(
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "peer" to JsonPrimitive(id(value.peer)),
                "snapshot" to
                    (
                        value.snapshot?.let {
                            Json.parseToJsonElement(
                                response(
                                    TilePinResponse("cache", "cache", "snapshot", it.pins, it)
                                ).toString(Charsets.UTF_8)
                            )
                        }
                            ?: JsonNull
                        ),
                "pending" to
                    (
                        value.pending?.let {
                            Json.parseToJsonElement(
                                request(
                                    TilePinRequest(value.peer, it.requestId, it)
                                ).toString(Charsets.UTF_8)
                            )
                        }
                            ?: JsonNull
                        ),
                "issue" to nullable(value.issue)
            )
        )
    )
    fun cache(bytes: ByteArray): TilePinPhoneState {
        val root = decode(bytes)
        require(root.keys == setOf("version", "peer", "snapshot", "pending", "issue"))
        val issue = root.getValue("issue").takeIf { it != JsonNull }?.jsonPrimitive?.content
        require(issue == null || issue in setOf("conflict", "rejected"))
        return TilePinPhoneState(
            id(string(root, "peer")),
            root.getValue("snapshot").takeIf {
                it !=
                    JsonNull
            }?.let { response(it.toString().toByteArray()).current },
            root.getValue("pending").takeIf {
                it != JsonNull
            }?.let { request(it.toString().toByteArray()).edit },
            issue
        )
    }
}
