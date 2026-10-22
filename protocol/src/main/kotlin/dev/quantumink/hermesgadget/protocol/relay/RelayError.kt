package dev.quantumink.hermesgadget.protocol.relay

/** The stable error codes of the accepted SDK (`relay_conversations._ERRORS`); the first nine are the O5 set. */
enum class RelayErrorCode(val wire: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED("unsupported"),
    PERMISSION_DENIED("permission_denied"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    EXPIRED("expired"),
    BUSY("busy"),
    RATE_LIMITED("rate_limited"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    STALE_BINDING("stale_binding"),
    STALE_EPOCH("stale_epoch"),
    PROFILE_CHANGED("profile_changed"),
    SNAPSHOT_REQUIRED("snapshot_required"),
    PROJECTION_TOO_LARGE("projection_too_large"),

    /** A well-formed token this client does not know. It is still an error and never a success. */
    UNKNOWN("unknown");

    val isOutboxCode: Boolean get() = ordinal < 9

    companion object {
        private val byWire: Map<String, RelayErrorCode> = entries.filter {
            it != UNKNOWN
        }.associateBy { it.wire }

        fun fromWire(token: String): RelayErrorCode = byWire[token] ?: UNKNOWN
    }
}

/**
 * What a consumer must do next, per the contract's client rules. A pure classification: nothing is dispatched,
 * retried or stored here.
 */
enum class RelayErrorAction {
    /** `temporarily_unavailable`/`busy`/`rate_limited`: back off and retry the SAME request (same durable id). */
    RETRY_SAME_REQUEST,

    /** `stale_binding`/`stale_epoch`/`snapshot_required`: attach again and refresh; never auto-submit. */
    REATTACH_AND_REFRESH,

    /** `permission_denied`: stop and evict cached content for that scope. */
    STOP_AND_EVICT_SCOPE,

    /** `profile_changed`: invalidate the profile binding and require user review. */
    INVALIDATE_PROFILE_AND_REVIEW,

    /** Any other code: a failure to surface. It is never success and never silently retried. */
    FAILED
}

class RelayError internal constructor(
    val code: RelayErrorCode,
    /** `error.snapshot_required: true` alongside another code also demands a fresh view. */
    val snapshotRequired: Boolean
) {
    val needsFreshView: Boolean get() = snapshotRequired || code == RelayErrorCode.SNAPSHOT_REQUIRED

    /** Highest-severity rule wins: profile change, then permission, then fresh view, then retry. */
    val action: RelayErrorAction
        get() = when {
            code == RelayErrorCode.PROFILE_CHANGED -> RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
            code == RelayErrorCode.PERMISSION_DENIED -> RelayErrorAction.STOP_AND_EVICT_SCOPE
            needsFreshView || code == RelayErrorCode.STALE_BINDING ||
                code == RelayErrorCode.STALE_EPOCH ->
                RelayErrorAction.REATTACH_AND_REFRESH
            code == RelayErrorCode.TEMPORARILY_UNAVAILABLE || code == RelayErrorCode.BUSY ||
                code == RelayErrorCode.RATE_LIMITED -> RelayErrorAction.RETRY_SAME_REQUEST
            else -> RelayErrorAction.FAILED
        }

    override fun equals(other: Any?): Boolean =
        other is RelayError && other.code == code && other.snapshotRequired == snapshotRequired

    override fun hashCode(): Int = 31 * code.hashCode() + snapshotRequired.hashCode()

    override fun toString(): String = "RelayError(${code.wire}, snapshotRequired=$snapshotRequired)"

    companion object {
        /** Strict wire form: `{code}` or `{code, snapshot_required: true}`; anything else is not an error object. */
        internal fun decode(reader: FieldReader): RelayError {
            val flag = reader.has("snapshot_required")
            if (flag) reader.mustBeTrue("snapshot_required")
            val token = reader.string("code", RelayLimits.ID_CHARS)
            if (!token.all { it in 'a'..'z' || it == '_' }) reader.fail("code")
            return RelayError(RelayErrorCode.fromWire(token), flag)
        }
    }
}
