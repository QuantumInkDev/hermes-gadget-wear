package dev.quantumink.hermesgadget.protocol.relay.wake

import dev.quantumink.hermesgadget.protocol.relay.DecodeFailure
import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.WireToken
import dev.quantumink.hermesgadget.protocol.relay.guarded
import dev.quantumink.hermesgadget.protocol.relay.isCanonicalUuid
import dev.quantumink.hermesgadget.protocol.relay.ownedBounded
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.wireObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A wake target the server's policy names: canonical lowercase UUID text, equality only. */
@JvmInline
value class WakeTargetId private constructor(val value: String) {
    companion object {
        fun of(value: String): WakeTargetId? = value.takeIf(::isCanonicalUuid)?.let(::WakeTargetId)
    }

    override fun toString(): String = "WakeTargetId($value)"
}

enum class WakeBackend(override val wire: String) : WireToken {
    WOL("wol"),
    VM_START_RESUME("vm_start_resume")
}

/** Why a listed target cannot be woken now (`relay_wake._targets`): `unsupported` is a sender that is not macOS. */
enum class WakeUnavailableReason(override val wire: String) : WireToken {
    UNAVAILABLE("unavailable"),
    UNSUPPORTED("unsupported")
}

/** `status` of `wake.request`. It describes the dispatch only, never that the target booted or its agent is ready. */
enum class WakeStatus(override val wire: String) : WireToken {
    ACCEPTED("accepted"),
    UNAVAILABLE("unavailable"),
    UNSUPPORTED("unsupported")
}

/** One `wake.targets` row: only the id, the operator's label, the backend kind and its fixed availability. */
class WakeTarget(
    val targetId: WakeTargetId,
    val label: String,
    val backend: WakeBackend,
    val available: Boolean,
    /** Present exactly when the target is not available. */
    val reason: WakeUnavailableReason?
) {
    init {
        require(WakeCalls.validLabel(label)) { "Invalid label." }
        require((reason == null) == available) { "Invalid availability." }
    }

    override fun equals(other: Any?): Boolean = other is WakeTarget &&
        other.targetId == targetId && other.label == label && other.backend == backend &&
        other.available == available && other.reason == reason

    override fun hashCode(): Int = targetId.hashCode()

    override fun toString(): String =
        "WakeTarget(${targetId.value}, ${backend.wire}, available=$available, redacted)"

    fun toWire(): JsonObject {
        val members = linkedMapOf(
            "target_id" to JsonPrimitive(targetId.value),
            "label" to JsonPrimitive(label),
            "backend" to JsonPrimitive(backend.wire),
            "available" to JsonPrimitive(available)
        )
        reason?.let { members["reason"] = JsonPrimitive(it.wire) }
        return JsonObject(members)
    }

    internal companion object {
        fun decode(reader: FieldReader): WakeTarget = guarded(reader, "target") {
            WakeTarget(
                reader.id("target_id", WakeTargetId::of),
                reader.string("label", WakeCalls.LABEL_BYTES),
                reader.token("backend", WakeBackend.entries.toTypedArray()),
                reader.boolean("available"),
                if (reader.has("reason")) {
                    reader.token("reason", WakeUnavailableReason.entries.toTypedArray())
                } else {
                    null
                }
            )
        }
    }
}

/** `wake.targets`: the targets shared with this device's group, at most 16, each id once. */
class WakeTargets(targets: List<WakeTarget>) {
    val targets: List<WakeTarget> = ownedBounded(targets, WakeCalls.MAX_TARGETS)

    init {
        require(this.targets.map { it.targetId }.toSet().size == this.targets.size)
    }

    override fun equals(other: Any?): Boolean = other is WakeTargets && other.targets == targets

    override fun hashCode(): Int = targets.hashCode()

    override fun toString(): String = "WakeTargets(${targets.size})"

    fun toWire(): JsonObject = wireObject(
        "targets" to kotlinx.serialization.json.JsonArray(targets.map { it.toWire() })
    )

    internal companion object {
        fun decode(result: JsonObject): WakeTargets {
            val reader = FieldReader.of("wake", result, setOf("targets"))
            val rows = reader.objects(
                "targets",
                WakeCalls.MAX_TARGETS,
                setOf("target_id", "label", "backend", "available"),
                setOf("reason"),
                WakeTarget::decode
            )
            return guarded(reader, "targets") { WakeTargets(rows) }
        }
    }
}

/** `wake.request`: the echoed request id and the dispatch status. */
class WakeResult(val requestId: RequestId, val status: WakeStatus) {
    override fun equals(other: Any?): Boolean =
        other is WakeResult && other.requestId == requestId && other.status == status

    override fun hashCode(): Int = 31 * requestId.hashCode() + status.hashCode()

    override fun toString(): String = "WakeResult(${requestId.value}, ${status.wire})"

    fun toWire(): JsonObject = wireObject(
        "request_id" to JsonPrimitive(requestId.value),
        "status" to JsonPrimitive(status.wire)
    )

    internal companion object {
        fun decode(result: JsonObject): WakeResult {
            val reader = FieldReader.of("wake", result, setOf("request_id", "status"))
            return WakeResult(
                reader.id("request_id", RequestId::of),
                reader.token("status", WakeStatus.entries.toTypedArray())
            )
        }
    }
}

/**
 * What one deliberate wake read or request ended with, in the words the phone shows. A pure classification of the
 * call's result: nothing here retries, stores or sends. Only [ACCEPTED] means the server dispatched a wake.
 */
enum class WakeAnswer {
    /** The server sent the wake (a magic packet, or a VM start/resume). The box may still be starting. */
    ACCEPTED,

    /** `status: unavailable`, or a target listed as unavailable: the sender could not dispatch it now. */
    UNAVAILABLE,

    /** `status: unsupported`: this sender cannot wake that kind of target. */
    UNSUPPORTED_TARGET,

    /** `relay.info` does not list the method: wake is off on the server or this device holds no wake permission. */
    NOT_OFFERED,

    /** `permission_denied`: this device's grant does not allow it (now). Never a conversation-scope stop. */
    NOT_PERMITTED,

    /** `expired`: the request's 30 s window passed before the server handled it; nothing was dispatched. */
    EXPIRED,

    /** `rate_limited`: one wake per target per minute (or the session's request bucket). */
    TOO_SOON,

    /** `not_found`: the target is no longer shared with this device. */
    NOT_FOUND,

    /** `busy` / `temporarily_unavailable`: try again by hand later. */
    BUSY,

    /** `unsupported` as an error: the wake feature is turned off on the server. */
    TURNED_OFF,

    /** `invalid_request`, `conflict` or an unknown code: refused, nothing was dispatched for this request. */
    REFUSED,

    /** No usable server clock for the 30 s expiry; nothing was sent. */
    NO_CLOCK,

    /** Refused locally before anything was written (not ready, backpressure, scope replaced). */
    NOT_SENT,

    /** Timed out, the connection closed or the answer was not understood: the wake may or may not have been sent. */
    NOT_CONFIRMED
}

/**
 * Typed requests for the two remote-wake methods with the exact parameter sets of the server (`relay_wake.WakeService`):
 * `wake.targets {}` and `wake.request {target_id, request_id, expires_at_ms}`. Nothing else is ever sent (no MAC, VM,
 * address, command or argument). A call is only valid if `relay.info` lists it, and nothing here sends, retries or
 * persists. The expiry is formed from a [ServerInstant] only (the server clock of `relay.info`), never the device clock.
 */
object WakeCalls {
    const val MAX_TARGETS = 16

    /** The server refuses an expiry more than 30 s ahead of its own clock (`invalid_request`). */
    const val MAX_TTL_MS = 30_000L

    /** Below the cap, so a local estimate that leads the server a little still lands inside the window. */
    const val DEFAULT_TTL_MS = 20_000L

    const val LABEL_CODE_POINTS = 80
    internal const val LABEL_BYTES = LABEL_CODE_POINTS * 4

    fun targets(): RelayCall<WakeTargets> =
        RelayCall(RelayMethod.WAKE_TARGETS, wireObject(), WakeTargets::decode)

    /**
     * One wake of [target] identified by the freshly minted [requestId], expiring [ttlMs] (1..30,000) after the server
     * time estimate [now]. A result naming another request id is not this answer and decodes as invalid.
     */
    fun request(
        target: WakeTargetId,
        requestId: RequestId,
        now: ServerInstant,
        ttlMs: Long = DEFAULT_TTL_MS
    ): RelayCall<WakeResult> {
        val expiresAtMs = requireNotNull(expiry(now.serverMs, ttlMs)) { "Invalid expiry." }
        return requestAt(target, requestId, expiresAtMs)
    }

    /** `now + ttlMs` when [ttlMs] is in 1..30,000 and [serverNowMs] is a server time in range; otherwise null. */
    fun expiry(serverNowMs: Long, ttlMs: Long): Long? {
        if (ttlMs < 1 || ttlMs > MAX_TTL_MS) return null
        if (serverNowMs < 0 ||
            serverNowMs > StateLimits.SERVER_TIME_MAX_MS - MAX_TTL_MS
        ) {
            return null
        }
        return serverNowMs + ttlMs
    }

    private fun requestAt(
        target: WakeTargetId,
        requestId: RequestId,
        expiresAtMs: Long
    ): RelayCall<WakeResult> {
        require(expiresAtMs >= 0) { "Invalid expiry." }
        return RelayCall(
            RelayMethod.WAKE_REQUEST,
            wireObject(
                "target_id" to JsonPrimitive(target.value),
                "request_id" to JsonPrimitive(requestId.value),
                "expires_at_ms" to JsonPrimitive(expiresAtMs)
            )
        ) { result ->
            WakeResult.decode(result).also {
                if (it.requestId != requestId) throw DecodeFailure("wake.request_id")
            }
        }
    }

    /** 1..80 Unicode scalars with no control, format, surrogate or private-use character (`WakePolicy.load`). */
    fun validLabel(label: String): Boolean {
        var count = 0
        var index = 0
        while (index < label.length) {
            val point = label.codePointAt(index)
            when (Character.getType(point).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE ->
                    return false
            }
            count++
            if (count > LABEL_CODE_POINTS) return false
            index += Character.charCount(point)
        }
        return count >= 1
    }

    /** The plain classification of a read or request result. A success of `wake.targets` is [ACCEPTED]. */
    fun answer(result: RelayCallResult<*>): WakeAnswer = when (result) {
        is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
            is RelayOutcome.Success -> when ((outcome.value as? WakeResult)?.status) {
                WakeStatus.UNAVAILABLE -> WakeAnswer.UNAVAILABLE
                WakeStatus.UNSUPPORTED -> WakeAnswer.UNSUPPORTED_TARGET
                WakeStatus.ACCEPTED, null -> WakeAnswer.ACCEPTED
            }
            is RelayOutcome.Failed -> when (outcome.error.code) {
                RelayErrorCode.PERMISSION_DENIED -> WakeAnswer.NOT_PERMITTED
                RelayErrorCode.EXPIRED -> WakeAnswer.EXPIRED
                RelayErrorCode.RATE_LIMITED -> WakeAnswer.TOO_SOON
                RelayErrorCode.NOT_FOUND -> WakeAnswer.NOT_FOUND
                RelayErrorCode.BUSY, RelayErrorCode.TEMPORARILY_UNAVAILABLE -> WakeAnswer.BUSY
                RelayErrorCode.UNSUPPORTED -> WakeAnswer.TURNED_OFF
                else -> WakeAnswer.REFUSED
            }
            is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> WakeAnswer.NOT_CONFIRMED
        }
        RelayCallResult.TimedOut, RelayCallResult.Closed -> WakeAnswer.NOT_CONFIRMED
        is RelayCallResult.Refused ->
            if (result.reason ==
                RelayRefusal.NOT_ADVERTISED
            ) {
                WakeAnswer.NOT_OFFERED
            } else {
                WakeAnswer.NOT_SENT
            }
    }

    internal fun resultDecoder(method: RelayMethod): (JsonObject) -> Any = when (method) {
        RelayMethod.WAKE_TARGETS -> WakeTargets::decode
        RelayMethod.WAKE_REQUEST -> WakeResult::decode
        else -> throw IllegalArgumentException("not a wake method")
    }

    /** The inverse of the builders (test support): the typed call a wire `params` object encodes, or null if refused. */
    internal fun fromWire(method: RelayMethod, params: JsonObject): RelayCall<*>? = try {
        when (method) {
            RelayMethod.WAKE_TARGETS -> {
                FieldReader.of("params", params, emptySet())
                targets()
            }
            RelayMethod.WAKE_REQUEST ->
                FieldReader.of(
                    "params",
                    params,
                    setOf("target_id", "request_id", "expires_at_ms")
                ).let {
                    requestAt(
                        it.id("target_id", WakeTargetId::of),
                        it.id("request_id", RequestId::of),
                        it.long("expires_at_ms")
                    )
                }
            else -> null
        }
    } catch (_: DecodeFailure) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
