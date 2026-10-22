package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Acknowledged
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.OutboxReference
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayEvent
import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.TaskId
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import java.util.Collections

/** What must be re-read from the server before the local view can be called current. */
enum class RefreshKind { HISTORY, TASKS }

/** Why a refresh is owed. A refresh is a requirement on the consumer, never an execution effect. */
enum class RefreshCause {
    /** Every attach is followed by a fresh history and task snapshot. */
    ATTACHED,
    GAP_EVENT,
    SNAPSHOT_REQUIRED,
    EPOCH_MISMATCH,
    SEQUENCE_DISCONTINUITY,
    CHANGED_EVENT,
    CONVERSATION_DELETED,
    SUPERSEDED_COPY,
    OUTBOX_GAP,
    OUTBOX_BASELINE_UNKNOWN,
    OUTBOX_GAP_REGRESSED
}

/** A refresh owed for one conversation, or (conversation = null) for the whole enrollment. */
class RefreshObligation internal constructor(
    val conversation: ConversationScope?,
    val kind: RefreshKind,
    val cause: RefreshCause
) {
    override fun equals(other: Any?): Boolean =
        other is RefreshObligation && other.conversation == conversation && other.kind == kind &&
            other.cause == cause

    override fun hashCode(): Int = 31 * (conversation?.hashCode() ?: 0) + kind.hashCode()

    override fun toString(): String =
        "RefreshObligation($kind, $cause, ${if (conversation == null) "all" else "one"})"
}

/**
 * The exact identity an event or an O5 copy is deduplicated by. The two spaces never meet: a final-text copy keeps the
 * server's OPAQUE text (no trimming, case folding or parsing, and no conversion to a canonical id), a canonical key
 * is scoped by its conversation.
 */
sealed interface DedupKey {
    data class Canonical(val conversationId: ConversationId, val eventId: EventId) : DedupKey

    class Opaque(val conversationRef: String, val eventRef: String) : DedupKey {
        override fun equals(other: Any?): Boolean =
            other is Opaque && other.conversationRef == conversationRef &&
                other.eventRef == eventRef

        override fun hashCode(): Int = 31 * conversationRef.hashCode() + eventRef.hashCode()

        override fun toString(): String = "Opaque(redacted)"
    }
}

/** A local resource limit of the delivery state (policy). The page is NOT consumed and nothing is dropped. */
enum class DeliveryLimit { SEEN_KEYS, PENDING_ACKS }

enum class DeliveryIgnore { UNKNOWN_CALL, NO_SESSION, STALE_BINDING, WRONG_BATCH }

enum class RejectReason {
    /** The page names another replay epoch than this binding's, without asking for a refresh. */
    FOREIGN_EPOCH,

    /** An event names another conversation or binding than the one attached. */
    FOREIGN_EVENT,

    /** An event is not after the committed cursor. */
    STALE_SEQUENCE
}

enum class SessionRefusal {
    NO_SESSION,
    BINDING_NOT_CURRENT,
    READ_OUTSTANDING,
    BATCH_OUTSTANDING,
    BINDING_RETIRED,
    INVALID_LIMIT,
    WRONG_SCOPE
}

class ReadRef internal constructor(
    val serial: Long,
    val correlation: CorrelationId,
    val bindingSerial: Long
) {
    override fun equals(other: Any?): Boolean = other is ReadRef && other.serial == serial

    override fun hashCode(): Int = serial.hashCode()

    override fun toString(): String = "ReadRef(serial=$serial)"
}

class ReadPlan internal constructor(val ref: ReadRef, val call: RelayCall<EventsPage>) {
    override fun toString(): String = "ReadPlan($ref)"
}

sealed interface ReadPlanOutcome {
    class Planned(val plan: ReadPlan) : ReadPlanOutcome {
        override fun toString(): String = "Planned($plan)"
    }

    class Refused(val reason: SessionRefusal) : ReadPlanOutcome {
        override fun toString(): String = "Refused($reason)"
    }
}

/**
 * Events staged from one page. NOTHING has advanced: the committed cursor, the dedup table and the refresh
 * obligations change only on [EnrollmentDelivery.commitEvents]. Every event is display-only data: none is an action,
 * a prompt answer, a submit or speech, and a prompt event must be re-fetched before anything is offered.
 */
class StagedEvents internal constructor(
    val batchId: Long,
    /** New events in server order (duplicates already removed). */
    val events: List<RelayEvent>,
    val duplicates: Int,
    /** The cursor the next read will carry once this batch is committed. */
    val highSequence: Long,
    /** Owed once committed. */
    val refresh: List<RefreshObligation>,
    /** The server has retired this binding (a gap or a delete): attach again before reading more. */
    val retiresBinding: Boolean,
    val conversationDeleted: Boolean
) {
    override fun toString(): String =
        "StagedEvents(batch=$batchId, events=${events.size}, duplicates=$duplicates, high=$highSequence)"
}

sealed interface StageOutcome {
    class Staged(val batch: StagedEvents) : StageOutcome {
        override fun toString(): String = "Staged($batch)"
    }

    /** The page was refused whole; the session stays usable. */
    class Rejected(val reason: RejectReason) : StageOutcome {
        override fun toString(): String = "Rejected($reason)"
    }

    /** The binding can no longer be used: obligations were recorded and the session dropped. */
    class NeedsReattach(val cause: RefreshCause) : StageOutcome {
        override fun toString(): String = "NeedsReattach($cause)"
    }

    class Backpressure(val limit: DeliveryLimit) : StageOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }

    class RetrySameRead(val code: RelayErrorCode) : StageOutcome {
        override fun toString(): String = "RetrySameRead(${code.wire})"
    }

    class Failed(val code: RelayErrorCode) : StageOutcome {
        override fun toString(): String = "Failed(${code.wire})"
    }

    class Ignored(val reason: DeliveryIgnore) : StageOutcome {
        override fun toString(): String = "Ignored($reason)"
    }
}

sealed interface EventCommit {
    /** Other commits filled the table since staging: the batch stays staged; prune explicitly, then commit again. */
    class Backpressure(val limit: DeliveryLimit) : EventCommit {
        override fun toString(): String = "Backpressure($limit)"
    }

    class Committed(val committedSequence: Long, val newKeys: Int) : EventCommit {
        override fun toString(): String = "Committed(seq=$committedSequence, keys=$newKeys)"
    }

    class Refused(val reason: DeliveryIgnore) : EventCommit {
        override fun toString(): String = "Refused($reason)"
    }
}

class OutboxRef internal constructor(val serial: Long, val correlation: CorrelationId) {
    override fun equals(other: Any?): Boolean = other is OutboxRef && other.serial == serial

    override fun hashCode(): Int = serial.hashCode()

    override fun toString(): String = "OutboxRef(serial=$serial)"
}

class OutboxReadPlan internal constructor(val ref: OutboxRef, val call: RelayCall<OutboxPage>) {
    override fun toString(): String = "OutboxReadPlan($ref)"
}

class AckRef internal constructor(
    val serial: Long,
    val correlation: CorrelationId,
    internal val ids: List<DeliveryId>
) {
    override fun equals(other: Any?): Boolean = other is AckRef && other.serial == serial

    override fun hashCode(): Int = serial.hashCode()

    override fun toString(): String = "AckRef(serial=$serial, ids=${ids.size})"
}

class AckPlan internal constructor(val ref: AckRef, val call: RelayCall<Acknowledged>) {
    override fun toString(): String = "AckPlan($ref)"
}

enum class OutboxRefusal {
    READ_OUTSTANDING,
    BATCH_OUTSTANDING,
    ACK_OUTSTANDING,
    NOTHING_TO_ACK,
    INVALID_LIMIT
}

sealed interface OutboxPlanOutcome<out T> {
    class Planned<T>(val plan: T) : OutboxPlanOutcome<T> {
        override fun toString(): String = "Planned($plan)"
    }

    class Refused(val reason: OutboxRefusal) : OutboxPlanOutcome<Nothing> {
        override fun toString(): String = "Refused($reason)"
    }
}

/**
 * O5 recovered content staged from one `relay.outbox.read`. DISPLAY ONLY: a copy never speaks, submits, answers or
 * opens a binding. Nothing is acknowledged or remembered until [EnrollmentDelivery.commitOutbox]; the delivery ids to
 * acknowledge are owed only after that, i.e. after the consumer stored the content.
 */
class StagedOutbox internal constructor(
    val batchId: Long,
    /** Items not stored before, oldest first. */
    val items: List<OutboxItem>,
    /** Items whose event key is already stored: nothing to show, but they are acknowledged too. */
    val duplicates: List<OutboxItem>,
    /** The newest task-change copy per task in this batch (older snapshots of the same task are superseded). */
    val newestPerTask: Map<TaskId, OutboxItem.Change>,
    /** Tasks whose copy says `needs_input`: fetch `relay.task.get` before showing any prompt. */
    val fetchBeforePrompt: List<TaskId>,
    val gapGeneration: Long,
    val refresh: List<RefreshObligation>
) {
    override fun toString(): String =
        "StagedOutbox(batch=$batchId, items=${items.size}, duplicates=${duplicates.size}, gap=$gapGeneration)"
}

sealed interface OutboxStageOutcome {
    class Staged(val batch: StagedOutbox) : OutboxStageOutcome {
        override fun toString(): String = "Staged($batch)"
    }

    class Backpressure(val limit: DeliveryLimit) : OutboxStageOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }

    class RetrySameRead(val code: RelayErrorCode) : OutboxStageOutcome {
        override fun toString(): String = "RetrySameRead(${code.wire})"
    }

    class Failed(val code: RelayErrorCode) : OutboxStageOutcome {
        override fun toString(): String = "Failed(${code.wire})"
    }

    class Ignored(val reason: DeliveryIgnore) : OutboxStageOutcome {
        override fun toString(): String = "Ignored($reason)"
    }
}

sealed interface OutboxCommit {
    class Backpressure(val limit: DeliveryLimit) : OutboxCommit {
        override fun toString(): String = "Backpressure($limit)"
    }

    class Committed(val newKeys: Int, val acknowledgementsOwed: Int) : OutboxCommit {
        override fun toString(): String = "Committed(keys=$newKeys, owed=$acknowledgementsOwed)"
    }

    class Refused(val reason: DeliveryIgnore) : OutboxCommit {
        override fun toString(): String = "Refused($reason)"
    }
}

sealed interface AckOutcome {
    class Confirmed(val removed: Int, val stillPending: Int) : AckOutcome {
        override fun toString(): String = "Confirmed(removed=$removed, pending=$stillPending)"
    }

    /** No acknowledgement happened: the ids stay pending and repeating the acknowledgement is safe. */
    class NotAcknowledged(val code: RelayErrorCode?) : AckOutcome {
        override fun toString(): String = "NotAcknowledged(${code?.wire})"
    }

    class Ignored(val reason: DeliveryIgnore) : AckOutcome {
        override fun toString(): String = "Ignored($reason)"
    }
}

sealed interface DeliveryEffect {
    /** Content or position changed: persist the snapshot (durable-write ordering is the consumer's, not proven here). */
    data object PersistDelivery : DeliveryEffect

    data class RefreshRequired(val obligations: List<RefreshObligation>) : DeliveryEffect

    data class ReattachRequired(val scope: EnrollmentScope) : DeliveryEffect

    data class EvictScopeContent(val scope: EnrollmentScope) : DeliveryEffect

    data class InvalidateRuntimeAuthority(val scope: EnrollmentScope) : DeliveryEffect

    data class RequireUserReview(val scope: EnrollmentScope) : DeliveryEffect

    /** A canonical delete: invalidate BOTH devices' cached lineage for the conversation. */
    data class ConversationDeleted(val conversation: ConversationScope) : DeliveryEffect

    /** Stored O5 content awaits acknowledgement; planning the acknowledgement is the consumer's explicit call. */
    data class AcknowledgementsOwed(val count: Int) : DeliveryEffect
}

class DeliveryStep<out R>(
    val delivery: EnrollmentDelivery,
    val result: R,
    val effects: List<DeliveryEffect> = emptyList()
) {
    override fun toString(): String = "DeliveryStep($result, effects=${effects.size})"
}

/**
 * Everything the server pushes or leaves for ONE enrollment: live events of its single binding, and O5 copies.
 *
 * Contract (not proven here): events and copies are DISPLAY-ONLY; the committed cursor and every dedup/ack fact advance
 * ONLY on a commit that the consumer issues after it durably stored the content, so a failed store can be aborted and
 * the same page read again; a gap, `snapshot_required`, a larger O5 `gap_generation` or an unknown O5 baseline produce
 * refresh obligations and never an execution effect; stale or foreign callbacks are rejected before any state change.
 * The dedup table is bounded (policy): a full table refuses a page, and only an explicit [retireOlderThan] relaxes it.
 */
class EnrollmentDelivery private constructor(
    val enrollment: EnrollmentScope,
    private val seen: Map<DedupKey, Long>,
    private val obligations: List<RefreshObligation>,
    private val gapBaseline: Long?,
    private val pendingAcks: List<DeliveryId>,
    private val live: Live?,
    private val outbox: OutboxFlight,
    private val nextSerial: Long
) {
    val seenCount: Int get() = seen.size

    /** Refreshes owed before the local view may be called current. */
    val refreshOwed: List<RefreshObligation> get() = Collections.unmodifiableList(obligations)

    /** The O5 `gap_generation` last committed, or null if none was (an unknown baseline is a refresh, not zero). */
    val committedGapGeneration: Long? get() = gapBaseline

    /** O5 deliveries stored but not yet acknowledged, oldest first. */
    val acknowledgementsOwed: List<DeliveryId> get() = Collections.unmodifiableList(pendingAcks)

    /** The cursor the next `relay.events.read` carries; moves only on commit. Null without a session. */
    val committedSequence: Long? get() = live?.committedSequence

    val hasSession: Boolean get() = live != null

    fun hasSeen(key: DedupKey): Boolean = key in seen

    // -- live events ---------------------------------------------------------------------------------------

    /**
     * Starts the event session of [token] (the one current binding of this enrollment). Replaces an older session and
     * drops whatever it had staged. Owes a fresh history and task snapshot for the conversation, as every attach does.
     */
    fun attach(authority: RelayAuthority, token: BindingToken): DeliveryStep<SessionRefusal?> {
        if (token.scope != enrollment) return DeliveryStep(this, SessionRefusal.WRONG_SCOPE)
        if (!authority.isCurrent(
                token
            )
        ) {
            return DeliveryStep(this, SessionRefusal.BINDING_NOT_CURRENT)
        }
        val owed = withObligations(
            listOf(RefreshKind.HISTORY, RefreshKind.TASKS).map {
                RefreshObligation(token.conversation, it, RefreshCause.ATTACHED)
            }
        )
        val next = copy(
            live = Live(token, token.baselineSequence, null, null, false),
            obligations = owed
        )
        return DeliveryStep(
            next,
            null,
            listOf(DeliveryEffect.PersistDelivery, DeliveryEffect.RefreshRequired(owed))
        )
    }

    fun planRead(
        authority: RelayAuthority,
        correlation: CorrelationId,
        limit: Int? = null
    ): DeliveryStep<ReadPlanOutcome> {
        fun refused(reason: SessionRefusal) = DeliveryStep(this, ReadPlanOutcome.Refused(reason))
        val session = live ?: return refused(SessionRefusal.NO_SESSION)
        if (!authority.isCurrent(session.token)) return refused(SessionRefusal.BINDING_NOT_CURRENT)
        if (session.retired) return refused(SessionRefusal.BINDING_RETIRED)
        if (session.outstanding != null) return refused(SessionRefusal.READ_OUTSTANDING)
        if (session.staged != null) return refused(SessionRefusal.BATCH_OUTSTANDING)
        val call = try {
            RelayCalls.events(
                session.token.ref,
                session.token.replayEpoch,
                session.committedSequence,
                limit
            )
        } catch (_: IllegalArgumentException) {
            return refused(SessionRefusal.INVALID_LIMIT)
        }
        val ref = ReadRef(nextSerial, correlation, session.token.serial)
        val next = copy(
            live = session.copy(outstanding = ref),
            nextSerial = Math.addExact(nextSerial, 1L)
        )
        return DeliveryStep(next, ReadPlanOutcome.Planned(ReadPlan(ref, call)))
    }

    /** The read named by [ref] got no answer in time: the cursor is unchanged, so the same read can be repeated. */
    fun onReadTimeout(ref: ReadRef): DeliveryStep<StageOutcome> {
        val session = live
        if (session == null || session.outstanding != ref) {
            return DeliveryStep(this, StageOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        return DeliveryStep(
            copy(live = session.copy(outstanding = null)),
            StageOutcome.RetrySameRead(RelayErrorCode.TEMPORARILY_UNAVAILABLE)
        )
    }

    /** The transport (or just this binding) is gone: the session and anything staged in it are dropped. */
    fun bindingLost(token: BindingToken? = null): DeliveryStep<Boolean> {
        val session = live ?: return DeliveryStep(this, false)
        if (token != null && session.token.serial != token.serial) return DeliveryStep(this, false)
        return DeliveryStep(
            copy(live = null),
            true,
            listOf(DeliveryEffect.ReattachRequired(enrollment))
        )
    }

    /**
     * Stages the answer to [ref]. The page is checked against the CURRENT binding's exact conversation, binding id and
     * replay epoch before anything is staged; a stale session, foreign epoch or foreign event is refused with no state
     * change beyond the finished call.
     */
    fun stageEvents(
        authority: RelayAuthority,
        ref: ReadRef,
        outcome: RelayOutcome<EventsPage>
    ): DeliveryStep<StageOutcome> {
        val session = live
        if (session == null || session.outstanding != ref) {
            return DeliveryStep(this, StageOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        if (!authority.isCurrent(session.token)) {
            return DeliveryStep(
                copy(live = null),
                StageOutcome.Ignored(DeliveryIgnore.STALE_BINDING)
            )
        }
        val cleared = session.copy(outstanding = null)
        return when (outcome) {
            is RelayOutcome.Success -> stagePage(session, outcome.value)
            is RelayOutcome.Failed -> eventsError(cleared, outcome.error)
            is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid ->
                DeliveryStep(copy(live = cleared), StageOutcome.Failed(RelayErrorCode.UNKNOWN))
        }
    }

    private fun eventsError(session: Live, error: RelayError): DeliveryStep<StageOutcome> {
        val code = error.code
        return when (error.action) {
            RelayErrorAction.REATTACH_AND_REFRESH -> dropSession(
                session,
                RefreshCause.SNAPSHOT_REQUIRED
            )
            RelayErrorAction.STOP_AND_EVICT_SCOPE -> {
                val step = dropSession(session, null)
                DeliveryStep(
                    step.delivery,
                    step.result,
                    listOf(
                        DeliveryEffect.EvictScopeContent(enrollment),
                        DeliveryEffect.InvalidateRuntimeAuthority(enrollment)
                    )
                )
            }
            RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> {
                val step = dropSession(session, null)
                DeliveryStep(
                    step.delivery,
                    step.result,
                    listOf(
                        DeliveryEffect.InvalidateRuntimeAuthority(enrollment),
                        DeliveryEffect.RequireUserReview(enrollment)
                    )
                )
            }
            RelayErrorAction.RETRY_SAME_REQUEST ->
                DeliveryStep(copy(live = session), StageOutcome.RetrySameRead(code))
            RelayErrorAction.FAILED -> DeliveryStep(copy(live = session), StageOutcome.Failed(code))
        }
    }

    private fun dropSession(session: Live, cause: RefreshCause?): DeliveryStep<StageOutcome> {
        val conversation = session.token.conversation
        val owed = if (cause == null) {
            obligations
        } else {
            withObligations(
                listOf(RefreshKind.HISTORY, RefreshKind.TASKS).map {
                    RefreshObligation(conversation, it, cause)
                }
            )
        }
        val effects = ArrayList<DeliveryEffect>()
        if (cause != null) {
            effects.add(DeliveryEffect.PersistDelivery)
            effects.add(DeliveryEffect.RefreshRequired(owed))
        }
        effects.add(DeliveryEffect.ReattachRequired(enrollment))
        return DeliveryStep(
            copy(live = null, obligations = owed),
            StageOutcome.NeedsReattach(cause ?: RefreshCause.SNAPSHOT_REQUIRED),
            effects
        )
    }

    private fun stagePage(original: Live, page: EventsPage): DeliveryStep<StageOutcome> {
        val session = original.copy(outstanding = null)
        val token = session.token
        if (page.replayEpoch != token.replayEpoch) {
            // The server answers a cursor it does not recognise with its own epoch and `snapshot_required`.
            if (page.snapshotRequired) return dropSession(session, RefreshCause.EPOCH_MISMATCH)
            return DeliveryStep(
                copy(live = session),
                StageOutcome.Rejected(RejectReason.FOREIGN_EPOCH)
            )
        }
        for (event in page.events) {
            val ours = event.conversationId == token.conversationId &&
                event.bindingId == token.bindingId &&
                event.replayEpoch == token.replayEpoch
            if (!ours) {
                return DeliveryStep(
                    copy(live = session),
                    StageOutcome.Rejected(RejectReason.FOREIGN_EVENT)
                )
            }
            if (event.sequence <= session.committedSequence) {
                return DeliveryStep(
                    copy(live = session),
                    StageOutcome.Rejected(RejectReason.STALE_SEQUENCE)
                )
            }
        }
        if (hasDiscontinuity(page, session.committedSequence)) {
            return dropSession(session, RefreshCause.SEQUENCE_DISCONTINUITY)
        }
        val fresh = ArrayList<RelayEvent>()
        val batchKeys = HashSet<DedupKey>()
        var duplicates = 0
        for (event in page.events) {
            val key = DedupKey.Canonical(event.conversationId, event.eventId)
            if (key in seen || !batchKeys.add(key)) duplicates++ else fresh.add(event)
        }
        if (seen.size + fresh.size > StateLimits.MAX_SEEN_KEYS) {
            // Not consumed: the read stays outstanding so the SAME answer can be applied after room was made.
            return DeliveryStep(
                copy(live = original),
                StageOutcome.Backpressure(DeliveryLimit.SEEN_KEYS)
            )
        }
        val deleted = page.events.any {
            (it.payload as? EventPayload.ConversationChanged)?.deleted ==
                true
        }
        val gap = page.events.any { it.payload is EventPayload.Gap }
        val changed = page.events.any { it.payload is EventPayload.Changed }
        val owedNow = ArrayList<RefreshObligation>()
        fun owe(cause: RefreshCause) {
            for (kind in RefreshKind.entries) {
                owedNow.add(
                    RefreshObligation(token.conversation, kind, cause)
                )
            }
        }
        if (gap) owe(RefreshCause.GAP_EVENT)
        if (page.snapshotRequired && !gap) owe(RefreshCause.SNAPSHOT_REQUIRED)
        if (deleted) owe(RefreshCause.CONVERSATION_DELETED)
        if (changed) owe(RefreshCause.CHANGED_EVENT)
        val high = page.events.lastOrNull()?.sequence ?: session.committedSequence
        val batch = StagedEvents(
            nextSerial,
            Collections.unmodifiableList(fresh),
            duplicates,
            high,
            Collections.unmodifiableList(owedNow),
            page.snapshotRequired || gap || deleted,
            deleted
        )
        val next =
            copy(live = session.copy(staged = batch), nextSerial = Math.addExact(nextSerial, 1L))
        return DeliveryStep(next, StageOutcome.Staged(batch))
    }

    /** Events are contiguous after the cursor, except where the server replaced its backlog with a gap or tombstone. */
    private fun hasDiscontinuity(page: EventsPage, committed: Long): Boolean {
        var expected = committed + 1
        for (event in page.events) {
            val payload = event.payload
            val replaced = payload is EventPayload.Gap ||
                (payload is EventPayload.ConversationChanged && payload.deleted)
            if (event.sequence != expected && !replaced) return true
            expected = event.sequence + 1
        }
        return false
    }

    /**
     * The consumer durably stored the staged events: NOW the cursor advances, their keys become known and the owed
     * refreshes are recorded. A binding the server retired (gap, delete) takes no further reads.
     */
    fun commitEvents(batchId: Long, now: ServerInstant): DeliveryStep<EventCommit> {
        val session = live
        val batch = session?.staged
        if (session == null || batch == null || batch.batchId != batchId) {
            return DeliveryStep(this, EventCommit.Refused(DeliveryIgnore.WRONG_BATCH))
        }
        if (seen.size + batch.events.size > StateLimits.MAX_SEEN_KEYS) {
            return DeliveryStep(this, EventCommit.Backpressure(DeliveryLimit.SEEN_KEYS))
        }
        val newSeen = LinkedHashMap(seen)
        for (event in batch.events) {
            newSeen[DedupKey.Canonical(event.conversationId, event.eventId)] = now.serverMs
        }
        val owed = withObligations(batch.refresh)
        val effects = ArrayList<DeliveryEffect>()
        effects.add(DeliveryEffect.PersistDelivery)
        if (batch.refresh.isNotEmpty()) effects.add(DeliveryEffect.RefreshRequired(owed))
        if (batch.conversationDeleted) {
            effects.add(
                DeliveryEffect.ConversationDeleted(session.token.conversation)
            )
        }
        if (batch.retiresBinding) effects.add(DeliveryEffect.ReattachRequired(enrollment))
        val next = copy(
            seen = newSeen,
            obligations = owed,
            live = session.copy(
                committedSequence = batch.highSequence,
                staged = null,
                retired = session.retired || batch.retiresBinding
            )
        )
        return DeliveryStep(
            next,
            EventCommit.Committed(batch.highSequence, batch.events.size),
            effects
        )
    }

    /** The consumer could not store the staged events: nothing advanced, so the same events are read again. */
    fun abortEvents(batchId: Long): DeliveryStep<Boolean> {
        val session = live
        val batch = session?.staged
        if (session == null || batch == null ||
            batch.batchId != batchId
        ) {
            return DeliveryStep(this, false)
        }
        return DeliveryStep(copy(live = session.copy(staged = null)), true)
    }

    // -- O5 copies -----------------------------------------------------------------------------------------

    fun planOutboxRead(
        correlation: CorrelationId,
        limit: Int? = null
    ): DeliveryStep<OutboxPlanOutcome<OutboxReadPlan>> {
        fun refused(reason: OutboxRefusal) = DeliveryStep(this, OutboxPlanOutcome.Refused(reason))
        if (outbox.read != null) return refused(OutboxRefusal.READ_OUTSTANDING)
        if (outbox.staged != null) return refused(OutboxRefusal.BATCH_OUTSTANDING)
        val call = try {
            RelayCalls.outboxRead(limit)
        } catch (_: IllegalArgumentException) {
            return refused(OutboxRefusal.INVALID_LIMIT)
        }
        val ref = OutboxRef(nextSerial, correlation)
        val next =
            copy(outbox = outbox.copy(read = ref), nextSerial = Math.addExact(nextSerial, 1L))
        return DeliveryStep(next, OutboxPlanOutcome.Planned(OutboxReadPlan(ref, call)))
    }

    fun onOutboxTimeout(ref: OutboxRef): DeliveryStep<OutboxStageOutcome> {
        if (outbox.read !=
            ref
        ) {
            return DeliveryStep(this, OutboxStageOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        return DeliveryStep(
            copy(outbox = outbox.copy(read = null)),
            OutboxStageOutcome.RetrySameRead(RelayErrorCode.TEMPORARILY_UNAVAILABLE)
        )
    }

    fun stageOutbox(
        ref: OutboxRef,
        outcome: RelayOutcome<OutboxPage>
    ): DeliveryStep<OutboxStageOutcome> {
        if (outbox.read !=
            ref
        ) {
            return DeliveryStep(this, OutboxStageOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        val cleared = outbox.copy(read = null)
        return when (outcome) {
            is RelayOutcome.Success -> stageOutboxPage(outbox, outcome.value)
            is RelayOutcome.Failed -> outboxError(cleared, outcome.error)
            is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid ->
                DeliveryStep(
                    copy(outbox = cleared),
                    OutboxStageOutcome.Failed(RelayErrorCode.UNKNOWN)
                )
        }
    }

    private fun outboxError(
        cleared: OutboxFlight,
        error: RelayError
    ): DeliveryStep<OutboxStageOutcome> {
        val code = error.code
        val next = copy(outbox = cleared)
        return when (error.action) {
            RelayErrorAction.STOP_AND_EVICT_SCOPE -> DeliveryStep(
                next,
                OutboxStageOutcome.Failed(code),
                listOf(
                    DeliveryEffect.EvictScopeContent(enrollment),
                    DeliveryEffect.InvalidateRuntimeAuthority(enrollment)
                )
            )
            RelayErrorAction.RETRY_SAME_REQUEST -> DeliveryStep(
                next,
                OutboxStageOutcome.RetrySameRead(code)
            )
            else -> DeliveryStep(next, OutboxStageOutcome.Failed(code))
        }
    }

    private fun stageOutboxPage(
        original: OutboxFlight,
        page: OutboxPage
    ): DeliveryStep<OutboxStageOutcome> {
        val cleared = original.copy(read = null)
        val fresh = ArrayList<OutboxItem>()
        val duplicates = ArrayList<OutboxItem>()
        val batchKeys = HashSet<DedupKey>()
        for (item in page.items) {
            val key = keyOf(item)
            if (key in seen || !batchKeys.add(key)) duplicates.add(item) else fresh.add(item)
        }
        if (seen.size + fresh.size > StateLimits.MAX_SEEN_KEYS) {
            return DeliveryStep(
                copy(outbox = original),
                OutboxStageOutcome.Backpressure(DeliveryLimit.SEEN_KEYS)
            )
        }
        val ackIds = page.items.map { it.deliveryId }
        val newAcks = ackIds.filter { it !in pendingAcks }.distinct()
        if (pendingAcks.size + newAcks.size > StateLimits.MAX_PENDING_ACKS) {
            return DeliveryStep(
                copy(outbox = original),
                OutboxStageOutcome.Backpressure(DeliveryLimit.PENDING_ACKS)
            )
        }
        val owedNow = ArrayList<RefreshObligation>()
        fun owe(cause: RefreshCause) {
            for (kind in RefreshKind.entries) owedNow.add(RefreshObligation(null, kind, cause))
        }
        val baseline = gapBaseline
        when {
            baseline == null -> owe(RefreshCause.OUTBOX_BASELINE_UNKNOWN)
            page.gapGeneration > baseline -> owe(RefreshCause.OUTBOX_GAP)
            page.gapGeneration < baseline -> owe(RefreshCause.OUTBOX_GAP_REGRESSED)
        }
        // A task whose NEWEST copy in this page is only an older snapshot needs a refresh to show the newer state.
        val newestAnywhere = LinkedHashMap<TaskId, OutboxItem.Change>()
        for (item in page.items) {
            val change = item as? OutboxItem.Change ?: continue
            newestAnywhere[taskOf(change).first] = change
        }
        if (newestAnywhere.values.any { it.payload.reference == OutboxReference.SUPERSEDED }) {
            owe(RefreshCause.SUPERSEDED_COPY)
        }
        val newestPerTask = LinkedHashMap<TaskId, OutboxItem.Change>()
        for (item in fresh) {
            val change = item as? OutboxItem.Change ?: continue
            newestPerTask[taskOf(change).first] = change
        }
        // `needs_input` in a copy means "wanted input then": only the newest, still-current one asks for a fetch.
        val fetchBeforePrompt = newestPerTask.filter { (_, change) ->
            taskOf(change).second == TaskState.NEEDS_INPUT &&
                change.payload.reference == OutboxReference.CURRENT
        }.keys
        val batch = StagedOutbox(
            nextSerial,
            Collections.unmodifiableList(fresh),
            Collections.unmodifiableList(duplicates),
            Collections.unmodifiableMap(newestPerTask),
            Collections.unmodifiableList(ArrayList(fetchBeforePrompt)),
            page.gapGeneration,
            Collections.unmodifiableList(owedNow)
        )
        val staged = cleared.copy(staged = Staged(batch, newAcks))
        return DeliveryStep(
            copy(outbox = staged, nextSerial = Math.addExact(nextSerial, 1L)),
            OutboxStageOutcome.Staged(batch)
        )
    }

    private fun keyOf(item: OutboxItem): DedupKey = when (item) {
        is OutboxItem.Final -> DedupKey.Opaque(item.conversationRef.value, item.eventRef.value)
        is OutboxItem.Change -> DedupKey.Canonical(item.conversationId, item.eventId)
    }

    private fun taskOf(change: OutboxItem.Change): Pair<TaskId, TaskState> =
        when (val payload = change.payload) {
            is OutboxPayload.TaskChange.Relay -> payload.task.taskId to payload.task.state
            is OutboxPayload.TaskChange.Existing -> payload.task.taskId to payload.task.state
        }

    /**
     * The consumer stored the staged copies (and, for duplicates, already had them): their keys become known, the gap
     * generation becomes the baseline, and their delivery ids are OWED to the server as an acknowledgement. Nothing is
     * acknowledged here.
     */
    fun commitOutbox(batchId: Long, now: ServerInstant): DeliveryStep<OutboxCommit> {
        val staged = outbox.staged
        if (staged == null || staged.batch.batchId != batchId) {
            return DeliveryStep(this, OutboxCommit.Refused(DeliveryIgnore.WRONG_BATCH))
        }
        if (seen.size + staged.batch.items.size > StateLimits.MAX_SEEN_KEYS) {
            return DeliveryStep(this, OutboxCommit.Backpressure(DeliveryLimit.SEEN_KEYS))
        }
        val newSeen = LinkedHashMap(seen)
        for (item in staged.batch.items) newSeen[keyOf(item)] = now.serverMs
        val acks = ArrayList(pendingAcks)
        acks.addAll(staged.newAcks)
        val owed = withObligations(staged.batch.refresh)
        val baseline = maxOf(gapBaseline ?: staged.batch.gapGeneration, staged.batch.gapGeneration)
        val effects = ArrayList<DeliveryEffect>()
        effects.add(DeliveryEffect.PersistDelivery)
        if (staged.batch.refresh.isNotEmpty()) effects.add(DeliveryEffect.RefreshRequired(owed))
        if (acks.isNotEmpty()) effects.add(DeliveryEffect.AcknowledgementsOwed(acks.size))
        val next = copy(
            seen = newSeen,
            obligations = owed,
            gapBaseline = baseline,
            pendingAcks = Collections.unmodifiableList(acks),
            outbox = outbox.copy(staged = null)
        )
        return DeliveryStep(
            next,
            OutboxCommit.Committed(staged.batch.items.size, acks.size),
            effects
        )
    }

    fun abortOutbox(batchId: Long): DeliveryStep<Boolean> {
        val staged = outbox.staged
        if (staged == null || staged.batch.batchId != batchId) return DeliveryStep(this, false)
        return DeliveryStep(copy(outbox = outbox.copy(staged = null)), true)
    }

    /** Plans one acknowledgement of up to 50 STORED deliveries. Repeating it after a lost reply is safe. */
    fun planAck(correlation: CorrelationId): DeliveryStep<OutboxPlanOutcome<AckPlan>> {
        if (outbox.ack !=
            null
        ) {
            return DeliveryStep(this, OutboxPlanOutcome.Refused(OutboxRefusal.ACK_OUTSTANDING))
        }
        if (pendingAcks.isEmpty()) {
            return DeliveryStep(
                this,
                OutboxPlanOutcome.Refused(OutboxRefusal.NOTHING_TO_ACK)
            )
        }
        val ids = ArrayList<DeliveryId>(minOf(pendingAcks.size, RelayLimits.ACK_IDS))
        for (id in pendingAcks) {
            if (ids.size >= RelayLimits.ACK_IDS) break
            ids.add(id)
        }
        val ref = AckRef(nextSerial, correlation, Collections.unmodifiableList(ids))
        val next = copy(outbox = outbox.copy(ack = ref), nextSerial = Math.addExact(nextSerial, 1L))
        return DeliveryStep(
            next,
            OutboxPlanOutcome.Planned(AckPlan(ref, RelayCalls.outboxAck(ids)))
        )
    }

    fun onAckReply(ref: AckRef, outcome: RelayOutcome<Acknowledged>): DeliveryStep<AckOutcome> {
        if (outbox.ack !=
            ref
        ) {
            return DeliveryStep(this, AckOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        val cleared = outbox.copy(ack = null)
        return when (outcome) {
            is RelayOutcome.Success -> {
                val done = outcome.value.acknowledged.filter { it in ref.ids }.toSet()
                val remaining = pendingAcks.filter { it !in done }
                DeliveryStep(
                    copy(pendingAcks = Collections.unmodifiableList(remaining), outbox = cleared),
                    AckOutcome.Confirmed(pendingAcks.size - remaining.size, remaining.size),
                    listOf(DeliveryEffect.PersistDelivery)
                )
            }
            is RelayOutcome.Failed -> {
                val effects = if (outcome.error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE) {
                    listOf(
                        DeliveryEffect.EvictScopeContent(enrollment),
                        DeliveryEffect.InvalidateRuntimeAuthority(enrollment)
                    )
                } else {
                    emptyList()
                }
                DeliveryStep(
                    copy(outbox = cleared),
                    AckOutcome.NotAcknowledged(outcome.error.code),
                    effects
                )
            }
            is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid ->
                DeliveryStep(copy(outbox = cleared), AckOutcome.NotAcknowledged(null))
        }
    }

    fun onAckTimeout(ref: AckRef): DeliveryStep<AckOutcome> {
        if (outbox.ack !=
            ref
        ) {
            return DeliveryStep(this, AckOutcome.Ignored(DeliveryIgnore.UNKNOWN_CALL))
        }
        return DeliveryStep(
            copy(outbox = outbox.copy(ack = null)),
            AckOutcome.NotAcknowledged(null)
        )
    }

    // -- obligations and housekeeping ----------------------------------------------------------------------

    /**
     * The consumer finished refreshing [kinds] for [conversation] (null: the whole enrollment). Clears exactly the
     * matching obligations.
     */
    fun refreshed(conversation: ConversationScope?, kinds: Set<RefreshKind>): DeliveryStep<Int> {
        val remaining = obligations.filter {
            it.kind !in kinds ||
                (conversation != null && it.conversation != conversation)
        }
        val cleared = obligations.size - remaining.size
        if (cleared == 0) return DeliveryStep(this, 0)
        return DeliveryStep(
            copy(obligations = Collections.unmodifiableList(remaining)),
            cleared,
            listOf(DeliveryEffect.PersistDelivery)
        )
    }

    /**
     * `permission_denied`: forgets everything cached for this enrollment (identities, owed refreshes, the O5 baseline,
     * unacknowledged ids, the session and anything staged). The server still holds the copies until they expire and
     * they are NOT acknowledged by this; a later grant starts from an unknown baseline, i.e. with a refresh.
     */
    fun evictScopeContent(): DeliveryStep<Int> {
        val forgotten = seen.size + pendingAcks.size + obligations.size
        val next = copy(
            seen = emptyMap(),
            obligations = emptyList(),
            gapBaseline = null,
            pendingAcks = emptyList(),
            live = null,
            outbox = OutboxFlight(null, null, null)
        )
        return DeliveryStep(next, forgotten, listOf(DeliveryEffect.PersistDelivery))
    }

    /**
     * Explicitly forgets identities committed before [serverMs]. This RELAXES duplicate detection for them: only do it
     * for identities the server can no longer replay (an O5 copy expires after 24 hours; a live event is bound to a
     * binding that is gone).
     */
    fun retireOlderThan(serverMs: Long): DeliveryStep<Int> {
        val kept = LinkedHashMap<DedupKey, Long>()
        for ((key, at) in seen) if (at >= serverMs) kept[key] = at
        val dropped = seen.size - kept.size
        if (dropped == 0) return DeliveryStep(this, 0)
        return DeliveryStep(copy(seen = kept), dropped, listOf(DeliveryEffect.PersistDelivery))
    }

    private fun withObligations(added: List<RefreshObligation>): List<RefreshObligation> {
        val merged = ArrayList<RefreshObligation>(obligations)
        for (item in added) {
            val index = merged.indexOfFirst {
                it.conversation == item.conversation &&
                    it.kind == item.kind
            }
            if (index >= 0) merged[index] = item else merged.add(item)
        }
        return Collections.unmodifiableList(collapse(merged))
    }

    /** More obligations than the local limit are WIDENED to enrollment-wide ones, never dropped. */
    private fun collapse(list: ArrayList<RefreshObligation>): List<RefreshObligation> {
        if (list.size <= MAX_OBLIGATIONS) return list
        val out = ArrayList<RefreshObligation>()
        for (kind in RefreshKind.entries) {
            val cause = list.last { it.kind == kind }.cause
            out.add(RefreshObligation(null, kind, cause))
        }
        return out
    }

    // -- snapshot ------------------------------------------------------------------------------------------

    /**
     * The durable part: the identities committed so far, the refreshes still owed, the O5 baseline (null if none was
     * ever committed) and the stored-but-unacknowledged delivery ids. The live session, its cursor and anything staged
     * are transient and are not saved.
     */
    fun snapshot(): DeliverySnapshot = DeliverySnapshot(
        enrollment.toSnapshot(),
        gapBaseline,
        seen.map { (key, at) ->
            when (key) {
                is DedupKey.Canonical ->
                    SeenKeySnapshot("canonical", key.conversationId.value, key.eventId.value, at)
                is DedupKey.Opaque -> SeenKeySnapshot(
                    "opaque",
                    key.conversationRef,
                    key.eventRef,
                    at
                )
            }
        },
        obligations.map {
            ObligationSnapshot(it.conversation?.conversationId?.value, it.kind.name, it.cause.name)
        },
        pendingAcks.map { it.value }
    )

    override fun toString(): String =
        "EnrollmentDelivery(seen=${seen.size}, owed=${obligations.size}, acks=${pendingAcks.size})"

    // -- internals ----------------------------------------------------------------------------------------

    internal class Live(
        val token: BindingToken,
        val committedSequence: Long,
        val outstanding: ReadRef?,
        val staged: StagedEvents?,
        val retired: Boolean
    ) {
        fun copy(
            committedSequence: Long = this.committedSequence,
            outstanding: ReadRef? = this.outstanding,
            staged: StagedEvents? = this.staged,
            retired: Boolean = this.retired
        ) = Live(token, committedSequence, outstanding, staged, retired)
    }

    internal class Staged(val batch: StagedOutbox, val newAcks: List<DeliveryId>)

    internal class OutboxFlight(val read: OutboxRef?, val staged: Staged?, val ack: AckRef?) {
        fun copy(
            read: OutboxRef? = this.read,
            staged: Staged? = this.staged,
            ack: AckRef? = this.ack
        ) = OutboxFlight(read, staged, ack)
    }

    private fun copy(
        seen: Map<DedupKey, Long> = this.seen,
        obligations: List<RefreshObligation> = this.obligations,
        gapBaseline: Long? = this.gapBaseline,
        pendingAcks: List<DeliveryId> = this.pendingAcks,
        live: Live? = this.live,
        outbox: OutboxFlight = this.outbox,
        nextSerial: Long = this.nextSerial
    ) = EnrollmentDelivery(
        enrollment,
        seen,
        obligations,
        gapBaseline,
        pendingAcks,
        live,
        outbox,
        nextSerial
    )

    companion object {
        private const val MAX_OBLIGATIONS = 128

        /**
         * Restores the delivery state of one enrollment. Without a session, a cursor or a staged batch; the saved O5
         * baseline is restored as it was (never invented: null stays null, and an unknown baseline is a refresh).
         */
        internal fun restore(saved: DeliverySnapshot): RestoreResult<EnrollmentDelivery> {
            fun refused(reason: RestoreRefusal, path: String) = RestoreResult.Refused(reason, path)
            val enrollment =
                saved.scope.toScope() ?: return refused(RestoreRefusal.INVALID_SCOPE, "scope")
            if (saved.seen.size >
                StateLimits.MAX_SEEN_KEYS
            ) {
                return refused(RestoreRefusal.TOO_MANY, "seen")
            }
            if (saved.obligations.size >
                MAX_OBLIGATIONS
            ) {
                return refused(RestoreRefusal.TOO_MANY, "obligations")
            }
            if (saved.pendingAcks.size >
                StateLimits.MAX_PENDING_ACKS
            ) {
                return refused(RestoreRefusal.TOO_MANY, "pendingAcks")
            }
            if ((saved.gapBaseline ?: 0) <
                0
            ) {
                return refused(RestoreRefusal.INVALID_FIELD, "gapBaseline")
            }
            val seen = LinkedHashMap<DedupKey, Long>()
            for ((index, item) in saved.seen.withIndex()) {
                val path = "seen[$index]"
                if (item.committedAtMs <
                    0
                ) {
                    return refused(RestoreRefusal.INVALID_FIELD, "$path.committedAtMs")
                }
                val key: DedupKey = when (item.kind) {
                    "canonical" -> {
                        val conversation = ConversationId.of(item.conversation)
                            ?: return refused(RestoreRefusal.INVALID_ID, "$path.conversation")
                        val event =
                            EventId.of(item.event)
                                ?: return refused(RestoreRefusal.INVALID_ID, "$path.event")
                        DedupKey.Canonical(conversation, event)
                    }
                    "opaque" -> {
                        val conversation = OpaqueConversationRef.of(item.conversation)
                            ?: return refused(RestoreRefusal.INVALID_ID, "$path.conversation")
                        val event = OpaqueEventRef.of(item.event)
                            ?: return refused(RestoreRefusal.INVALID_ID, "$path.event")
                        DedupKey.Opaque(conversation.value, event.value)
                    }
                    else -> return refused(RestoreRefusal.INVALID_FIELD, "$path.kind")
                }
                if (seen.put(key, item.committedAtMs) !=
                    null
                ) {
                    return refused(RestoreRefusal.DUPLICATE, path)
                }
            }
            val obligations = ArrayList<RefreshObligation>()
            for ((index, item) in saved.obligations.withIndex()) {
                val path = "obligations[$index]"
                val kind = RefreshKind.entries.firstOrNull { it.name == item.kind }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.kind")
                val cause = RefreshCause.entries.firstOrNull { it.name == item.cause }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.cause")
                val conversation = item.conversationId?.let { text ->
                    ConversationScope(
                        enrollment,
                        ConversationId.of(text)
                            ?: return refused(RestoreRefusal.INVALID_ID, "$path.conversationId")
                    )
                }
                if (obligations.any { it.conversation == conversation && it.kind == kind }) {
                    return refused(RestoreRefusal.DUPLICATE, path)
                }
                obligations.add(RefreshObligation(conversation, kind, cause))
            }
            val acks = ArrayList<DeliveryId>()
            for ((index, text) in saved.pendingAcks.withIndex()) {
                val id =
                    DeliveryId.of(text)
                        ?: return refused(RestoreRefusal.INVALID_ID, "pendingAcks[$index]")
                if (id in acks) return refused(RestoreRefusal.DUPLICATE, "pendingAcks[$index]")
                acks.add(id)
            }
            val delivery = EnrollmentDelivery(
                enrollment,
                seen,
                obligations,
                saved.gapBaseline,
                acks,
                null,
                OutboxFlight(null, null, null),
                1
            )
            return RestoreResult.Restored(delivery, emptyList())
        }

        fun begin(enrollment: EnrollmentScope): EnrollmentDelivery = EnrollmentDelivery(
            enrollment,
            emptyMap(),
            emptyList(),
            null,
            emptyList(),
            null,
            OutboxFlight(null, null, null),
            1
        )
    }
}
