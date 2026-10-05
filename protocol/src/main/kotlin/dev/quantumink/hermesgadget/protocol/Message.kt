package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Open envelopes preserve optional fields without requiring every extension to be known. */
class Message private constructor(val type: String, private val fields: JsonObject) {
    fun string(name: String): String? =
        (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(name: String): Int? = (fields[name] as? JsonPrimitive)?.takeUnless {
        it.isString
    }?.intOrNull

    fun boolean(name: String): Boolean? = (fields[name] as? JsonPrimitive)?.takeUnless {
        it.isString
    }?.booleanOrNull

    fun field(name: String): JsonElement? = fields[name]

    fun encode(): String = fields.toString()

    override fun toString(): String = "Message(type=$type, fields=redacted)"

    companion object {
        fun create(type: String, fields: JsonObject = JsonObject(emptyMap())): Message {
            require(type.isNotBlank())
            return Message(type, JsonObject(fields + ("type" to JsonPrimitive(type))))
        }

        fun parse(text: String): Message? {
            if (text.length > BinaryFrame.MAX_FRAME_BYTES ||
                text.toByteArray(Charsets.UTF_8).size > BinaryFrame.MAX_FRAME_BYTES ||
                !safeNesting(text)
            ) {
                return null
            }
            return try {
                val fields = Json.parseToJsonElement(text) as? JsonObject ?: return null
                val type =
                    (fields["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return null
                if (type.isBlank()) null else Message(type, fields)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun safeNesting(text: String): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else if (character == '\\') {
                        escaped = true
                    } else if (character == '"') {
                        quoted = false
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '{', '[' -> if (++depth > 64) return false
                        '}', ']' -> if (--depth < 0) return false
                    }
                }
            }
            return depth == 0 && !quoted
        }
    }
}

object Protocol {
    const val VERSION = 1
    const val SUBPROTOCOL = "hermes-gadget.v1"

    fun hello(
        identity: DeviceIdentity,
        name: String,
        board: String,
        firmware: String,
        capabilities: JsonObject = JsonObject(emptyMap()),
        accessToken: String? = null
    ): Message = Message.create(
        "hello",
        buildJsonObject {
            put("proto", VERSION)
            put("device_id", identity.deviceId)
            put("name", name)
            put("board", board)
            put("firmware", firmware)
            put("caps", capabilities)
            accessToken?.takeIf(String::isNotEmpty)?.let { put("token", it) }
        }
    )

    fun pcmCapabilities(rate: Int = 16000): JsonObject {
        require(rate in 4000..48000)
        val audio = buildJsonObject {
            put("rate", rate)
            put("format", "pcm16")
        }
        return buildJsonObject {
            put("mic", audio)
            put("speaker", audio)
            put("display", buildJsonObject { put("charset", "utf8") })
            put("talk_mode", "hold")
        }
    }

    /** A stock server has no negotiation field. Never select Opus merely because we offered it. */
    fun selectedAudioFormat(serverChoice: String?, offered: Set<String> = setOf("pcm16")): String {
        val selected = serverChoice ?: "pcm16"
        require(selected in offered && selected in setOf("pcm16", "opus"))
        return selected
    }
}
