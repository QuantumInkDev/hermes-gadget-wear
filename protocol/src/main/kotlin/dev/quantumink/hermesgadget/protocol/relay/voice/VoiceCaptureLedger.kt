package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.BindingKind
import dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
import dev.quantumink.hermesgadget.protocol.relay.state.ConversationScope
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.MintRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.MintResult
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreNotice
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeStatus
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnKey
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.state.aggregateTooMany
import dev.quantumink.hermesgadget.protocol.relay.state.monotonicRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.toScope
import dev.quantumink.hermesgadget.protocol.relay.state.toSnapshot
import java.util.Collections

/** The durable identity of one capture: its conversation under its enrollment plus the client-minted capture id. */
data class CaptureKey(val conversation: ConversationScope, val captureId: CaptureId) {
    override fun toString(): String = "CaptureKey(capture=${captureId.value}, scope=redacted)"
}

/** The client states of design §8.4. [terminal] ones are retained up to 24 h for truthful history of billing unknowns, then removed. */
enum class CaptureLifecycle(val terminal: Boolean) {
    /** `capture.start` may be sent; nothing is billed. Audio lives in memory only: a restored `RECORDING` record has none. */
    RECORDING(false),

    /** `capture.finish` may be in flight; billing is possible. */
    FINISHING(false),

    /** Receipt reads allowed. */
    AWAITING_RESULT(false),

    /** `linkedRequest` is set; the Task2 ledger owns the turn. */
    ADMITTING(false),

    /** The text was given to the user as a draft; it never auto-submits. */
    DRAFT_OFFERED(false),
    CANCELLED(true),
    LOST(true),
    ENDED(true)
}

/** Why an [CaptureLifecycle.ENDED] capture ended (truthful history; nothing here is ever re-run). */
enum class CaptureEnd {
    /** The server answered `not_found` to a result read: nothing was sealed or billed ("Recording lost, not sent"). */
    NOT_SEALED,
    PRE_SEAL_FAILED,
    FAILED,
    EMPTY,
    TRANSCRIPT_TOO_LONG,
    UNKNOWN_OUTCOME,
    DISCARDED,
    EXPIRED,
    REVOKED,
    TURN_SETTLED,

    /** The linked turn is no longer in the Task2 ledger (settled and released, or abandoned by the user). */
    TURN_GONE,
    DRAFT_RESOLVED
}

enum class CaptureBilling { NOT_BILLED, MAY_HAVE_BEEN_BILLED }

/** One capture record as the ledger keeps it. Never holds audio, a stream, a socket, a binding, a waveform, a playback flag or text. */
class CaptureEntry internal constructor(
    val key: CaptureKey,
    /** The server-time estimate at press; equals the UUIDv7 stamp of the capture id. */
    val createdAtMs: Long,
    val lifecycle: CaptureLifecycle,
    /** The Task2 turn this capture was linked to by the one atomic prepare+link; once set it is never prepared again. */
    val linkedRequest: RequestId?,
    /** The last `provider` evidence the server reported (merged, never downgraded), or null before any answer. */
    val provider: VoiceProvider?,
    val end: CaptureEnd?,
    val failure: CaptureFailure?,
    val terminalAtMs: Long?
) {
    /** Billing truth: the server's evidence when there is any, else whether `finish` may have left. */
    val billing: CaptureBilling
        get() = when {
            provider != null ->
                if (provider.mayHaveBeenBilled) {
                    CaptureBilling.MAY_HAVE_BEEN_BILLED
                } else {
                    CaptureBilling.NOT_BILLED
                }
            lifecycle == CaptureLifecycle.RECORDING || lifecycle == CaptureLifecycle.CANCELLED ||
                lifecycle == CaptureLifecycle.LOST ->
                CaptureBilling.NOT_BILLED
            else -> CaptureBilling.MAY_HAVE_BEEN_BILLED
        }

    internal fun update(
        lifecycle: CaptureLifecycle = this.lifecycle,
        linkedRequest: RequestId? = this.linkedRequest,
        provider: VoiceProvider? = this.provider,
        end: CaptureEnd? = this.end,
        failure: CaptureFailure? = this.failure,
        terminalAtMs: Long? = this.terminalAtMs
    ) = CaptureEntry(
        key,
        createdAtMs,
        lifecycle,
        linkedRequest,
        provider,
        end,
        failure,
        terminalAtMs
    )

    override fun toString(): String =
        "CaptureEntry(capture=${key.captureId.value}, $lifecycle, billing=$billing)"
}

/**
 * Transient intent of ONE capture (design §5.2 C4): the binding serial at press and, once released, the server time of the release. It is process state
 * only, absent from every snapshot and from a restore, and it is the only thing that lets a transcript become a turn automatically.
 */
class CaptureIntent internal constructor(val bindingSerial: Long, val releasedAtMs: Long?) {
    override fun toString(): String =
        "CaptureIntent(serial=$bindingSerial, released=${releasedAtMs != null})"
}

/** What a transition asks its consumer to DO. None of these is a send; sending only comes from a plan, after the obligation is met. */
sealed interface CaptureEffect {
    /** The capture section (and, in an admission, the turn ledger of the SAME write) changed: persist the aggregate snapshot. */
    data object PersistAggregate : CaptureEffect

    /** The snapshot with this `recording` record must be DURABLE before `capture.start` is written. */
    data class PersistBeforeStart(val key: CaptureKey) : CaptureEffect

    /** The snapshot with this `finishing` record must be DURABLE before `capture.finish` is written. */
    data class PersistBeforeFinish(val key: CaptureKey) : CaptureEffect

    /** After the snapshot is durable the client MAY send `capture.discard` to erase the server's copy of the transcript early. */
    data class MayDiscardServerCopy(val key: CaptureKey) : CaptureEffect

    /** The recording was cancelled locally: best-effort `capture.discard` drops the audio the server may hold; nothing was sealed or billed. */
    data class SendDiscard(val key: CaptureKey) : CaptureEffect
}

/** A pure transition of the capture ledger: the next ledger, what happened, and the obligations. */
class CaptureStep<out R>(
    val ledger: VoiceCaptureLedger,
    val result: R,
    val effects: List<CaptureEffect>
) {
    override fun toString(): String = "CaptureStep($result, effects=${effects.size})"
}

enum class BeginRefusal {
    CLOCK_ROLLBACK,
    SCOPE_STOPPED,
    SCOPE_NEEDS_REVIEW,
    BINDING_KIND,
    BINDING_NOT_CURRENT,
    MINT_FAILED,
    ID_COLLISION
}

/** A local resource limit of the capture ledger (policy). Reaching one refuses the new capture; nothing is dropped or evicted. */
enum class CaptureLimit { PER_ENROLLMENT, TOTAL }

sealed interface BeginOutcome {
    class Began(val key: CaptureKey) : BeginOutcome {
        override fun toString(): String = "Began($key)"
    }

    class Refused(val reason: BeginRefusal, val mint: MintRefusal? = null) : BeginOutcome {
        override fun toString(): String = "Refused($reason${mint?.let { ", $it" } ?: ""})"
    }

    class Backpressure(val limit: CaptureLimit) : BeginOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }

    /** The aggregate snapshot would exceed a component limit: nothing was created. The user sees "Can't record until pending items are reviewed". */
    class AggregateTooMany(val path: String, val limit: Int) : BeginOutcome {
        override fun toString(): String = "AggregateTooMany($path, limit=$limit)"
    }
}

enum class PlanRefusal {
    UNKNOWN_CAPTURE,
    WRONG_LIFECYCLE,

    /** The record exists but its audio does not: a restored or invalidated recording can only be cancelled. */
    AUDIO_NOT_LIVE,
    BINDING_KIND,
    BINDING_NOT_CURRENT,
    BINDING_WRONG_SCOPE,
    SCOPE_STOPPED,
    SCOPE_NEEDS_REVIEW,
    CLOCK_ROLLBACK,
    NO_AUDIO,
    TOO_SHORT,

    /** The combined snapshot cannot be built: send `capture.discard`, not `capture.finish` (design §8.2 step 2). */
    SNAPSHOT_TOO_MANY
}

sealed interface CapturePlan<out R : Any> {
    class Planned<R : Any>(val call: RelayCall<R>) : CapturePlan<R> {
        override fun toString(): String = "Planned(${call.method.wire})"
    }

    class Refused(val reason: PlanRefusal, val path: String? = null, val limit: Int? = null) :
        CapturePlan<Nothing> {
        override fun toString(): String = "Refused($reason${path?.let {
            ", $it limit=$limit"
        } ?: ""})"
    }
}

enum class IgnoredResult { UNKNOWN_CAPTURE, FOREIGN_RESULT, NOT_AWAITING, SCOPE_FROZEN }

sealed interface ResultOutcome {
    /** Queued, preparing or transcribing: the capture waits (receipt reads allowed). */
    class Progress(val state: CaptureState) : ResultOutcome {
        override fun toString(): String = "Progress($state)"
    }

    /** A transcript is in hand and nothing was decided: call [VoiceAdmission.admit] (auto path) or [VoiceCaptureLedger.offerDraft]. */
    data object TranscriptReady : ResultOutcome

    class Ended(val reason: CaptureEnd, val failure: CaptureFailure?) : ResultOutcome {
        override fun toString(): String = "Ended($reason${failure?.let { ", ${it.wire}" } ?: ""})"
    }

    class Ignored(val why: IgnoredResult) : ResultOutcome {
        override fun toString(): String = "Ignored($why)"
    }
}

/** A transcript offered as an unsent, editable draft. It lives in memory only; it is never persisted and never printed. */
class DraftTranscript internal constructor(val key: CaptureKey, val text: String) {
    override fun toString(): String = "DraftTranscript(capture=${key.captureId.value}, redacted)"
}

enum class DraftRefusal {
    UNKNOWN_CAPTURE,
    MISMATCH,
    NOT_TRANSCRIBED,
    WRONG_LIFECYCLE,
    ALREADY_LINKED,
    SCOPE_FROZEN
}

sealed interface DraftOutcome {
    class Offered(val draft: DraftTranscript) : DraftOutcome {
        override fun toString(): String = "Offered($draft)"
    }

    class Refused(val reason: DraftRefusal) : DraftOutcome {
        override fun toString(): String = "Refused($reason)"
    }
}

/** What a failed voice call asks next. The ledger already withdrew the transient intent when the binding or authority is no longer valid. */
class ErrorAdvice(val action: RelayErrorAction, val ended: CaptureEnd?) {
    override fun toString(): String = "ErrorAdvice($action, ended=$ended)"
}

/**
 * The pure capture ledger (design §8.4; the capture section of the aggregate snapshot).
 *
 * Contract with the future controller (none of it is proven here):
 *  - Every transition is pure and returns a new ledger plus [CaptureEffect] obligations. [CaptureEffect.PersistBeforeStart] and
 *    [CaptureEffect.PersistBeforeFinish] mean the snapshot must be DURABLE before the frame is written; nothing is persisted or sent here.
 *  - A capture id is minted only by [begin] and is distinct from every turn request id. A collision is a refusal, never a re-mint.
 *  - A transcript becomes a turn only through [VoiceAdmission.admit], which prepares the Task2 turn and links the capture in ONE returned transition,
 *    and only while the transient [CaptureIntent] still holds. A linked capture never prepares again, also not after a restore. A draft never submits.
 *  - A restore drops every transient intent: no transcript that arrives afterwards can be auto-submitted, and a restored `RECORDING` record has no audio.
 *  - Resource limits ([VoiceLimits.CAPTURES_PER_ENROLLMENT], [VoiceLimits.CAPTURES_TOTAL]) refuse the new capture with a typed result; terminal records
 *    leave only by [prune] after 24 h, an explicit [dismiss] or a scope retirement, never to make room.
 */
class VoiceCaptureLedger private constructor(
    internal val records: Map<CaptureKey, CaptureEntry>,
    private val intents: Map<CaptureKey, CaptureIntent>,
    private val lastNanos: Long
) {
    val size: Int get() = records.size

    fun entry(key: CaptureKey): CaptureEntry? = records[key]

    /** All records, oldest first. */
    val entries: List<CaptureEntry> get() = Collections.unmodifiableList(records.values.toList())

    fun entriesFor(scope: EnrollmentScope): List<CaptureEntry> = Collections.unmodifiableList(
        records.values.filter {
            it.key.conversation.enrollment == scope
        }
    )

    fun intentOf(key: CaptureKey): CaptureIntent? = intents[key]

    internal fun countFor(scope: EnrollmentScope): Int = records.keys.count {
        it.conversation.enrollment ==
            scope
    }

    internal fun maxPerEnrollment(): Int =
        records.keys.groupingBy { it.conversation.enrollment }.eachCount().values.maxOrNull() ?: 0

    internal fun idInUse(value: String): Boolean = records.values.any {
        it.key.captureId.value ==
            value ||
            it.linkedRequest?.value == value
    }

    // -- begin: press ----------------------------------------------------------------------------------------------------

    /**
     * Press: mints the capture id from the server-time estimate and records `RECORDING`. Needs the CURRENT binding of this socket generation (design C1):
     * its serial is the intent that a transcript may later use. The caller persists before [planStart] (persist-before-start).
     */
    fun begin(
        turns: TurnLedger,
        authority: RelayAuthority,
        token: BindingToken,
        now: ServerInstant,
        entropy: RequestIdEntropy,
        shape: AggregateShape
    ): CaptureStep<BeginOutcome> {
        fun refused(reason: BeginRefusal, mint: MintRefusal? = null) =
            CaptureStep(this, BeginOutcome.Refused(reason, mint), emptyList())
        if (monotonicRefusal(lastNanos, now) != null) return refused(BeginRefusal.CLOCK_ROLLBACK)
        val scope = token.scope
        when (turns.scopeStatus(scope)) {
            ScopeStatus.STOPPED -> return refused(BeginRefusal.SCOPE_STOPPED)
            ScopeStatus.NEEDS_REVIEW -> return refused(BeginRefusal.SCOPE_NEEDS_REVIEW)
            ScopeStatus.ACTIVE -> Unit
        }
        if (token.kind != BindingKind.RELAY) return refused(BeginRefusal.BINDING_KIND)
        if (!authority.isCurrent(token)) return refused(BeginRefusal.BINDING_NOT_CURRENT)
        if (countFor(scope) >= VoiceLimits.CAPTURES_PER_ENROLLMENT) {
            return CaptureStep(
                this,
                BeginOutcome.Backpressure(CaptureLimit.PER_ENROLLMENT),
                emptyList()
            )
        }
        if (records.size >= VoiceLimits.CAPTURES_TOTAL) {
            return CaptureStep(this, BeginOutcome.Backpressure(CaptureLimit.TOTAL), emptyList())
        }
        val id = when (val minted = RequestIds.mint(now, entropy)) {
            is MintResult.Minted -> minted.id
            is MintResult.Refused -> return refused(BeginRefusal.MINT_FAILED, minted.reason)
        }
        if (turns.entries.any { it.key.requestId.value == id.value } ||
            idInUse(id.value)
        ) {
            return refused(BeginRefusal.ID_COLLISION)
        }
        val key = CaptureKey(token.conversation, CaptureId.fromMinted(id))
        val entry =
            CaptureEntry(
                key,
                now.serverMs,
                CaptureLifecycle.RECORDING,
                null,
                null,
                null,
                null,
                null
            )
        val next = derive(
            records = records + (key to entry),
            intents = intents + (key to CaptureIntent(token.serial, null)),
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
        aggregateTooMany(turns, shape, next)?.let {
            return CaptureStep(this, BeginOutcome.AggregateTooMany(it.path, it.limit), emptyList())
        }
        return CaptureStep(next, BeginOutcome.Began(key), listOf(CaptureEffect.PersistAggregate))
    }

    // -- planning the four calls -------------------------------------------------------------------------------------------

    /** The `capture.start` call of a live recording. The snapshot with the `recording` record must be durable before it is written. */
    fun planStart(
        turns: TurnLedger,
        authority: RelayAuthority,
        token: BindingToken,
        key: CaptureKey,
        stream: VoiceStream,
        format: CaptureFormat
    ): CaptureStep<CapturePlan<CaptureStarted>> {
        refusalFor(
            turns,
            authority,
            token,
            key,
            CaptureLifecycle.RECORDING,
            released = false
        )?.let {
            return CaptureStep(this, CapturePlan.Refused(it), emptyList())
        }
        val call = VoiceCalls.captureStart(
            key.captureId,
            token.ref,
            key.conversation.conversationId,
            stream,
            format
        )
        return CaptureStep(
            this,
            CapturePlan.Planned(call),
            listOf(CaptureEffect.PersistBeforeStart(key))
        )
    }

    /**
     * Release: moves a live recording to `FINISHING` and plans `capture.finish` with the upload's own counts (design §8.2 step 2). If the combined snapshot
     * cannot be built the plan is refused with [PlanRefusal.SNAPSHOT_TOO_MANY] and the caller must [cancel] (and discard), never finish.
     */
    fun planFinish(
        turns: TurnLedger,
        authority: RelayAuthority,
        token: BindingToken,
        key: CaptureKey,
        stream: VoiceStream,
        counts: FinishCounts?,
        now: ServerInstant,
        shape: AggregateShape
    ): CaptureStep<CapturePlan<CaptureResult>> {
        fun refused(reason: PlanRefusal, path: String? = null, limit: Int? = null) =
            CaptureStep(this, CapturePlan.Refused(reason, path, limit), emptyList())
        if (monotonicRefusal(lastNanos, now) != null) return refused(PlanRefusal.CLOCK_ROLLBACK)
        refusalFor(
            turns,
            authority,
            token,
            key,
            CaptureLifecycle.RECORDING,
            released = false
        )?.let {
            return refused(it)
        }
        if (counts == null) return refused(PlanRefusal.NO_AUDIO)
        if (counts.tooShort) return refused(PlanRefusal.TOO_SHORT)
        val entry = records.getValue(key)
        val next = derive(
            records = records + (key to entry.update(lifecycle = CaptureLifecycle.FINISHING)),
            intents = intents + (key to CaptureIntent(token.serial, now.serverMs)),
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
        aggregateTooMany(turns, shape, next)?.let {
            return refused(PlanRefusal.SNAPSHOT_TOO_MANY, it.path, it.limit)
        }
        val call = VoiceCalls.captureFinish(key.captureId, stream, counts.frames, counts.durationMs)
        return CaptureStep(
            next,
            CapturePlan.Planned(call),
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.PersistBeforeFinish(key))
        )
    }

    /** A receipt read / long-poll for a capture whose `finish` may have left. Never a new capture, never a re-transcription. */
    fun planResult(key: CaptureKey, waitMs: Int): CapturePlan<CaptureResult> {
        val entry = records[key] ?: return CapturePlan.Refused(PlanRefusal.UNKNOWN_CAPTURE)
        if (entry.lifecycle != CaptureLifecycle.FINISHING &&
            entry.lifecycle != CaptureLifecycle.AWAITING_RESULT
        ) {
            return CapturePlan.Refused(PlanRefusal.WRONG_LIFECYCLE)
        }
        return CapturePlan.Planned(VoiceCalls.captureResult(key.captureId, waitMs))
    }

    /** `capture.discard` of a capture that is not terminal: it erases the server's copy or drops uploaded audio; the local state moves on the answer. */
    fun planDiscard(key: CaptureKey): CapturePlan<CaptureResult> {
        val entry = records[key] ?: return CapturePlan.Refused(PlanRefusal.UNKNOWN_CAPTURE)
        if (entry.lifecycle.terminal) return CapturePlan.Refused(PlanRefusal.WRONG_LIFECYCLE)
        return CapturePlan.Planned(VoiceCalls.captureDiscard(key.captureId))
    }

    private fun refusalFor(
        turns: TurnLedger,
        authority: RelayAuthority,
        token: BindingToken,
        key: CaptureKey,
        expected: CaptureLifecycle,
        released: Boolean
    ): PlanRefusal? {
        val entry = records[key] ?: return PlanRefusal.UNKNOWN_CAPTURE
        if (entry.lifecycle != expected) return PlanRefusal.WRONG_LIFECYCLE
        when (turns.scopeStatus(key.conversation.enrollment)) {
            ScopeStatus.STOPPED -> return PlanRefusal.SCOPE_STOPPED
            ScopeStatus.NEEDS_REVIEW -> return PlanRefusal.SCOPE_NEEDS_REVIEW
            ScopeStatus.ACTIVE -> Unit
        }
        val intent = intents[key]
        if (intent == null ||
            (intent.releasedAtMs != null) != released
        ) {
            return PlanRefusal.AUDIO_NOT_LIVE
        }
        if (token.kind != BindingKind.RELAY) return PlanRefusal.BINDING_KIND
        if (token.scope != key.conversation.enrollment ||
            token.conversationId != key.conversation.conversationId
        ) {
            return PlanRefusal.BINDING_WRONG_SCOPE
        }
        if (!authority.isCurrent(token) ||
            intent.bindingSerial != token.serial
        ) {
            return PlanRefusal.BINDING_NOT_CURRENT
        }
        return null
    }

    // -- local endings of a recording -------------------------------------------------------------------------------------

    /**
     * Slide-left, mic failure, too short, a selection change: the recording ends, nothing was sealed or billed. [CaptureEffect.SendDiscard] drops audio the
     * server may hold. Only a recording can be cancelled; once `finish` may have left the server owns the outcome.
     */
    fun cancel(key: CaptureKey, now: ServerInstant): CaptureStep<Boolean> = endRecording(
        key,
        now,
        CaptureLifecycle.CANCELLED,
        listOf(CaptureEffect.PersistAggregate, CaptureEffect.SendDiscard(key))
    )

    /** The audio of a recording is gone (process loss, hide): `LOST`. Nothing was billed. */
    fun lose(key: CaptureKey, now: ServerInstant): CaptureStep<Boolean> =
        endRecording(key, now, CaptureLifecycle.LOST, listOf(CaptureEffect.PersistAggregate))

    /** After a restore: every `RECORDING` record without a live intent has no audio any more and becomes `LOST`. Returns how many. */
    fun reconcileRecordings(now: ServerInstant): CaptureStep<Int> {
        var next = this
        var count = 0
        for (entry in records.values) {
            if (entry.lifecycle == CaptureLifecycle.RECORDING && entry.key !in intents) {
                next = next.endRecording(entry.key, now, CaptureLifecycle.LOST, emptyList()).ledger
                count++
            }
        }
        return CaptureStep(
            next,
            count,
            if (count >
                0
            ) {
                listOf(CaptureEffect.PersistAggregate)
            } else {
                emptyList()
            }
        )
    }

    private fun endRecording(
        key: CaptureKey,
        now: ServerInstant,
        to: CaptureLifecycle,
        effects: List<CaptureEffect>
    ): CaptureStep<Boolean> {
        val entry = records[key]
        if (entry == null || entry.lifecycle != CaptureLifecycle.RECORDING ||
            monotonicRefusal(lastNanos, now) != null
        ) {
            return CaptureStep(this, false, emptyList())
        }
        val next = derive(
            records = records + (key to entry.update(lifecycle = to, terminalAtMs = now.serverMs)),
            intents = intents - key,
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
        return CaptureStep(next, true, effects)
    }

    // -- results ----------------------------------------------------------------------------------------------------------------

    /**
     * Applies a decoded `CaptureResult` of `finish`, `result` or `discard` to the capture it names. A result for another capture or conversation, for a capture
     * that is no longer waiting (linked, draft, ended, cancelled) or under a frozen scope is ignored before any state changes. A transcript changes nothing
     * here: [ResultOutcome.TranscriptReady] hands the decision to [VoiceAdmission.admit] or [offerDraft].
     */
    fun onResult(
        turns: TurnLedger,
        key: CaptureKey,
        result: CaptureResult,
        now: ServerInstant
    ): CaptureStep<ResultOutcome> {
        fun ignored(why: IgnoredResult) = CaptureStep(this, ResultOutcome.Ignored(why), emptyList())
        val entry = records[key] ?: return ignored(IgnoredResult.UNKNOWN_CAPTURE)
        if (result.captureId != key.captureId ||
            result.conversationId != key.conversation.conversationId
        ) {
            return ignored(IgnoredResult.FOREIGN_RESULT)
        }
        if (entry.lifecycle != CaptureLifecycle.FINISHING &&
            entry.lifecycle != CaptureLifecycle.AWAITING_RESULT
        ) {
            return ignored(IgnoredResult.NOT_AWAITING)
        }
        if (turns.scopeStatus(key.conversation.enrollment) !=
            ScopeStatus.ACTIVE
        ) {
            return ignored(IgnoredResult.SCOPE_FROZEN)
        }
        val evidence = mergeProvider(entry.provider, result.provider)
        val waiting = entry.update(
            lifecycle = CaptureLifecycle.AWAITING_RESULT,
            provider = evidence
        )
        val (outcome, end, failure) = when (result.state) {
            CaptureState.QUEUED, CaptureState.PREPARING, CaptureState.TRANSCRIBING -> Triple(
                ResultOutcome.Progress(result.state),
                null,
                null
            )
            CaptureState.TRANSCRIBED -> Triple(ResultOutcome.TranscriptReady, null, null)
            CaptureState.EMPTY -> ended(CaptureEnd.EMPTY, null)
            CaptureState.TOO_LONG -> ended(CaptureEnd.TRANSCRIPT_TOO_LONG, null)
            CaptureState.FAILED -> ended(
                if (result.reason?.preSeal ==
                    true
                ) {
                    CaptureEnd.PRE_SEAL_FAILED
                } else {
                    CaptureEnd.FAILED
                },
                result.reason
            )
            CaptureState.DISCARDED -> ended(CaptureEnd.DISCARDED, null)
            CaptureState.EXPIRED -> ended(CaptureEnd.EXPIRED, null)
            CaptureState.REVOKED -> ended(CaptureEnd.REVOKED, null)
            CaptureState.UNKNOWN -> ended(CaptureEnd.UNKNOWN_OUTCOME, null)
        }
        val changedEntry = if (end != null) {
            waiting.update(
                lifecycle = CaptureLifecycle.ENDED,
                end = end,
                failure = failure,
                terminalAtMs = now.serverMs
            )
        } else {
            waiting
        }
        val next = derive(
            records = records + (key to changedEntry),
            intents = if (end != null) intents - key else intents,
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
        val durable =
            changedEntry.lifecycle != entry.lifecycle || changedEntry.provider != entry.provider
        return CaptureStep(
            next,
            outcome,
            if (durable) listOf(CaptureEffect.PersistAggregate) else emptyList()
        )
    }

    private fun ended(end: CaptureEnd, failure: CaptureFailure?) =
        Triple(ResultOutcome.Ended(end, failure), end, failure)

    /**
     * A failed voice call. `not_found` of a result read means nothing was sealed or billed: the capture ends [CaptureEnd.NOT_SEALED]. A stale binding,
     * `permission_denied` or `profile_changed` withdraws the scope's transient intents (no later transcript may auto-submit); the scope decision itself
     * is the Task2 ledger's. Transient and unexpected errors change nothing.
     */
    fun onError(key: CaptureKey, error: RelayError, now: ServerInstant): CaptureStep<ErrorAdvice> {
        val entry = records[key]
        val action = error.action
        var next = this
        if (action == RelayErrorAction.REATTACH_AND_REFRESH ||
            action == RelayErrorAction.STOP_AND_EVICT_SCOPE ||
            action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
        ) {
            next = next.withdrawIntents(key.conversation.enrollment).ledger
        }
        val waiting =
            entry != null &&
                (
                    entry.lifecycle == CaptureLifecycle.FINISHING ||
                        entry.lifecycle == CaptureLifecycle.AWAITING_RESULT
                    )
        if (entry != null && waiting && error.code == RelayErrorCode.NOT_FOUND &&
            monotonicRefusal(lastNanos, now) == null
        ) {
            val finished = entry.update(
                lifecycle = CaptureLifecycle.ENDED,
                provider = VoiceProvider.NOT_STARTED,
                end = CaptureEnd.NOT_SEALED,
                terminalAtMs = now.serverMs
            )
            val done = next.derive(
                records = next.records + (key to finished),
                intents = next.intents - key,
                lastNanos = maxOf(lastNanos, now.monotonicNanos)
            )
            return CaptureStep(
                done,
                ErrorAdvice(action, CaptureEnd.NOT_SEALED),
                listOf(CaptureEffect.PersistAggregate)
            )
        }
        return CaptureStep(next, ErrorAdvice(action, null), emptyList())
    }

    // -- drafts and the linked turn ----------------------------------------------------------------------------------------

    /**
     * The transcript becomes an unsent editable draft in memory only; the record moves to `DRAFT_OFFERED` and never auto-submits. A user who edits and sends
     * the draft makes a NEW typed turn (a new `prepare`); this capture stays `DRAFT_OFFERED` until [endDraft].
     */
    fun offerDraft(
        turns: TurnLedger,
        key: CaptureKey,
        result: CaptureResult
    ): CaptureStep<DraftOutcome> {
        fun refused(reason: DraftRefusal) =
            CaptureStep(this, DraftOutcome.Refused(reason), emptyList())
        val entry = records[key] ?: return refused(DraftRefusal.UNKNOWN_CAPTURE)
        if (result.captureId != key.captureId ||
            result.conversationId != key.conversation.conversationId
        ) {
            return refused(DraftRefusal.MISMATCH)
        }
        val text = result.transcript
        if (result.state != CaptureState.TRANSCRIBED ||
            text == null
        ) {
            return refused(DraftRefusal.NOT_TRANSCRIBED)
        }
        if (entry.linkedRequest != null) return refused(DraftRefusal.ALREADY_LINKED)
        if (entry.lifecycle != CaptureLifecycle.FINISHING &&
            entry.lifecycle != CaptureLifecycle.AWAITING_RESULT
        ) {
            return refused(DraftRefusal.WRONG_LIFECYCLE)
        }
        if (turns.scopeStatus(key.conversation.enrollment) !=
            ScopeStatus.ACTIVE
        ) {
            return refused(DraftRefusal.SCOPE_FROZEN)
        }
        val next = derive(
            records = records + (
                key to
                    entry.update(
                        lifecycle = CaptureLifecycle.DRAFT_OFFERED,
                        provider = mergeProvider(entry.provider, result.provider)
                    )
                ),
            intents = intents - key
        )
        return CaptureStep(
            next,
            DraftOutcome.Offered(DraftTranscript(key, text)),
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.MayDiscardServerCopy(key))
        )
    }

    /** The draft was sent as a new typed turn or dismissed: `DRAFT_OFFERED` -> `ENDED`. */
    fun endDraft(key: CaptureKey, now: ServerInstant): CaptureStep<Boolean> {
        val entry = records[key]
        if (entry == null || entry.lifecycle != CaptureLifecycle.DRAFT_OFFERED ||
            monotonicRefusal(lastNanos, now) != null
        ) {
            return CaptureStep(this, false, emptyList())
        }
        val next = derive(
            records = records + (
                key to
                    entry.update(
                        lifecycle = CaptureLifecycle.ENDED,
                        end = CaptureEnd.DRAFT_RESOLVED,
                        terminalAtMs = now.serverMs
                    )
                ),
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
        return CaptureStep(next, true, listOf(CaptureEffect.PersistAggregate))
    }

    /**
     * Ends every `ADMITTING` capture whose linked turn the Task2 ledger has settled (terminal or refused: [CaptureEnd.TURN_SETTLED]) or no longer holds
     * ([CaptureEnd.TURN_GONE]). An unresolved turn (uncertain, absent so far, `EXPIRED_REPORTED`, queued, running) keeps its capture `ADMITTING`.
     */
    fun settleLinked(turns: TurnLedger, now: ServerInstant): CaptureStep<Int> {
        if (monotonicRefusal(lastNanos, now) != null) return CaptureStep(this, 0, emptyList())
        val updated = LinkedHashMap(records)
        var count = 0
        for (entry in records.values) {
            val request = entry.linkedRequest
            if (entry.lifecycle != CaptureLifecycle.ADMITTING || request == null) continue
            val turn = turns.entry(TurnKey(entry.key.conversation, request))
            val end = when {
                turn == null -> CaptureEnd.TURN_GONE
                turn.phase == TurnPhase.TERMINAL ||
                    turn.phase == TurnPhase.REFUSED -> CaptureEnd.TURN_SETTLED
                else -> null
            } ?: continue
            updated[entry.key] =
                entry.update(
                    lifecycle = CaptureLifecycle.ENDED,
                    end = end,
                    terminalAtMs = now.serverMs
                )
            count++
        }
        if (count == 0) return CaptureStep(this, 0, emptyList())
        return CaptureStep(
            derive(records = updated, lastNanos = maxOf(lastNanos, now.monotonicNanos)),
            count,
            listOf(CaptureEffect.PersistAggregate)
        )
    }

    // -- invalidation ---------------------------------------------------------------------------------------------------------------

    /** Hide, selection change, profile replacement: the transient intents of this scope go. The records stay; later transcripts can only be drafts. */
    fun withdrawIntents(scope: EnrollmentScope): CaptureStep<Int> =
        withdraw { it.conversation.enrollment == scope }

    /** The selection of one conversation changed: only its intents go. */
    fun withdrawIntents(conversation: ConversationScope): CaptureStep<Int> =
        withdraw { it.conversation == conversation }

    /** The binding of [scope] (or just [bindingSerial]) is gone (reconnect, rebind): the intents made under it go. */
    fun bindingLost(scope: EnrollmentScope, bindingSerial: Long? = null): CaptureStep<Int> =
        withdraw { key ->
            key.conversation.enrollment == scope &&
                (bindingSerial == null || intents[key]?.bindingSerial == bindingSerial)
        }

    private fun withdraw(match: (CaptureKey) -> Boolean): CaptureStep<Int> {
        val gone = intents.keys.filter(match)
        if (gone.isEmpty()) return CaptureStep(this, 0, emptyList())
        return CaptureStep(derive(intents = intents - gone.toSet()), gone.size, emptyList())
    }

    /**
     * An authorized enrollment retirement (or a replaced profile): every record of [scope] goes with it and nothing is reactivated. Returns the keys that
     * were not terminal so the consumer can tell the user which spoken intents were dropped.
     */
    fun retireScope(scope: EnrollmentScope): CaptureStep<List<CaptureKey>> {
        val gone = records.keys.filter { it.conversation.enrollment == scope }
        if (gone.isEmpty()) return CaptureStep(this, emptyList(), emptyList())
        val open = gone.filter { records.getValue(it).lifecycle.terminal.not() }
        val next = derive(records = records - gone.toSet(), intents = intents - gone.toSet())
        return CaptureStep(
            next,
            Collections.unmodifiableList(open),
            listOf(CaptureEffect.PersistAggregate)
        )
    }

    // -- housekeeping ---------------------------------------------------------------------------------------------------------------

    /** Removes terminal records older than [VoiceLimits.TERMINAL_RETENTION_MS] (a stale billing note is not kept past what the server keeps). */
    fun prune(now: ServerInstant): CaptureStep<Int> {
        if (monotonicRefusal(lastNanos, now) != null) return CaptureStep(this, 0, emptyList())
        val expired = records.values.filter { entry ->
            val at = entry.terminalAtMs
            entry.lifecycle.terminal && at != null &&
                now.serverMs - at >= VoiceLimits.TERMINAL_RETENTION_MS
        }.map { it.key }
        if (expired.isEmpty()) return CaptureStep(this, 0, emptyList())
        return CaptureStep(
            derive(
                records = records - expired.toSet(),
                intents = intents - expired.toSet(),
                lastNanos = maxOf(lastNanos, now.monotonicNanos)
            ),
            expired.size,
            listOf(CaptureEffect.PersistAggregate)
        )
    }

    /** The user dismisses a terminal record early. This forgets its billing note by explicit choice; a record that is not terminal is never dropped. */
    fun dismiss(key: CaptureKey): CaptureStep<Boolean> {
        val entry = records[key]
        if (entry == null || !entry.lifecycle.terminal) return CaptureStep(this, false, emptyList())
        return CaptureStep(
            derive(records = records - key, intents = intents - key),
            true,
            listOf(CaptureEffect.PersistAggregate)
        )
    }

    // -- internals --------------------------------------------------------------------------------------------------------------------

    internal fun derive(
        records: Map<CaptureKey, CaptureEntry> = this.records,
        intents: Map<CaptureKey, CaptureIntent> = this.intents,
        lastNanos: Long = this.lastNanos
    ): VoiceCaptureLedger = VoiceCaptureLedger(records, intents, lastNanos)

    /** Used by [VoiceAdmission]: the same ledger with [key] linked and ADMITTING, atomically with the caller's prepared turn ledger. */
    internal fun linked(
        key: CaptureKey,
        request: RequestId,
        evidence: VoiceProvider,
        now: ServerInstant
    ): VoiceCaptureLedger {
        val entry = records.getValue(key)
        return derive(
            records = records + (
                key to
                    entry.update(
                        lifecycle = CaptureLifecycle.ADMITTING,
                        linkedRequest = request,
                        provider = mergeProvider(entry.provider, evidence)
                    )
                ),
            intents = intents - key,
            lastNanos = maxOf(lastNanos, now.monotonicNanos)
        )
    }

    /**
     * The durable part as plain values: identity, state, link, billing evidence and ending of every record. Nothing transient is saved: no intent,
     * stream, binding, audio or text. Persisting it, and in what order, is the consumer's job.
     */
    fun snapshot(): CaptureLedgerSnapshot = CaptureLedgerSnapshot(
        CaptureLedgerSnapshot.VERSION,
        records.values.asSequence().take(VoiceLimits.CAPTURES_TOTAL + 1).map {
            CaptureRecordSnapshot(
                it.key.conversation.enrollment.toSnapshot(),
                it.key.conversation.conversationId.value,
                it.key.captureId.value,
                it.createdAtMs,
                it.lifecycle.name,
                it.linkedRequest?.value,
                it.provider?.wire,
                it.end?.name,
                it.failure?.wire,
                it.terminalAtMs
            )
        }.toList()
    )

    internal fun nanos(): Long = lastNanos

    override fun toString(): String =
        "VoiceCaptureLedger(records=${records.size}, intents=${intents.size})"

    companion object {
        fun empty(): VoiceCaptureLedger = VoiceCaptureLedger(emptyMap(), emptyMap(), 0)

        /**
         * Restores the capture section against the already restored turn [turns]. Every record is re-validated and the first violation refuses the whole
         * section (never shortens it). All transient intents start EMPTY: no transcript that arrives after a restore can be auto-submitted, and a
         * `RECORDING` record has no audio. No identity, link or billing fact is invented; a capture linked to a turn is never prepared again.
         */
        internal fun restore(
            saved: CaptureLedgerSnapshot,
            turns: TurnLedger,
            now: ServerInstant
        ): RestoreResult<VoiceCaptureLedger> {
            fun refused(reason: RestoreRefusal, path: String) = RestoreResult.Refused(reason, path)
            if (saved.version !=
                CaptureLedgerSnapshot.VERSION
            ) {
                return refused(RestoreRefusal.VERSION, "version")
            }
            if (saved.records.size >
                VoiceLimits.CAPTURES_TOTAL
            ) {
                return refused(RestoreRefusal.TOO_MANY, "records")
            }
            val records = LinkedHashMap<CaptureKey, CaptureEntry>()
            val perScope = HashMap<EnrollmentScope, Int>()
            val linked = HashSet<TurnKey>()
            var recordings = false
            var orphan = false
            var clockBehind = false
            for ((index, item) in saved.records.withIndex()) {
                val path = "records[$index]"
                val scope =
                    item.scope.toScope()
                        ?: return refused(RestoreRefusal.INVALID_SCOPE, "$path.scope")
                val conversation =
                    ConversationId.of(item.conversationId)
                        ?: return refused(RestoreRefusal.INVALID_ID, "$path.conversationId")
                val captureId =
                    CaptureId.of(item.captureId)
                        ?: return refused(RestoreRefusal.INVALID_ID, "$path.captureId")
                val key = CaptureKey(ConversationScope(scope, conversation), captureId)
                if (key in records) return refused(RestoreRefusal.DUPLICATE, path)
                val stamped = item.createdAtMs in 0..StateLimits.SERVER_TIME_MAX_MS &&
                    RequestIds.timestampMs(RequestId.of(item.captureId)!!) == item.createdAtMs
                if (!stamped) return refused(RestoreRefusal.INCONSISTENT, "$path.createdAtMs")
                val lifecycle = CaptureLifecycle.entries.firstOrNull { it.name == item.state }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.state")
                val link = item.linkedRequest?.let {
                    RequestId.of(it)
                        ?: return refused(RestoreRefusal.INVALID_ID, "$path.linkedRequest")
                }
                val provider = item.provider?.let { wire ->
                    VoiceProvider.entries.firstOrNull { it.wire == wire }
                        ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.provider")
                }
                val end = item.end?.let { name ->
                    CaptureEnd.entries.firstOrNull { it.name == name }
                        ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.end")
                }
                val failure = item.failure?.let { wire ->
                    CaptureFailure.entries.firstOrNull { it.wire == wire }
                        ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.failure")
                }
                val turnLinked = end == CaptureEnd.TURN_SETTLED || end == CaptureEnd.TURN_GONE
                val shape =
                    (link != null) ==
                        (
                            lifecycle == CaptureLifecycle.ADMITTING ||
                                (lifecycle == CaptureLifecycle.ENDED && turnLinked)
                            ) &&
                        (end != null) == (lifecycle == CaptureLifecycle.ENDED) &&
                        (failure != null) ==
                        (end == CaptureEnd.FAILED || end == CaptureEnd.PRE_SEAL_FAILED) &&
                        (end != CaptureEnd.PRE_SEAL_FAILED || failure?.preSeal == true) &&
                        (end != CaptureEnd.FAILED || failure?.preSeal == false) &&
                        (lifecycle.terminal == (item.terminalAtMs != null))
                if (!shape) return refused(RestoreRefusal.INCONSISTENT, path)
                val terminalAt = item.terminalAtMs
                if (terminalAt != null &&
                    (terminalAt < item.createdAtMs || terminalAt > StateLimits.SERVER_TIME_MAX_MS)
                ) {
                    return refused(RestoreRefusal.INCONSISTENT, "$path.terminalAtMs")
                }
                val beforeAnyAnswer =
                    lifecycle == CaptureLifecycle.RECORDING ||
                        lifecycle == CaptureLifecycle.FINISHING ||
                        lifecycle == CaptureLifecycle.CANCELLED ||
                        lifecycle == CaptureLifecycle.LOST
                if (beforeAnyAnswer &&
                    provider != null
                ) {
                    return refused(RestoreRefusal.INCONSISTENT, "$path.provider")
                }
                val inScope = (perScope[scope] ?: 0) + 1
                perScope[scope] = inScope
                if (inScope >
                    VoiceLimits.CAPTURES_PER_ENROLLMENT
                ) {
                    return refused(RestoreRefusal.TOO_MANY, "$path.scope")
                }
                if (link != null &&
                    !linked.add(TurnKey(key.conversation, link))
                ) {
                    return refused(RestoreRefusal.DUPLICATE, "$path.linkedRequest")
                }
                if (lifecycle == CaptureLifecycle.RECORDING) recordings = true
                if (lifecycle == CaptureLifecycle.ADMITTING && link != null &&
                    turns.entry(TurnKey(key.conversation, link)) == null
                ) {
                    orphan = true
                }
                if (item.createdAtMs > now.serverMs + StateLimits.ID_FUTURE_MS) clockBehind = true
                records[key] =
                    CaptureEntry(
                        key,
                        item.createdAtMs,
                        lifecycle,
                        link,
                        provider,
                        end,
                        failure,
                        terminalAt
                    )
            }
            // A capture id is never a turn request id, and no capture is linked to another capture's id (design §6.1: distinct kinds).
            val turnIds = turns.entries.mapTo(HashSet()) { it.key.requestId.value }
            for ((index, entry) in records.values.withIndex()) {
                val id = entry.key.captureId.value
                val clash =
                    id in turnIds ||
                        records.values.any { other ->
                            other !== entry &&
                                other.linkedRequest?.value == id
                        }
                if (clash) return refused(RestoreRefusal.INCONSISTENT, "records[$index].captureId")
            }
            val notices = ArrayList<RestoreNotice>()
            if (recordings) notices.add(RestoreNotice.CAPTURE_RECORDINGS_LOST)
            if (orphan) notices.add(RestoreNotice.CAPTURE_LINK_WITHOUT_TURN)
            if (clockBehind) notices.add(RestoreNotice.SERVER_CLOCK_BEHIND_SNAPSHOT)
            return RestoreResult.Restored(
                VoiceCaptureLedger(records, emptyMap(), now.monotonicNanos),
                notices
            )
        }

        /**
         * Billing evidence only moves forward and never back to "not billed": `unknown` is sticky, a concrete answer replaces an earlier concrete one only
         * when the earlier one was `not_started` or `in_flight`.
         */
        internal fun mergeProvider(
            current: VoiceProvider?,
            incoming: VoiceProvider
        ): VoiceProvider = when {
            current == null -> incoming
            current == VoiceProvider.UNKNOWN -> VoiceProvider.UNKNOWN
            incoming == VoiceProvider.UNKNOWN -> VoiceProvider.UNKNOWN
            current == VoiceProvider.NOT_STARTED || current == VoiceProvider.IN_FLIGHT -> incoming
            incoming == VoiceProvider.NOT_STARTED || incoming == VoiceProvider.IN_FLIGHT -> current
            else -> current
        }
    }
}
