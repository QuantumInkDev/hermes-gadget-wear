package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EndpointProfile(
    val endpoint: Endpoint,
    val label: String,
    val accessToken: String,
    val transport: TransportPreference = TransportPreference.AUTO
) {
    init {
        require(label.isNotBlank() && label.length <= 32) { "Name must be 1–32 characters." }
        require(accessToken.length <= 2048 && accessToken.all { it.code in 33..126 }) {
            "Access token must be at most 2048 printable characters without spaces."
        }
        require(transport != TransportPreference.RELAY || endpoint.isTls) {
            "Phone relay requires wss."
        }
    }
    override fun toString(): String = "EndpointProfile(content=redacted)"
    fun message(): Message = Message.create(
        "endpoint",
        buildJsonObject {
            put("url", endpoint.url)
            put("private", endpoint.privateNetwork)
            put("cleartext", endpoint.cleartextAccepted)
            put("label", label)
            put("token", accessToken)
            put("transport", transport.name)
        }
    )

    companion object {
        fun parse(message: Message): EndpointProfile {
            require(message.type == "endpoint")
            return EndpointProfile(
                Endpoint.parse(
                    requireNotNull(message.string("url")),
                    requireNotNull(message.boolean("private")),
                    requireNotNull(message.boolean("cleartext"))
                ),
                requireNotNull(message.string("label")),
                message.string("token").orEmpty(),
                TransportPreference.valueOf(message.string("transport") ?: "AUTO")
            )
        }
    }
}

object EndpointProfiles {
    const val MAX_PROFILES = 8
    const val MAX_BYTES = 65536
    const val SETUP_PATH = "/hermes/setup/v1"
    fun encode(profiles: List<EndpointProfile>): String {
        validate(profiles)
        val message = Message.create(
            "endpoints",
            buildJsonObject {
                put(
                    "profiles",
                    JsonArray(
                        profiles.map {
                            requireNotNull(Message.parse(it.message().encode())).fieldObject()
                        }
                    )
                )
            }
        ).encode()
        require(message.toByteArray().size <= MAX_BYTES)
        return message
    }
    fun decode(text: String): List<EndpointProfile> {
        require(text.toByteArray().size <= MAX_BYTES)
        val message = requireNotNull(Message.parse(text))
        require(message.type == "endpoints")
        val items = message.field("profiles") as? JsonArray ?: error("Missing profiles")
        require(items.size <= MAX_PROFILES)
        return items.map { EndpointProfile.parse(requireNotNull(Message.parse(it.toString()))) }
            .also(::validate)
    }
    private fun validate(profiles: List<EndpointProfile>) {
        require(profiles.size <= MAX_PROFILES) { "At most eight profiles are supported." }
        require(profiles.map { it.endpoint.url }.toSet().size == profiles.size) {
            "Each URL can appear only once."
        }
    }
    private fun Message.fieldObject(): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(encode()) as JsonObject
}
