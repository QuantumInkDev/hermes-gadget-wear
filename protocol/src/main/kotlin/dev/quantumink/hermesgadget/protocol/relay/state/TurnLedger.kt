package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import java.util.Collections

/** A deliberate new user turn. Only this kind of input can make [TurnLedger.prepare] mint a request id. */
class TurnDraft(
    val conversation: ConversationScope,
    val text: String,
    /** Lifetime in 1..300000 ms from the server-time estimate; see [StateLimits.DEFAULT_TURN_TTL_MS]. */
    val ttlMs: Long = StateLimits.DEFAULT_TURN_TTL_MS
) {
    override fun toString(): String = "TurnDraft(redacted)"
}

enum class PrepareRefusal {
    CLOCK_ROLLBACK,
    SCOPE_STOPPED,
    SCOPE_NEEDS_REVIEW,
    INVALID_TEXT,
    INVALID_DEADLINE,
    MINT_FAILED,
    ID_COLLISION
}

/** A local resource limit of the ledger (policy). Reaching one refuses the new turn; nothing is dropped. */
enum class LedgerLimit { TURNS, TEXT_BYTES }

sealed interface PrepareOutcome {
    class Prepared(val key: TurnKey) : PrepareOutcome {
        override fun toString(): String = "Prepared($key)"
    }

    class Refused(val reason: PrepareRefusal, val mint: MintRefusal? = null) : PrepareOutcome {
        override fun toString(): String = "Refused($reason${mint?.let { ", $it" } ?: ""})"
    }

    /** The ledger is full: typed backpressure. Nothing was truncated, deleted or converted to success. */
    class Backpressure(val limit: LedgerLimit) : PrepareOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }
}

enum class PlanRefusal {
    UNKNOWN_TURN,
    CLOCK_ROLLBACK,
    SCOPE_STOPPED,
    SCOPE_NEEDS_REVIEW,
    BINDING_KIND,
    BINDING_WRONG_SCOPE,
    BINDING_NOT_CURRENT,
    CONTENT_EVICTED,
    CALL_OUTSTANDING,
    ALREADY_CLOSED,
    CORRELATION_REUSED,

    /** The turn may have reached the server: look up its receipt before any same-id resubmit. */
    LOOKUP_REQUIRED_FIRST,

    /** The binding is not newer than the uncertainty: attach again first. */
    FRESH_ATTACH_REQUIRED,

    /** A receipt proved admission: a resubmit is never planned. */
    ALREADY_ADMITTED,
    HELD,
    DEADLINE_PASSED,
    NOTHING_TO_LOOK_UP,
    INVALID_TURN
}

sealed interface PlanOutcome {
    class Planned(val dispatch: TurnDispatch) : PlanOutcome {
        override fun toString(): String = "Planned($dispatch)"
    }

    class Refused(val reason: PlanRefusal) : PlanOutcome {
        override fun toString(): String = "Refused($reason)"
    }
}

enum class IgnoreReason {
    /** No turn has this call outstanding: a late, replayed or foreign reply changes nothing. */
    UNKNOWN_CALL
}

sealed interface ReplyOutcome {
    class Applied(val key: TurnKey, val phase: TurnPhase) : ReplyOutcome {
        override fun toString(): String = "Applied($key, $phase)"
    }

    /** The reply contradicted what is already known: the known state is kept and the anomaly surfaced. */
    class Anomalous(val key: TurnKey, val kind: AnomalyKind, val phase: TurnPhase) : ReplyOutcome {
        override fun toString(): String = "Anomalous($key, $kind, $phase)"
    }

    class Ignored(val reason: IgnoreReason) : ReplyOutcome {
        override fun toString(): String = "Ignored($reason)"
    }
}

enum class IntentMatch { SAME, CHANGED, UNKNOWN_TURN }

enum class DiscardMode {
    /** Only a turn the server cannot still be holding: never sent, settled, refused or provably not admitted. */
    ONLY_IF_SETTLED,

    /** The user explicitly stops tracking a turn the server may still execute. */
    ABANDON_TRACKING
}

sealed interface DiscardOutcome {
    class Discarded(val phase: TurnPhase) : DiscardOutcome {
        override fun toString(): String = "Discarded($phase)"
    }

    class Refused(val phase: TurnPhase?) : DiscardOutcome {
        override fun toString(): String = "Refused($phase)"
    }
}

enum class Reactivation { USER_REAUTHORIZED, USER_REVIEWED }

/**
 * The pure retry ledger of user turns.
 *
 * Contract with the future controller (none of it is proven by this JVM state):
 *  - A plan returns a new ledger and [TurnEffect.PersistBeforeSend]: the snapshot must be DURABLE before the frame is
 *    written. Without that, "never sent" cannot be claimed after a crash.
 *  - Every transition is explicit and pure; nothing is sent, scheduled, retried or minted here. A request id is
 *    minted only by [prepare], i.e. only for a deliberate new user turn.
 *  - The durable request id, text and deadline never change. A transport correlation id is chosen per call and may
 *    change; a call's reply is applied only if it names exactly that call.
 *  - Timeout, stale authority, disconnect, process loss and any other uncertainty require a FRESH attach and then a
 *    receipt lookup BEFORE any resubmit of the same id; admission is never repeated, and only the exact turn whose
 *    receipt it was can be settled by it.
 *  - Local expiry is not evidence: a turn that may have reached the server stays unknown until a lookup (or the
 *    server's own `expired`) proves otherwise.
 *
 * Resource limits ([StateLimits]) are LOCAL policy; reaching one is typed backpressure, never truncation.
 */
class TurnLedger private constructor(
    private val turns: Map<TurnKey, TurnEntry>,
    private val scopes: Map<EnrollmentScope, ScopeRecord>,
    private val nextCall: Long,
    private val correlations: List<String>,
    private val lastNanos: Long,
    private val textBytes: Long
) {
    val size: Int get() = turns.size

    fun entry(key: TurnKey): TurnEntry? = turns[key]

    /** All retained turns, oldest first. */
    val entries: List<TurnEntry> get() = Collections.unmodifiableList(turns.values.toMutableList())

    fun entriesFor(scope: EnrollmentScope): List<TurnEntry> = Collections.unmodifiableList(
        turns.values.filter {
            it.key.conversation.enrollment == scope
        }
    )

    fun scopeRecord(scope: EnrollmentScope): ScopeRecord? = scopes[scope]

    fun scopeStatus(scope: EnrollmentScope): ScopeStatus =
        scopes[scope]?.status ?: ScopeStatus.ACTIVE

    /** Scopes with recorded non-default state (stopped, under review, or owing content eviction). */
    val recordedScopes: Map<EnrollmentScope, ScopeRecord>
        get() = Collections.unmodifiableMap(LinkedHashMap(scopes))

    // -- preparing ---------------------------------------------------------------------------------------

    /**
     * A new, explicitly user-initiated turn. Needs a [ServerInstant] (proof that server time exists: the O5-only info
     * cannot make one), mints the durable id from that estimate, fixes the deadline strictly inside
     * `now < expires <= now + 300000`, and stores the immutable text. The caller persists before dispatching.
     */
    fun prepare(
        draft: TurnDraft,
        now: ServerInstant,
        entropy: RequestIdEntropy
    ): LedgerStep<PrepareOutcome> {
        fun refused(reason: PrepareRefusal, mint: MintRefusal? = null) =
            LedgerStep(this, PrepareOutcome.Refused(reason, mint), emptyList())
        if (monotonicRefusal(lastNanos, now) != null) return refused(PrepareRefusal.CLOCK_ROLLBACK)
        when (scopeStatus(draft.conversation.enrollment)) {
            ScopeStatus.STOPPED -> return refused(PrepareRefusal.SCOPE_STOPPED)
            ScopeStatus.NEEDS_REVIEW -> return refused(PrepareRefusal.SCOPE_NEEDS_REVIEW)
            ScopeStatus.ACTIVE -> Unit
        }
        if (!RelayCalls.validSubmitText(draft.text)) return refused(PrepareRefusal.INVALID_TEXT)
        val expires = RequestIds.proposeExpiry(now.serverMs, draft.ttlMs)
            ?: return refused(PrepareRefusal.INVALID_DEADLINE)
        val bytes = RelayJson.utf8Length(draft.text).toLong()
        if (turns.size >= StateLimits.MAX_RETAINED_TURNS) {
            return LedgerStep(this, PrepareOutcome.Backpressure(LedgerLimit.TURNS), emptyList())
        }
        if (textBytes + bytes > StateLimits.MAX_RETAINED_TURN_TEXT_BYTES) {
            return LedgerStep(
                this,
                PrepareOutcome.Backpressure(LedgerLimit.TEXT_BYTES),
                emptyList()
            )
        }
        val id = when (val minted = RequestIds.mint(now, entropy)) {
            is MintResult.Minted -> minted.id
            is MintResult.Refused -> return refused(PrepareRefusal.MINT_FAILED, minted.reason)
        }
        val key = TurnKey(draft.conversation, id)
        if (key in turns) return refused(PrepareRefusal.ID_COLLISION)
        val entry = TurnEntry(
            key,
            expires,
            now.serverMs,
            TurnFingerprint.of(key.conversation.conversationId, id, expires, draft.text),
            draft.text,
            sent = false,
            facts = null,
            closure = null,
            absentSoFar = false,
            uncertainty = null,
            hold = null,
            lastErrorCode = null,
            anomalies = 0,
            freshAfterSerial = 0,
            outstanding = null,
            lookupSentAtMs = null
        )
        val next = derive(
            turns = turns + (key to entry),
            lastNanos = maxOf(lastNanos, now.monotonicNanos),
            textBytes = textBytes + bytes
        )
        return LedgerStep(next, PrepareOutcome.Prepared(key), listOf(TurnEffect.PersistLedger))
    }

    /**
     * Whether a caller's view of a turn is exactly the prepared one. Changed content or deadline under an existing id
     * is [IntentMatch.CHANGED] and is never "fixed up": a different explicit turn needs a new id (a new [prepare]).
     */
    fun reconcileIntent(key: TurnKey, text: String, expiresAtMs: Long): IntentMatch {
        val entry = turns[key] ?: return IntentMatch.UNKNOWN_TURN
        val candidate = TurnFingerprint.of(
            key.conversation.conversationId,
            key.requestId,
            expiresAtMs,
            text
        )
        return if (candidate == entry.fingerprint) IntentMatch.SAME else IntentMatch.CHANGED
    }

    // -- planning calls ----------------------------------------------------------------------------------

    /**
     * Plans the (first, or after an absent lookup the repeated) submit of this exact turn on [token]'s binding. It is
     * refused while the turn may already have reached the server (lookup first), while any call is outstanding, once
     * a receipt proved admission, once held, and after the deadline.
     */
    fun planSubmit(
        key: TurnKey,
        authority: RelayAuthority,
        token: BindingToken,
        now: ServerInstant,
        correlation: CorrelationId
    ): LedgerStep<PlanOutcome> {
        val entry = turns[key]
        val common = commonRefusal(key, authority, token, now, correlation)
        val refusal = common ?: submitRefusal(entry!!, now)
        if (refusal != null) return planRefused(refusal)
        val turn = entry!!
        val ref =
            CallRef(
                nextCall,
                correlation,
                CallKind.SUBMIT,
                token.serial,
                key.conversation.enrollment
            )
        val call = try {
            RelayCalls.submit(token.ref, key.requestId, turn.expiresAtMs, turn.text!!)
        } catch (_: IllegalArgumentException) {
            return planRefused(PlanRefusal.INVALID_TURN)
        }
        val changed = turn.update(
            sent = true,
            absentSoFar = false,
            uncertainty = null,
            outstanding = ref,
            lookupSentAtMs = null
        )
        return LedgerStep(
            planned(changed, ref, now),
            PlanOutcome.Planned(TurnDispatch(key, ref, call)),
            listOf(TurnEffect.PersistLedger, TurnEffect.PersistBeforeSend(key))
        )
    }

    /**
     * Plans the receipt lookup (the read-only recovery call) for a turn that may have reached the server, or to
     * refresh an admitted turn that is not yet settled. After uncertainty it needs a binding NEWER than the
     * uncertainty (a fresh attach). A retained receipt is looked up whatever the age of the id or the deadline.
     */
    fun planLookup(
        key: TurnKey,
        authority: RelayAuthority,
        token: BindingToken,
        now: ServerInstant,
        correlation: CorrelationId
    ): LedgerStep<PlanOutcome> {
        val entry = turns[key]
        val common = commonRefusal(key, authority, token, now, correlation)
        val refusal = common ?: lookupRefusal(entry!!, token)
        if (refusal != null) return planRefused(refusal)
        val turn = entry!!
        val ref =
            CallRef(
                nextCall,
                correlation,
                CallKind.LOOKUP,
                token.serial,
                key.conversation.enrollment
            )
        val call = RelayCalls.receipt(key.conversation.conversationId, key.requestId)
        val changed = turn.update(outstanding = ref, lookupSentAtMs = now.serverMs)
        return LedgerStep(
            planned(changed, ref, now),
            PlanOutcome.Planned(TurnDispatch(key, ref, call)),
            emptyList()
        )
    }

    private fun planRefused(reason: PlanRefusal) =
        LedgerStep(this, PlanOutcome.Refused(reason), emptyList())

    private fun planned(entry: TurnEntry, ref: CallRef, now: ServerInstant): TurnLedger {
        val remembered =
            ArrayList<String>(minOf(correlations.size + 1, StateLimits.MAX_REMEMBERED_CORRELATIONS))
        val start = maxOf(0, correlations.size + 1 - StateLimits.MAX_REMEMBERED_CORRELATIONS)
        for (index in start until correlations.size) remembered.add(correlations[index])
        remembered.add(ref.correlation.value)
        return derive(
            turns = turns + (entry.key to entry),
            nextCall = Math.addExact(ref.serial, 1L),
            correlations = Collections.unmodifiableList(remembered),
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
    }

    private fun commonRefusal(
        key: TurnKey,
        authority: RelayAuthority,
        token: BindingToken,
        now: ServerInstant,
        correlation: CorrelationId
    ): PlanRefusal? {
        val entry = turns[key] ?: return PlanRefusal.UNKNOWN_TURN
        if (monotonicRefusal(lastNanos, now) != null) return PlanRefusal.CLOCK_ROLLBACK
        when (scopeStatus(key.conversation.enrollment)) {
            ScopeStatus.STOPPED -> return PlanRefusal.SCOPE_STOPPED
            ScopeStatus.NEEDS_REVIEW -> return PlanRefusal.SCOPE_NEEDS_REVIEW
            ScopeStatus.ACTIVE -> Unit
        }
        if (token.kind != BindingKind.RELAY) return PlanRefusal.BINDING_KIND
        if (token.scope != key.conversation.enrollment ||
            token.conversationId != key.conversation.conversationId
        ) {
            return PlanRefusal.BINDING_WRONG_SCOPE
        }
        if (!authority.isCurrent(token)) return PlanRefusal.BINDING_NOT_CURRENT
        if (entry.outstanding != null) return PlanRefusal.CALL_OUTSTANDING
        if (entry.closure != null) return PlanRefusal.ALREADY_CLOSED
        if (correlation.value in correlations) return PlanRefusal.CORRELATION_REUSED
        return null
    }

    private fun submitRefusal(entry: TurnEntry, now: ServerInstant): PlanRefusal? = when {
        entry.text == null -> PlanRefusal.CONTENT_EVICTED
        entry.facts != null -> PlanRefusal.ALREADY_ADMITTED
        entry.hold != null -> PlanRefusal.HELD
        entry.phase == TurnPhase.UNCERTAIN -> PlanRefusal.LOOKUP_REQUIRED_FIRST
        now.serverMs >= entry.expiresAtMs -> PlanRefusal.DEADLINE_PASSED
        else -> null
    }

    private fun lookupRefusal(entry: TurnEntry, token: BindingToken): PlanRefusal? {
        val olderThanUncertainty = token.serial <= entry.freshAfterSerial
        return when (entry.phase) {
            TurnPhase.PREPARED, TurnPhase.TERMINAL -> PlanRefusal.NOTHING_TO_LOOK_UP
            TurnPhase.UNCERTAIN ->
                if (olderThanUncertainty) PlanRefusal.FRESH_ATTACH_REQUIRED else null
            else -> null
        }
    }

    // -- replies, timeouts and transport loss ------------------------------------------------------------

    /**
     * Applies the typed outcome of the call named by [ref]. A reply that names no outstanding call (late, replayed,
     * from an older binding or a reused correlation) is ignored before any state changes.
     */
    fun onReply(ref: CallRef, outcome: RelayOutcome<Receipt>): LedgerStep<ReplyOutcome> {
        val entry = turns.values.firstOrNull { it.isAwaiting(ref) }
            ?: return LedgerStep(this, ReplyOutcome.Ignored(IgnoreReason.UNKNOWN_CALL), emptyList())
        return when (outcome) {
            is RelayOutcome.Success -> applyReceipt(entry, ref, outcome.value)
            is RelayOutcome.Failed -> applyError(entry, ref, outcome.error)
            is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> linkFailure(entry, ref, null)
        }
    }

    /** The call named by [ref] got no answer in time. A submit becomes uncertain; a lookup changes nothing known. */
    fun onTimeout(ref: CallRef): LedgerStep<ReplyOutcome> {
        val entry = turns.values.firstOrNull { it.isAwaiting(ref) }
            ?: return LedgerStep(this, ReplyOutcome.Ignored(IgnoreReason.UNKNOWN_CALL), emptyList())
        return settleCall(entry, ref, UncertainReason.TIMEOUT)
    }

    /**
     * The transport of [scope] (or just its binding [bindingSerial]) is gone. Every outstanding submit on it becomes
     * uncertain, every outstanding lookup is simply dropped; nothing is resent. Returns how many calls it ended.
     */
    fun bindingLost(scope: EnrollmentScope, bindingSerial: Long? = null): LedgerStep<Int> {
        var next = this
        var ended = 0
        val effects = LinkedHashSet<TurnEffect>()
        for (entry in turns.values) {
            val call = entry.outstanding ?: continue
            if (call.scope != scope ||
                (bindingSerial != null && call.bindingSerial != bindingSerial)
            ) {
                continue
            }
            val step = next.settleCall(entry, call, UncertainReason.TRANSPORT_LOST)
            next = step.ledger
            effects.addAll(step.effects)
            ended++
        }
        if (ended > 0) effects.add(TurnEffect.ReattachRequired(scope))
        return LedgerStep(next, ended, effects.toList())
    }

    private fun settleCall(
        entry: TurnEntry,
        ref: CallRef,
        reason: UncertainReason
    ): LedgerStep<ReplyOutcome> {
        val changed = when (ref.kind) {
            CallKind.SUBMIT -> entry.update(
                outstanding = null,
                lookupSentAtMs = null,
                uncertainty = reason,
                absentSoFar = false,
                freshAfterSerial = maxOf(entry.freshAfterSerial, ref.bindingSerial)
            )
            CallKind.LOOKUP -> entry.update(outstanding = null, lookupSentAtMs = null)
        }
        return commit(entry, changed, emptyList())
    }

    private fun linkFailure(
        entry: TurnEntry,
        ref: CallRef,
        anomaly: AnomalyKind?
    ): LedgerStep<ReplyOutcome> {
        val base = settleCall(entry, ref, UncertainReason.LINK_FAILURE)
        if (anomaly == null) return base
        val flagged = base.ledger.turns.getValue(entry.key).let {
            it.update(anomalies = bump(it.anomalies))
        }
        return base.ledger.commit(
            entry,
            flagged,
            listOf(TurnEffect.SurfaceAnomaly(entry.key, anomaly)),
            anomaly = anomaly
        )
    }

    private fun applyReceipt(
        entry: TurnEntry,
        ref: CallRef,
        receipt: Receipt
    ): LedgerStep<ReplyOutcome> {
        if (receipt.requestId != entry.key.requestId ||
            receipt.conversationId != entry.key.conversation.conversationId
        ) {
            return linkFailure(entry, ref, AnomalyKind.SCOPE_MISMATCH)
        }
        if (!consistent(receipt)) return linkFailure(entry, ref, AnomalyKind.INCONSISTENT_RECEIPT)
        val incoming = AdmissionFacts.of(receipt)
        return when (val merged = merge(entry.facts, incoming)) {
            is Merge.Conflict -> {
                val kept = entry.update(
                    outstanding = null,
                    lookupSentAtMs = null,
                    anomalies = bump(entry.anomalies)
                )
                commit(
                    entry,
                    kept,
                    listOf(TurnEffect.SurfaceAnomaly(entry.key, merged.kind)),
                    anomaly = merged.kind
                )
            }
            is Merge.Unchanged -> commit(
                entry,
                entry.update(outstanding = null, lookupSentAtMs = null),
                emptyList()
            )
            is Merge.Changed -> commit(
                entry,
                entry.update(
                    facts = merged.facts,
                    outstanding = null,
                    lookupSentAtMs = null,
                    uncertainty = null,
                    absentSoFar = false,
                    hold = null
                ),
                emptyList()
            )
        }
    }

    private fun applyError(
        entry: TurnEntry,
        ref: CallRef,
        error: RelayError
    ): LedgerStep<ReplyOutcome> {
        val scope = entry.key.conversation.enrollment
        val code = error.code
        val submit = ref.kind == CallKind.SUBMIT
        val cleared = entry.update(outstanding = null, lookupSentAtMs = null)
        return when {
            error.action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> {
                val record = ScopeRecord(
                    ScopeStatus.NEEDS_REVIEW,
                    scopes[scope]?.contentEvictionOwed == true
                )
                val next = recordScope(scope, record)
                next.commit(
                    entry,
                    revoked(cleared, ref, submit),
                    listOf(
                        TurnEffect.InvalidateRuntimeAuthority(scope),
                        TurnEffect.RequireUserReview(scope)
                    ),
                    scopesChanged = true
                )
            }
            error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE -> {
                val next = recordScope(scope, ScopeRecord(ScopeStatus.STOPPED, true))
                next.commit(
                    entry,
                    revoked(cleared, ref, submit),
                    listOf(
                        TurnEffect.EvictScopeContent(scope),
                        TurnEffect.InvalidateRuntimeAuthority(scope)
                    ),
                    scopesChanged = true
                )
            }
            code == RelayErrorCode.EXPIRED && entry.facts != null ->
                anomalous(entry, cleared, AnomalyKind.ABSENT_AFTER_ADMISSION)
            code == RelayErrorCode.EXPIRED -> close(
                entry,
                cleared,
                TurnClosure.NotAdmitted(NotAdmittedProof.SERVER_EXPIRED)
            )
            submit &&
                (code == RelayErrorCode.INVALID_REQUEST || code == RelayErrorCode.UNSUPPORTED) ->
                close(entry, cleared, TurnClosure.Refused(code))
            !submit && code == RelayErrorCode.NOT_FOUND -> lookupFoundNothing(entry, cleared)
            error.action == RelayErrorAction.REATTACH_AND_REFRESH -> {
                val effects = listOf(
                    TurnEffect.ReattachRequired(scope),
                    TurnEffect.RefreshHistoryAndTasks(entry.key.conversation)
                )
                commit(
                    entry,
                    uncertainAfter(cleared, ref, UncertainReason.STALE_AUTHORITY),
                    effects
                )
            }
            error.action == RelayErrorAction.RETRY_SAME_REQUEST ->
                commit(
                    entry,
                    uncertainAfter(cleared, ref, UncertainReason.TRANSIENT_ERROR),
                    emptyList()
                )
            else -> {
                val held = if (cleared.facts == null && cleared.closure == null) {
                    uncertainAfter(cleared, ref, UncertainReason.UNEXPECTED_ERROR)
                        .update(hold = TurnHold.UNEXPECTED_ERROR, lastErrorCode = code)
                } else {
                    cleared.update(lastErrorCode = code)
                }
                commit(entry, held, emptyList())
            }
        }
    }

    /** What a transient or stale answer does: a submit becomes uncertain; a lookup of a known turn changes nothing. */
    private fun uncertainAfter(
        cleared: TurnEntry,
        ref: CallRef,
        reason: UncertainReason
    ): TurnEntry = when {
        ref.kind == CallKind.SUBMIT ||
            (cleared.facts == null && reason == UncertainReason.STALE_AUTHORITY) ->
            cleared.update(
                uncertainty = reason,
                absentSoFar = false,
                freshAfterSerial = maxOf(cleared.freshAfterSerial, ref.bindingSerial)
            )
        else -> cleared
    }

    private fun revoked(cleared: TurnEntry, ref: CallRef, submit: Boolean): TurnEntry =
        if (submit || cleared.facts == null) {
            uncertainAfter(cleared, ref.asSubmit(), UncertainReason.STALE_AUTHORITY)
        } else {
            cleared
        }

    private fun CallRef.asSubmit(): CallRef =
        CallRef(serial, correlation, CallKind.SUBMIT, bindingSerial, scope)

    private fun lookupFoundNothing(entry: TurnEntry, cleared: TurnEntry): LedgerStep<ReplyOutcome> {
        val admitted = entry.facts != null
        if (admitted) return anomalous(entry, cleared, AnomalyKind.ABSENT_AFTER_ADMISSION)
        val sentAt = entry.lookupSentAtMs
        return if (sentAt != null && sentAt >= entry.expiresAtMs) {
            close(entry, cleared, TurnClosure.NotAdmitted(NotAdmittedProof.ABSENT_AFTER_DEADLINE))
        } else {
            commit(entry, cleared.update(absentSoFar = true, uncertainty = null), emptyList())
        }
    }

    private fun close(
        entry: TurnEntry,
        cleared: TurnEntry,
        closure: TurnClosure
    ): LedgerStep<ReplyOutcome> = commit(
        entry,
        cleared.update(
            sent = true,
            closure = closure,
            absentSoFar = false,
            uncertainty = null,
            hold = null,
            lastErrorCode = (closure as? TurnClosure.Refused)?.code ?: cleared.lastErrorCode
        ),
        emptyList()
    )

    private fun anomalous(
        entry: TurnEntry,
        cleared: TurnEntry,
        kind: AnomalyKind
    ): LedgerStep<ReplyOutcome> = commit(
        entry,
        cleared.update(anomalies = bump(cleared.anomalies)),
        listOf(TurnEffect.SurfaceAnomaly(entry.key, kind)),
        anomaly = kind
    )

    private fun bump(count: Int): Int = if (count < MAX_ANOMALIES) count + 1 else count

    private fun commit(
        before: TurnEntry,
        after: TurnEntry,
        effects: List<TurnEffect>,
        anomaly: AnomalyKind? = null,
        scopesChanged: Boolean = false
    ): LedgerStep<ReplyOutcome> {
        val next = derive(turns = turns + (before.key to after))
        val durable = scopesChanged || durableChanged(before, after)
        val all = if (durable) listOf(TurnEffect.PersistLedger) + effects else effects
        val result = if (anomaly != null) {
            ReplyOutcome.Anomalous(before.key, anomaly, after.phase)
        } else {
            ReplyOutcome.Applied(before.key, after.phase)
        }
        return LedgerStep(next, result, all)
    }

    private fun TurnEntry.isAwaiting(ref: CallRef): Boolean =
        outstanding?.let { it == ref && it.correlation == ref.correlation } == true

    private fun recordScope(scope: EnrollmentScope, record: ScopeRecord): TurnLedger =
        derive(scopes = scopes + (scope to record))

    private fun durableChanged(a: TurnEntry, b: TurnEntry): Boolean =
        a.text != b.text || a.sent != b.sent ||
            a.facts != b.facts || a.closure != b.closure || a.hold != b.hold ||
            a.lastErrorCode != b.lastErrorCode || a.anomalies != b.anomalies

    // -- scope decisions and housekeeping -----------------------------------------------------------------

    /**
     * Nulls the stored text of every turn in [scope] (the identity, uncertainty and server evidence stay, honestly) and
     * clears the owed eviction. A turn without text can never be submitted again.
     */
    fun evictContent(scope: EnrollmentScope): LedgerStep<Int> {
        var bytes = 0L
        var count = 0
        val updated = LinkedHashMap<TurnKey, TurnEntry>(turns)
        for ((key, entry) in turns) {
            if (key.conversation.enrollment != scope || entry.text == null) continue
            bytes += RelayJson.utf8Length(entry.text).toLong()
            updated[key] = entry.update(text = null)
            count++
        }
        val record = scopes[scope]
        val newScopes = if (record != null && record.contentEvictionOwed) {
            val reduced = LinkedHashMap(scopes)
            if (record.status == ScopeStatus.ACTIVE) {
                reduced.remove(scope)
            } else {
                reduced[scope] = ScopeRecord(record.status, false)
            }
            reduced
        } else {
            scopes
        }
        val changed = count > 0 || newScopes !== scopes
        val next = if (changed) {
            derive(
                turns = updated,
                scopes = newScopes,
                textBytes =
                textBytes - bytes
            )
        } else {
            this
        }
        return LedgerStep(
            next,
            count,
            if (changed) listOf(TurnEffect.PersistLedger) else emptyList()
        )
    }

    /**
     * The user re-authorised a stopped scope ([Reactivation.USER_REAUTHORIZED]) or reviewed a changed profile
     * ([Reactivation.USER_REVIEWED]); the reason must match the recorded status. Nothing is sent until then, and an
     * owed content eviction stays owed.
     */
    fun reactivate(scope: EnrollmentScope, why: Reactivation): LedgerStep<Boolean> {
        val record = scopes[scope]
        val matches = when (record?.status) {
            ScopeStatus.STOPPED -> why == Reactivation.USER_REAUTHORIZED
            ScopeStatus.NEEDS_REVIEW -> why == Reactivation.USER_REVIEWED
            else -> false
        }
        if (record == null || !matches) return LedgerStep(this, false, emptyList())
        val reduced = LinkedHashMap(scopes)
        if (record.contentEvictionOwed) {
            reduced[scope] = ScopeRecord(ScopeStatus.ACTIVE, true)
        } else {
            reduced.remove(scope)
        }
        return LedgerStep(derive(scopes = reduced), true, listOf(TurnEffect.PersistLedger))
    }

    /** Stops tracking a turn. A turn the server may still hold is only dropped by an explicit [DiscardMode]. */
    fun discard(
        key: TurnKey,
        mode: DiscardMode = DiscardMode.ONLY_IF_SETTLED
    ): LedgerStep<DiscardOutcome> {
        val entry = turns[key] ?: return LedgerStep(this, DiscardOutcome.Refused(null), emptyList())
        val settled = when (entry.phase) {
            TurnPhase.PREPARED,
            TurnPhase.TERMINAL,
            TurnPhase.NOT_ADMITTED,
            TurnPhase.REFUSED -> true
            else -> false
        }
        if (!settled && mode != DiscardMode.ABANDON_TRACKING) {
            return LedgerStep(this, DiscardOutcome.Refused(entry.phase), emptyList())
        }
        val remaining = LinkedHashMap(turns)
        remaining.remove(key)
        val bytes = entry.text?.let { RelayJson.utf8Length(it).toLong() } ?: 0L
        return LedgerStep(
            derive(turns = remaining, textBytes = textBytes - bytes),
            DiscardOutcome.Discarded(entry.phase),
            listOf(TurnEffect.PersistLedger)
        )
    }

    // -- internals ---------------------------------------------------------------------------------------

    private fun derive(
        turns: Map<TurnKey, TurnEntry> = this.turns,
        scopes: Map<EnrollmentScope, ScopeRecord> = this.scopes,
        nextCall: Long = this.nextCall,
        correlations: List<String> = this.correlations,
        lastNanos: Long = this.lastNanos,
        textBytes: Long = this.textBytes
    ): TurnLedger = TurnLedger(turns, scopes, nextCall, correlations, lastNanos, textBytes)

    // -- snapshot ------------------------------------------------------------------------------------------

    /**
     * The durable part of the ledger as plain values: every prepared turn exactly (identity, immutable text/deadline,
     * server evidence, closure, hold) and the scope decisions. Nothing transient is saved: no call, correlation,
     * binding, lookup time or freshness mark. Persisting it, and in what order, is the consumer's job.
     */
    fun snapshot(): TurnLedgerSnapshot = TurnLedgerSnapshot(
        turns.values.map { it.toSnapshot() },
        scopes.map { (scope, record) ->
            ScopeStateSnapshot(scope.toSnapshot(), record.status.name, record.contentEvictionOwed)
        }
    )

    private fun TurnEntry.toSnapshot() = TurnSnapshot(
        key.conversation.enrollment.toSnapshot(),
        key.conversation.conversationId.value,
        key.requestId.value,
        expiresAtMs,
        preparedAtMs,
        fingerprint.hex,
        text,
        sent,
        facts?.let {
            AdmissionFactsSnapshot(
                it.admissionId.value,
                it.authorityEpoch,
                it.status.wire,
                it.executionGeneration,
                it.outcome
            )
        },
        when (val shut = closure) {
            null -> null
            is TurnClosure.NotAdmitted -> "not_admitted:" + shut.proof.name.lowercase()
            is TurnClosure.Refused -> "refused:" + shut.code.wire
        },
        hold != null,
        lastErrorCode?.wire,
        anomalies
    )

    override fun toString(): String = "TurnLedger(turns=${turns.size}, scopes=${scopes.size})"

    internal sealed interface Merge {
        data object Unchanged : Merge

        class Changed(val facts: AdmissionFacts) : Merge

        class Conflict(val kind: AnomalyKind) : Merge
    }

    companion object {
        private const val MAX_ANOMALIES = 1_000

        fun empty(): TurnLedger = TurnLedger(emptyMap(), emptyMap(), 1, emptyList(), 0, 0)

        /**
         * Restores a ledger. Needs a fresh [ServerInstant] and a fresh (empty) [RelayAuthority]. Every turn that may
         * have reached the server comes back UNCERTAIN (process loss): it needs an attach made after this restore,
         * then a receipt lookup, before any resubmit. No identity, deadline, evidence or scope decision is invented:
         * each is re-validated, and a request id must carry exactly the server-time stamp it was prepared with.
         */
        internal fun restore(
            saved: TurnLedgerSnapshot,
            now: ServerInstant,
            authority: RelayAuthority
        ): RestoreResult<TurnLedger> {
            fun refused(reason: RestoreRefusal, path: String) = RestoreResult.Refused(reason, path)
            if (saved.turns.size >
                StateLimits.MAX_RETAINED_TURNS
            ) {
                return refused(RestoreRefusal.TOO_MANY, "turns")
            }
            val turns = LinkedHashMap<TurnKey, TurnEntry>()
            var textBytes = 0L
            var pastDeadline = false
            var clockBehind = false
            for ((index, item) in saved.turns.withIndex()) {
                val path = "turns[$index]"
                val scope =
                    item.scope.toScope()
                        ?: return refused(RestoreRefusal.INVALID_SCOPE, "$path.scope")
                val conversation = ConversationId.of(item.conversationId)
                    ?: return refused(RestoreRefusal.INVALID_ID, "$path.conversationId")
                val requestId = RequestId.of(item.requestId)
                    ?: return refused(RestoreRefusal.INVALID_ID, "$path.requestId")
                val key = TurnKey(ConversationScope(scope, conversation), requestId)
                if (key in turns) return refused(RestoreRefusal.DUPLICATE, path)
                val stamped = item.preparedAtMs in 0..StateLimits.SERVER_TIME_MAX_MS &&
                    RequestIds.timestampMs(requestId) == item.preparedAtMs
                if (!stamped) return refused(RestoreRefusal.INCONSISTENT, "$path.preparedAtMs")
                val window = item.expiresAtMs > item.preparedAtMs &&
                    item.expiresAtMs <= item.preparedAtMs + StateLimits.TURN_EXPIRY_MAX_AHEAD_MS
                if (!window) return refused(RestoreRefusal.INCONSISTENT, "$path.expiresAtMs")
                val fingerprint = TurnFingerprint.parse(item.fingerprint)
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.fingerprint")
                val text = item.text
                if (text != null) {
                    if (!RelayCalls.validSubmitText(
                            text
                        )
                    ) {
                        return refused(RestoreRefusal.INVALID_FIELD, "$path.text")
                    }
                    textBytes += RelayJson.utf8Length(text)
                    if (textBytes > StateLimits.MAX_RETAINED_TURN_TEXT_BYTES) {
                        return refused(RestoreRefusal.BUDGET, "$path.text")
                    }
                    val recomputed = TurnFingerprint.of(
                        conversation,
                        requestId,
                        item.expiresAtMs,
                        text
                    )
                    if (recomputed !=
                        fingerprint
                    ) {
                        return refused(RestoreRefusal.INCONSISTENT, "$path.fingerprint")
                    }
                }
                val facts = item.facts?.let {
                    parseFacts(it) ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.facts")
                }
                val closure = item.closure?.let {
                    parseClosure(it)
                        ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.closure")
                }
                val lastError = item.lastErrorCode?.let { wire ->
                    RelayErrorCode.entries.firstOrNull { it.wire == wire }
                        ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.lastErrorCode")
                }
                if (item.anomalies !in
                    0..MAX_ANOMALIES
                ) {
                    return refused(RestoreRefusal.INVALID_FIELD, "$path.anomalies")
                }
                val open = facts == null && closure == null
                val shape = when {
                    !item.sent -> open && !item.held && lastError == null
                    facts != null -> closure == null && !item.held
                    closure != null -> !item.held
                    else -> true
                }
                if (!shape) return refused(RestoreRefusal.INCONSISTENT, path)
                val unresolved = item.sent && open
                if (unresolved && now.serverMs >= item.expiresAtMs) pastDeadline = true
                if (item.preparedAtMs > now.serverMs + StateLimits.ID_FUTURE_MS) clockBehind = true
                turns[key] = TurnEntry(
                    key,
                    item.expiresAtMs,
                    item.preparedAtMs,
                    fingerprint,
                    text,
                    item.sent,
                    facts,
                    closure,
                    absentSoFar = false,
                    uncertainty = if (unresolved) UncertainReason.PROCESS_LOSS else null,
                    hold = if (item.held) TurnHold.UNEXPECTED_ERROR else null,
                    lastErrorCode = lastError,
                    anomalies = item.anomalies,
                    freshAfterSerial = authority.lastSerial,
                    outstanding = null,
                    lookupSentAtMs = null
                )
            }
            val scopes = LinkedHashMap<EnrollmentScope, ScopeRecord>()
            for ((index, item) in saved.scopes.withIndex()) {
                val path = "scopes[$index]"
                val scope =
                    item.scope.toScope()
                        ?: return refused(RestoreRefusal.INVALID_SCOPE, "$path.scope")
                val status = ScopeStatus.entries.firstOrNull { it.name == item.status }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.status")
                if (status == ScopeStatus.ACTIVE && !item.contentEvictionOwed) {
                    return refused(RestoreRefusal.INCONSISTENT, path)
                }
                if (scopes.put(scope, ScopeRecord(status, item.contentEvictionOwed)) != null) {
                    return refused(RestoreRefusal.DUPLICATE, path)
                }
            }
            val notices = ArrayList<RestoreNotice>()
            if (pastDeadline) notices.add(RestoreNotice.UNRESOLVED_TURNS_PAST_DEADLINE)
            if (clockBehind) notices.add(RestoreNotice.SERVER_CLOCK_BEHIND_SNAPSHOT)
            val ledger = TurnLedger(turns, scopes, 1, emptyList(), now.monotonicNanos, textBytes)
            return RestoreResult.Restored(ledger, notices)
        }

        private fun parseFacts(saved: AdmissionFactsSnapshot): AdmissionFacts? {
            val admission = AdmissionId.of(saved.admissionId) ?: return null
            val status =
                ReceiptStatus.entries.firstOrNull { it.wire == saved.status } ?: return null
            val generation = saved.executionGeneration
            val outcome = saved.outcome
            val validOutcome = outcome == null ||
                (
                    outcome.length in 1..RelayLimits.ID_CHARS &&
                        outcome.all { it in 'a'..'z' || it == '_' }
                    )
            val valid = saved.authorityEpoch >= 0 && (generation ?: 0) >= 0 && validOutcome &&
                consistent(status, outcome, generation)
            return if (valid) {
                AdmissionFacts(
                    admission,
                    saved.authorityEpoch,
                    status,
                    generation,
                    outcome
                )
            } else {
                null
            }
        }

        private fun parseClosure(saved: String): TurnClosure? {
            val kind = saved.substringBefore(':')
            val detail = saved.substringAfter(':', "")
            return when (kind) {
                "not_admitted" -> NotAdmittedProof.entries.firstOrNull {
                    it.name.lowercase() ==
                        detail
                }
                    ?.let { TurnClosure.NotAdmitted(it) }
                "refused" -> RelayErrorCode.entries.firstOrNull { it.wire == detail }
                    ?.let { TurnClosure.Refused(it) }
                else -> null
            }
        }

        private fun rank(status: ReceiptStatus): Int = when (status) {
            ReceiptStatus.UNKNOWN -> 0
            ReceiptStatus.QUEUED -> 1
            ReceiptStatus.STARTED -> 2
            ReceiptStatus.TERMINAL -> 3
        }

        /** Status, outcome and generation must go together, following the actual DTOs (queued: generation null). */
        internal fun consistent(receipt: Receipt): Boolean =
            consistent(receipt.status, receipt.outcome, receipt.executionGeneration)

        internal fun consistent(
            status: ReceiptStatus,
            outcome: String?,
            generation: Long?
        ): Boolean = when (status) {
            ReceiptStatus.QUEUED -> outcome == null && generation == null
            ReceiptStatus.STARTED -> outcome == null && (generation ?: 0) >= 1
            ReceiptStatus.TERMINAL, ReceiptStatus.UNKNOWN -> true
        }

        /**
         * Evidence only ever advances. UNKNOWN never overrides proof and proof replaces UNKNOWN; a regression, another
         * admission/epoch, a changed final outcome or a vanishing generation is a conflict that keeps what is known.
         */
        internal fun merge(current: AdmissionFacts?, incoming: AdmissionFacts): Merge {
            if (current == null) return Merge.Changed(incoming)
            val sameAdmission = incoming.admissionId == current.admissionId
            if (!sameAdmission) return Merge.Conflict(AnomalyKind.ADMISSION_MISMATCH)
            if (incoming.authorityEpoch != current.authorityEpoch) {
                return Merge.Conflict(AnomalyKind.AUTHORITY_EPOCH_MISMATCH)
            }
            if (incoming.status == ReceiptStatus.UNKNOWN) return Merge.Unchanged
            if (current.status == ReceiptStatus.UNKNOWN) return Merge.Changed(incoming)
            val regressed = rank(incoming.status) < rank(current.status)
            if (regressed) return Merge.Conflict(AnomalyKind.STATUS_REGRESSION)
            val before = current.executionGeneration
            val after = incoming.executionGeneration
            if (before != null && (after == null || after < before)) {
                return Merge.Conflict(AnomalyKind.GENERATION_REGRESSION)
            }
            if (incoming.status == current.status) {
                if (incoming.status == ReceiptStatus.TERMINAL) {
                    val sameOutcome = incoming.outcome == current.outcome
                    if (!sameOutcome) return Merge.Conflict(AnomalyKind.OUTCOME_CONFLICT)
                    if (after != before) return Merge.Conflict(AnomalyKind.GENERATION_REGRESSION)
                    return Merge.Unchanged
                }
                return if (after == before) Merge.Unchanged else Merge.Changed(incoming)
            }
            return Merge.Changed(incoming)
        }
    }
}
