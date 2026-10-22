package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.BindingRef
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ExistingBinding
import dev.quantumink.hermesgadget.protocol.relay.RelayScope

/**
 * Which enrolled endpoint/profile a piece of state belongs to. It is a LOCAL label supplied by the future
 * authenticated consumer: it is not a wire claim and never proof of authority (the server decides that per call).
 *
 * - [relay]: the consumer's opaque endpoint key plus the server-issued `relay.info.profile_id`.
 * - [enrollmentKey]: an opaque label of THIS device's own enrollment/key on that endpoint. Two devices, or two key
 *   generations, using equal textual ids are different scopes.
 * - [reviewEpoch]: a local counter the consumer advances each time the user reviews a changed server/profile
 *   identity. State saved under an older epoch is a different scope and is never sent under the new one.
 */
data class EnrollmentScope(
    val relay: RelayScope,
    val enrollmentKey: String,
    val reviewEpoch: Long = 0
) {
    init {
        require(
            enrollmentKey.length in 1..RelayScope.MAX_KEY &&
                enrollmentKey.none { it.isISOControl() }
        ) { "Invalid enrollment key." }
        require(reviewEpoch >= 0) { "Invalid review epoch." }
    }

    override fun toString(): String = "EnrollmentScope(redacted)"
}

/** The durable identity of a conversation under its enrollment: persist this, never a transport binding. */
data class ConversationScope(val enrollment: EnrollmentScope, val conversationId: ConversationId) {
    override fun toString(): String =
        "ConversationScope(id=${conversationId.value}, scope=redacted)"
}

enum class BindingKind { RELAY, EXISTING }

/**
 * The transient authority of ONE attach: the exact binding, epoch, replay epoch and generation it returned, tagged
 * with a local serial so a callback from an older attach is recognisably old. It is deliberately not serializable,
 * has no persistent form and is never part of a snapshot. Only a [RelayAuthority] makes one.
 */
class BindingToken internal constructor(
    val scope: EnrollmentScope,
    val kind: BindingKind,
    val conversationId: ConversationId,
    val bindingId: BindingId,
    val authorityEpoch: Long,
    /** Null only for an existing-session binding that reported none. */
    val executionGeneration: Long?,
    val replayEpoch: String,
    /** The `last_sequence` the attach reported: events read start after it. */
    val baselineSequence: Long,
    val serial: Long
) {
    val ref: BindingRef get() = BindingRef(bindingId, authorityEpoch)

    val conversation: ConversationScope get() = ConversationScope(scope, conversationId)

    override fun toString(): String = "BindingToken(serial=$serial, kind=$kind, redacted)"
}

class AuthorityStep internal constructor(
    val authority: RelayAuthority,
    val token: BindingToken,
    /** The binding this attach retired on the same transport scope (one binding per transport), if any. */
    val retired: BindingToken?
)

/**
 * The set of CURRENT transient bindings, at most one per enrollment scope (the server keeps one binding per
 * transport: an attach retires the previous one). Immutable; every change returns a new instance. It is process
 * state only: a fresh process starts with [fresh], which is how a restore gets fresh transient authority.
 */
class RelayAuthority private constructor(
    private val bindings: Map<EnrollmentScope, BindingToken>,
    /** The highest serial ever issued by this lineage of authorities (not persisted). */
    val lastSerial: Long
) {
    fun attach(scope: EnrollmentScope, binding: Binding): AuthorityStep = attach(
        scope,
        BindingKind.RELAY,
        binding.conversationId,
        binding.bindingId,
        binding.authorityEpoch,
        binding.executionGeneration,
        binding.replayEpoch,
        binding.lastSequence
    )

    fun attachExisting(scope: EnrollmentScope, binding: ExistingBinding): AuthorityStep = attach(
        scope,
        BindingKind.EXISTING,
        binding.conversationId,
        binding.bindingId,
        binding.authorityEpoch,
        binding.executionGeneration,
        binding.replayEpoch,
        binding.lastSequence
    )

    private fun attach(
        scope: EnrollmentScope,
        kind: BindingKind,
        conversationId: ConversationId,
        bindingId: BindingId,
        authorityEpoch: Long,
        executionGeneration: Long?,
        replayEpoch: String,
        baseline: Long
    ): AuthorityStep {
        val serial = Math.addExact(lastSerial, 1L)
        val token = BindingToken(
            scope,
            kind,
            conversationId,
            bindingId,
            authorityEpoch,
            executionGeneration,
            replayEpoch,
            baseline,
            serial
        )
        val next = LinkedHashMap(bindings)
        val retired = next.put(scope, token)
        return AuthorityStep(RelayAuthority(next, serial), token, retired)
    }

    /** Whether [token] is still THE binding of its scope. A retired or replaced token is not. */
    fun isCurrent(token: BindingToken): Boolean = bindings[token.scope]?.serial == token.serial

    fun current(scope: EnrollmentScope): BindingToken? = bindings[scope]

    /** Retires [token] if (and only if) it is still current; a stale token changes nothing. */
    fun retire(token: BindingToken): RelayAuthority =
        if (isCurrent(token)) retireScope(token.scope) else this

    fun retireScope(scope: EnrollmentScope): RelayAuthority {
        if (scope !in bindings) return this
        val next = LinkedHashMap(bindings)
        next.remove(scope)
        return RelayAuthority(next, lastSerial)
    }

    val isEmpty: Boolean get() = bindings.isEmpty()

    override fun toString(): String =
        "RelayAuthority(bindings=${bindings.size}, lastSerial=$lastSerial)"

    companion object {
        /** Fresh transient authority: no binding at all. */
        fun fresh(): RelayAuthority = RelayAuthority(emptyMap(), 0)
    }
}
