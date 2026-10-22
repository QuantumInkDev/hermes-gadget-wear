package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.TaskOutcome
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The durable identity of one user turn: its conversation under its enrollment plus the client-generated request id.
 * Equal textual ids under another endpoint, profile, enrollment or conversation are a different key.
 */
data class TurnKey(val conversation: ConversationScope, val requestId: RequestId) {
    override fun toString(): String = "TurnKey(request=${requestId.value}, scope=redacted)"
}

/**
 * SHA-256 over the immutable facts of a turn: conversation id, request id, deadline and text. Two prepared turns with
 * the same request id must have the same fingerprint, so changing text or deadline under an existing id is detected
 * rather than "fixed up". Only a digest is printed.
 */
class TurnFingerprint private constructor(val hex: String) {
    override fun equals(other: Any?): Boolean = other is TurnFingerprint && other.hex == hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "TurnFingerprint(${hex.take(12)}...)"

    companion object {
        private const val DOMAIN = "hermes-gadget.relay.turn.v1"

        fun of(
            conversationId: ConversationId,
            requestId: RequestId,
            expiresAtMs: Long,
            text: String
        ): TurnFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(bytes: ByteArray) {
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            field(DOMAIN.toByteArray(Charsets.UTF_8))
            field(conversationId.value.toByteArray(Charsets.UTF_8))
            field(requestId.value.toByteArray(Charsets.UTF_8))
            field(ByteBuffer.allocate(8).putLong(expiresAtMs).array())
            field(text.toByteArray(Charsets.UTF_8))
            return TurnFingerprint(
                digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
            )
        }

        /** A fingerprint read back from a snapshot: 64 lowercase hex digits or nothing. */
        internal fun parse(hex: String): TurnFingerprint? =
            hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                ?.let(::TurnFingerprint)
    }
}

/** Why a turn may have reached the server without us knowing. Uncertainty always demands attach, then receipt. */
enum class UncertainReason {
    TIMEOUT,
    TRANSPORT_LOST,
    STALE_AUTHORITY,

    /** `busy` / `rate_limited` / `temporarily_unavailable`: not a revocation, not a refusal. */
    TRANSIENT_ERROR,

    /** An uncorrelated or invalid frame, or a receipt that does not belong to this turn. */
    LINK_FAILURE,
    PROCESS_LOSS,

    /** An error whose meaning does not settle the turn (see [TurnHold]). */
    UNEXPECTED_ERROR,

    /**
     * The server answered `expired`. That shared code also answers a NEW intent whose UUID timestamp or deadline lies
     * too far in the FUTURE of the server clock, and the receipt path reads a different clock sample than a new
     * admission does, so it never proves that THIS immutable request can not be admitted. It is an observation: the
     * turn stays unresolved and reconcilable (fresh attach, then receipt), never closed.
     */
    EXPIRED_REPORTED
}

/**
 * How a turn can end without a receipt. There is deliberately NO "not admitted" ending: neither a local deadline
 * estimate, nor `not_found`, nor the server's `expired` code proves that a possibly sent intent can never be admitted
 * (see [UncertainReason.EXPIRED_REPORTED]); such a turn stays unresolved and reconcilable until a receipt settles it
 * or the user explicitly stops tracking it.
 */
sealed interface TurnClosure {
    /** The server refused the call before any effect (`invalid_request` / `unsupported`). Needs the user. */
    data class Refused(val code: RelayErrorCode) : TurnClosure {
        override fun toString(): String = "Refused(${code.wire})"
    }
}

/** Why automatic progress of a turn stopped and waits for a deliberate user decision. */
enum class TurnHold {
    /** An error that neither proves admission nor non-admission (conflict, unknown code, ...). */
    UNEXPECTED_ERROR
}

enum class ScopeStatus {
    ACTIVE,

    /** `permission_denied`: sending and lookups stop; cached content for the scope is to be evicted. */
    STOPPED,

    /** `profile_changed`: the profile binding is invalid; nothing is sent until the user has reviewed. */
    NEEDS_REVIEW
}

/** The durable decision state of one enrollment scope (only non-default state is recorded). */
class ScopeRecord internal constructor(val status: ScopeStatus, val contentEvictionOwed: Boolean) {
    override fun equals(other: Any?): Boolean = other is ScopeRecord && other.status == status &&
        other.contentEvictionOwed == contentEvictionOwed

    override fun hashCode(): Int = 31 * status.hashCode() + contentEvictionOwed.hashCode()

    override fun toString(): String = "ScopeRecord($status, evictionOwed=$contentEvictionOwed)"
}

/**
 * What a receipt proved about one admission. Only the exact intent/scope whose receipt it was may carry it. The
 * outcome is the server's spelling: an unknown spelling stays unknown and is never read as success.
 */
class AdmissionFacts internal constructor(
    val admissionId: AdmissionId,
    val authorityEpoch: Long,
    val status: ReceiptStatus,
    /** Null for a queued turn (and a queued turn cancelled before it ran); at least 1 once started. */
    val executionGeneration: Long?,
    val outcome: String?,
    /**
     * Only for a terminal `cancelled` receipt WITHOUT a generation that replaced a STARTED observation: the generation
     * that start was shown with. The core projects a queued admission handed to a running claim as STARTED(that claim's
     * generation), hides the transfer, and can requeue (generation NULL) and then cancel it; the earlier start was a
     * projection of the claim it was transferred to, kept here so that evidence is not erased or mistaken for a run of
     * this admission. Null in every other case.
     */
    val priorStartedGeneration: Long? = null
) {
    val knownOutcome: TaskOutcome? get() = TaskOutcome.entries.firstOrNull { it.wire == outcome }

    override fun equals(other: Any?): Boolean =
        other is AdmissionFacts && other.admissionId == admissionId &&
            other.authorityEpoch == authorityEpoch && other.status == status &&
            other.executionGeneration == executionGeneration && other.outcome == outcome &&
            other.priorStartedGeneration == priorStartedGeneration

    override fun hashCode(): Int = admissionId.hashCode() * 31 + status.hashCode()

    override fun toString(): String =
        "AdmissionFacts(admission=${admissionId.value}, status=${status.wire}, redacted)"

    internal companion object {
        fun of(receipt: Receipt): AdmissionFacts = AdmissionFacts(
            receipt.admissionId,
            receipt.authorityEpoch,
            receipt.status,
            receipt.executionGeneration,
            receipt.outcome
        )
    }
}

enum class TurnPhase {
    /** Saved, never dispatched (as far as the ledger was told; see the persist-before-send contract). */
    PREPARED,

    /** A submit call is outstanding: it may or may not reach the server. */
    SUBMITTING,

    /** May have reached the server; attach again, then look up the receipt FIRST. */
    UNCERTAIN,

    /** A receipt lookup is outstanding. */
    LOOKING_UP,

    /**
     * A lookup observed `not_found`. That is an observation, not a closure, at any time: the turn stays unresolved. While
     * the local estimate is before the deadline the same id may be submitted again; afterwards the estimate refuses a
     * resubmit, but only a receipt resolves the turn (the server's `expired` is an observation too, see
     * [UncertainReason.EXPIRED_REPORTED]).
     */
    ABSENT_SO_FAR,
    QUEUED,
    STARTED,

    /** The server cannot tell: stays unknown, never success. */
    ADMITTED_UNKNOWN,
    TERMINAL,
    REFUSED
}

/** The honest user-facing status of a turn. Only [FINISHED_COMPLETED] is a success. */
enum class TurnStatus {
    NOT_SENT,
    SENDING,
    UNCERTAIN,
    CHECKING,
    QUEUED,
    RUNNING,
    UNKNOWN_ADMITTED,
    FINISHED_COMPLETED,

    /** cancelled / interrupted / steered. */
    FINISHED_OTHER,

    /** failed / rejected. */
    FAILED,

    /** Terminal with an outcome this build does not know: it stays unknown. */
    TERMINAL_UNKNOWN_OUTCOME,
    REFUSED
}

enum class CallKind { SUBMIT, LOOKUP }

/** Identifies ONE outstanding call: a reply is applied only when it names exactly this serial and correlation. */
class CallRef internal constructor(
    val serial: Long,
    val correlation: CorrelationId,
    val kind: CallKind,
    val bindingSerial: Long,
    val scope: EnrollmentScope
) {
    override fun equals(other: Any?): Boolean = other is CallRef && other.serial == serial

    override fun hashCode(): Int = serial.hashCode()

    override fun toString(): String = "CallRef(serial=$serial, kind=$kind)"
}

/** The one thing a plan hands the transport: a typed call plus the reference its reply must name. */
class TurnDispatch internal constructor(
    val key: TurnKey,
    val ref: CallRef,
    val call: RelayCall<Receipt>
) {
    override fun toString(): String = "TurnDispatch(${ref.kind}, ${call.method.wire}, redacted)"
}

/**
 * One turn the ledger keeps. Everything but the transient bookkeeping is the durable state of the turn. The text,
 * deadline and request id never change once prepared; [toString] prints no text.
 */
class TurnEntry internal constructor(
    val key: TurnKey,
    val expiresAtMs: Long,
    val preparedAtMs: Long,
    val fingerprint: TurnFingerprint,
    /** The user's text; null once content was evicted (the identity and uncertainty then remain, honestly). */
    val text: String?,
    /** A submit was dispatched at least once (or may have been). False only before the first dispatch. */
    val sent: Boolean,
    val facts: AdmissionFacts?,
    val closure: TurnClosure?,
    internal val absentSoFar: Boolean,
    val uncertainty: UncertainReason?,
    val hold: TurnHold?,
    val lastErrorCode: RelayErrorCode?,
    val anomalies: Int,
    internal val freshAfterSerial: Long,
    internal val outstanding: CallRef?
) {
    val phase: TurnPhase
        get() = when {
            closure is TurnClosure.Refused -> TurnPhase.REFUSED
            outstanding?.kind == CallKind.SUBMIT -> TurnPhase.SUBMITTING
            outstanding?.kind == CallKind.LOOKUP -> TurnPhase.LOOKING_UP
            facts != null -> when (facts.status) {
                ReceiptStatus.QUEUED -> TurnPhase.QUEUED
                ReceiptStatus.STARTED -> TurnPhase.STARTED
                ReceiptStatus.UNKNOWN -> TurnPhase.ADMITTED_UNKNOWN
                ReceiptStatus.TERMINAL -> TurnPhase.TERMINAL
            }
            !sent -> TurnPhase.PREPARED
            absentSoFar -> TurnPhase.ABSENT_SO_FAR
            else -> TurnPhase.UNCERTAIN
        }

    /** The status to show. A pending or unknown turn never reads as success. */
    val status: TurnStatus
        get() = when (phase) {
            TurnPhase.PREPARED -> TurnStatus.NOT_SENT
            TurnPhase.SUBMITTING -> TurnStatus.SENDING
            TurnPhase.UNCERTAIN, TurnPhase.ABSENT_SO_FAR -> TurnStatus.UNCERTAIN
            TurnPhase.LOOKING_UP -> TurnStatus.CHECKING
            TurnPhase.QUEUED -> TurnStatus.QUEUED
            TurnPhase.STARTED -> TurnStatus.RUNNING
            TurnPhase.ADMITTED_UNKNOWN -> TurnStatus.UNKNOWN_ADMITTED
            TurnPhase.TERMINAL -> when (facts?.knownOutcome) {
                TaskOutcome.COMPLETED -> TurnStatus.FINISHED_COMPLETED
                TaskOutcome.CANCELLED, TaskOutcome.INTERRUPTED, TaskOutcome.STEERED ->
                    TurnStatus.FINISHED_OTHER
                TaskOutcome.FAILED, TaskOutcome.REJECTED -> TurnStatus.FAILED
                null -> TurnStatus.TERMINAL_UNKNOWN_OUTCOME
            }
            TurnPhase.REFUSED -> TurnStatus.REFUSED
        }

    /**
     * True when the LOCAL estimate of server time has reached the deadline. It refuses a resubmit; it is not evidence
     * of non-admission or of completion, and not of what the server holds or can still admit (the estimate can lead).
     */
    fun deadlinePassed(now: ServerInstant): Boolean = now.serverMs >= expiresAtMs

    /** Whether the server may still be holding or executing this turn: local expiry never changes the answer. */
    val mayHaveReachedServer: Boolean get() = sent && closure == null

    internal fun update(
        text: String? = this.text,
        sent: Boolean = this.sent,
        facts: AdmissionFacts? = this.facts,
        closure: TurnClosure? = this.closure,
        absentSoFar: Boolean = this.absentSoFar,
        uncertainty: UncertainReason? = this.uncertainty,
        hold: TurnHold? = this.hold,
        lastErrorCode: RelayErrorCode? = this.lastErrorCode,
        anomalies: Int = this.anomalies,
        freshAfterSerial: Long = this.freshAfterSerial,
        outstanding: CallRef? = this.outstanding
    ): TurnEntry = TurnEntry(
        key,
        expiresAtMs,
        preparedAtMs,
        fingerprint,
        text,
        sent,
        facts,
        closure,
        absentSoFar,
        uncertainty,
        hold,
        lastErrorCode,
        anomalies,
        freshAfterSerial,
        outstanding
    )

    override fun toString(): String =
        "TurnEntry(request=${key.requestId.value}, phase=$phase, redacted)"
}

/** What the state layer asks its consumer to DO next. None of these is a send; sending only comes from a plan. */
sealed interface TurnEffect {
    /** The ledger changed: persist the snapshot (the consumer's durable-write ordering is not proven here). */
    data object PersistLedger : TurnEffect

    /** The snapshot must be DURABLE before the planned frame is written (persist-before-send). */
    data class PersistBeforeSend(val key: TurnKey) : TurnEffect

    /** Attach again (a new binding), then refresh history and the task snapshot, before anything else. */
    data class ReattachRequired(val scope: EnrollmentScope) : TurnEffect

    data class RefreshHistoryAndTasks(val conversation: ConversationScope) : TurnEffect

    /** `permission_denied`: evict cached content (turn text included) for the scope. */
    data class EvictScopeContent(val scope: EnrollmentScope) : TurnEffect

    /** Runtime authority (bindings, prompts, controls) for the scope is no longer valid. */
    data class InvalidateRuntimeAuthority(val scope: EnrollmentScope) : TurnEffect

    /** `profile_changed`: stop and ask the user before anything is sent for the scope. */
    data class RequireUserReview(val scope: EnrollmentScope) : TurnEffect

    /** Show the user an unexpected server answer; it neither succeeded nor failed the turn. */
    data class SurfaceAnomaly(val key: TurnKey, val kind: AnomalyKind) : TurnEffect
}

enum class AnomalyKind {
    /** A receipt for another request id or conversation than the call's turn. */
    SCOPE_MISMATCH,
    ADMISSION_MISMATCH,
    AUTHORITY_EPOCH_MISMATCH,
    STATUS_REGRESSION,
    GENERATION_REGRESSION,
    OUTCOME_CONFLICT,

    /** A receipt whose status, outcome and generation cannot go together. */
    INCONSISTENT_RECEIPT,

    /** A lookup found nothing (`not_found`) or answered `expired` for a turn that is already known to be admitted. */
    ABSENT_AFTER_ADMISSION
}

/** A pure transition: the next ledger, what happened, and what the consumer is asked to do. */
class LedgerStep<out R>(val ledger: TurnLedger, val result: R, val effects: List<TurnEffect>) {
    override fun toString(): String = "LedgerStep($result, effects=${effects.size})"
}
