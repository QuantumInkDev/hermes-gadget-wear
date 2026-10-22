package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class RelayWelcome {
    /** The welcome carries no `relay` member: this connection has no Relay v1 (stock behaviour continues). */
    NOT_OFFERED,

    /** `relay: {version: 1}` exactly: a paired, enrolled device with a current grant. Call `relay.info` next. */
    NEGOTIATED,

    /** A `relay` member of any other shape. Never treated as negotiated and never downgraded silently. */
    INVALID
}

/**
 * Hello/welcome negotiation helpers. Nothing here is wired into [dev.quantumink.hermesgadget.protocol.Protocol] or the
 * stock handshake: those defaults are unchanged, and a future consumer opts in by passing [withRelay] capabilities.
 */
object RelayNegotiation {
    const val CAPABILITY = "relay"
    const val VERSION = 1L

    /** `{"version": 1}`: exactly what the hello advertises and the welcome answers. */
    fun capability(): JsonObject = wireObject("version" to JsonPrimitive(VERSION))

    /** A copy of [capabilities] that also advertises Relay v1; a caller-set `relay` member is refused. */
    fun withRelay(capabilities: JsonObject): JsonObject {
        require(CAPABILITY !in capabilities) { "Relay capability already present." }
        return JsonObject(LinkedHashMap(capabilities).apply { put(CAPABILITY, capability()) })
    }

    fun welcome(message: Message): RelayWelcome {
        if (message.type != "welcome") return RelayWelcome.INVALID
        val member = message.field(CAPABILITY) ?: return RelayWelcome.NOT_OFFERED
        val fields = member as? JsonObject ?: return RelayWelcome.INVALID
        val version = fields["version"] as? JsonPrimitive
        val exact =
            fields.size == 1 && version != null && !version.isString && version.content == "1"
        return if (exact) RelayWelcome.NEGOTIATED else RelayWelcome.INVALID
    }

    /** Why a method may not be called now. */
    enum class Gate { ALLOWED, NOT_ADVERTISED }

    /** Use ONLY the methods `relay.info` lists. Pure; it does not send anything. */
    fun gate(info: Info, method: RelayMethod): Gate =
        if (info.advertises(method)) Gate.ALLOWED else Gate.NOT_ADVERTISED

    fun gate(info: Info, call: RelayCall<*>): Gate = gate(info, call.method)
}

/**
 * The endpoint and profile a result was obtained under, carried explicitly so a consumer never mixes data across
 * enrollments. [endpointKey] is whatever opaque key the caller uses for one enrolled endpoint (its own enrollment/key
 * is per endpoint/profile/device); [profileId] is the server-issued `relay.info.profile_id`. The two are independent:
 * a reused endpoint with a different profile, or a profile seen through a different endpoint, are different scopes.
 * This is a label for the caller's own bookkeeping and is NEVER proof of authority: the server decides that per call.
 */
data class RelayScope(val endpointKey: String, val profileId: ProfileId) {
    init {
        require(
            endpointKey.length in 1..MAX_KEY && endpointKey.none {
                it.isISOControl()
            }
        ) { "Invalid endpoint key." }
    }

    override fun toString(): String = "RelayScope(redacted)"

    /** True only when [info] came from this scope's profile; a mismatch demands review before anything is sent. */
    fun matches(info: Info): Boolean = info.profileId == profileId

    companion object {
        const val MAX_KEY = 128

        fun of(endpointKey: String, info: Info): RelayScope =
            RelayScope(endpointKey, info.profileId)
    }
}

/**
 * What a consumer may persist: the conversation identity under its scope — never a [Binding], whose epoch, replay
 * cursor and id die with the socket.
 */
data class ConversationKey(val scope: RelayScope, val conversationId: ConversationId) {
    override fun toString(): String = "ConversationKey(id=${conversationId.value}, scope=redacted)"
}

/** A decoded value tagged with the scope it was obtained under. Equality includes the scope. */
data class Scoped<out T : Any>(val scope: RelayScope, val value: T) {
    override fun toString(): String = "Scoped(redacted)"
}
