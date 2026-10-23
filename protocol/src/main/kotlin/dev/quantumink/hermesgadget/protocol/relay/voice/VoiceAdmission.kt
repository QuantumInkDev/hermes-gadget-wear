package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerLimit
import dev.quantumink.hermesgadget.protocol.relay.state.MintRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.PrepareOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PrepareRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeStatus
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnDraft
import dev.quantumink.hermesgadget.protocol.relay.state.TurnKey
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.state.aggregateTooMany
import dev.quantumink.hermesgadget.protocol.relay.state.monotonicRefusal

/** Why a transcript arrived but is not an automatic admission candidate at all. Nothing was changed. */
enum class AdmissionIneligible {
    UNKNOWN_CAPTURE,
    FOREIGN_RESULT,
    NOT_TRANSCRIBED,
    WRONG_LIFECYCLE,
    SCOPE_FROZEN
}

/** Why the spoken intent no longer holds (design C4). The transcript is then an unsent draft and nothing is prepared. */
enum class DraftReason {
    /** No live intent: a restart, hide, selection change, profile change or invalidation removed it. */
    NO_INTENT,

    /** The socket generation or binding is no longer the one the recording was released under. */
    BINDING_CHANGED,

    /** More than [VoiceLimits.INTENT_WINDOW_MS] passed between release and the transcript. */
    INTENT_WINDOW_PASSED
}

/** Why a turn could not be prepared and linked: neither half committed. */
sealed interface AdmissionRefusal {
    class Prepare(val reason: PrepareRefusal, val mint: MintRefusal?) : AdmissionRefusal {
        override fun toString(): String = "Prepare($reason${mint?.let { ", $it" } ?: ""})"
    }

    /** The Task2 ledger is full (typed backpressure). */
    class TurnLedgerFull(val limit: LedgerLimit) : AdmissionRefusal {
        override fun toString(): String = "TurnLedgerFull($limit)"
    }

    /** The freshly minted turn id equals a capture id (or a capture's link): a refusal, never a re-mint and never a partial link. */
    data object IdKindCollision : AdmissionRefusal

    /** The combined snapshot of the result would exceed a component limit. */
    class AggregateTooMany(val path: String, val limit: Int) : AdmissionRefusal {
        override fun toString(): String = "AggregateTooMany($path, limit=$limit)"
    }
}

sealed interface AdmissionOutcome {
    /** The turn was prepared and the capture linked in ONE transition. The caller persists, then plans the submit of [turn] (persist-before-send). */
    class Linked(val capture: CaptureKey, val turn: TurnKey) : AdmissionOutcome {
        override fun toString(): String = "Linked($capture, $turn)"
    }

    /** This capture already prepared its one turn (also after a restore): nothing happens. */
    class AlreadyLinked(val turn: TurnKey) : AdmissionOutcome {
        override fun toString(): String = "AlreadyLinked($turn)"
    }

    class NotEligible(val why: AdmissionIneligible) : AdmissionOutcome {
        override fun toString(): String = "NotEligible($why)"
    }

    /** C4 failed: the capture moved to `DRAFT_OFFERED` and [draft] is the unsent text for the user to edit. No turn was prepared. */
    class DraftOnly(val draft: DraftTranscript, val why: DraftReason) : AdmissionOutcome {
        override fun toString(): String = "DraftOnly($why, $draft)"
    }

    /** Nothing changed (neither the turn ledger nor the capture ledger). The caller may offer the draft explicitly. */
    class Refused(val reason: AdmissionRefusal) : AdmissionOutcome {
        override fun toString(): String = "Refused($reason)"
    }
}

/**
 * The aggregate transition of a transcript: the next turn ledger, the next capture ledger, what happened and the obligations. When the outcome is
 * [AdmissionOutcome.Linked] both halves changed in this one value (one encrypted write, [CaptureEffect.PersistAggregate]); in every other outcome
 * except [AdmissionOutcome.DraftOnly] both halves are the very input instances.
 */
class AdmissionStep(
    val turns: TurnLedger,
    val captures: VoiceCaptureLedger,
    val outcome: AdmissionOutcome,
    val effects: List<CaptureEffect>
) {
    override fun toString(): String = "AdmissionStep($outcome, effects=${effects.size})"
}

object VoiceAdmission {
    /**
     * A current `transcribed` result becomes a Task2 turn: [TurnLedger.prepare] (mints the request id from the server-time estimate, fixes the deadline,
     * stores the immutable text) and `linkedRequest` + `ADMITTING` of the capture are ONE returned transition, or nothing changes.
     *
     * Applicable pure C4 conditions: the capture is `FINISHING`/`AWAITING_RESULT`, was never linked, its scope is active, its transient intent is still
     * live and released, THE SAME binding is current, and the transcript arrived inside [VoiceLimits.INTENT_WINDOW_MS] of the release. Otherwise the
     * transcript is [AdmissionOutcome.DraftOnly]. Foreground and selection continuity are modelled as the intent itself: the consumer withdraws it
     * ([VoiceCaptureLedger.withdrawIntents]) on hide, selection change or profile change.
     */
    fun admit(
        turns: TurnLedger,
        captures: VoiceCaptureLedger,
        key: CaptureKey,
        result: CaptureResult,
        authority: RelayAuthority,
        now: ServerInstant,
        entropy: RequestIdEntropy,
        shape: AggregateShape,
        ttlMs: Long = StateLimits.DEFAULT_TURN_TTL_MS
    ): AdmissionStep {
        fun same(outcome: AdmissionOutcome) = AdmissionStep(turns, captures, outcome, emptyList())
        fun ineligible(why: AdmissionIneligible) = same(AdmissionOutcome.NotEligible(why))
        val entry = captures.entry(key) ?: return ineligible(AdmissionIneligible.UNKNOWN_CAPTURE)
        if (result.captureId != key.captureId ||
            result.conversationId != key.conversation.conversationId
        ) {
            return ineligible(AdmissionIneligible.FOREIGN_RESULT)
        }
        val text = result.transcript
        if (result.state != CaptureState.TRANSCRIBED ||
            text == null
        ) {
            return ineligible(AdmissionIneligible.NOT_TRANSCRIBED)
        }
        // Once linked, never again: not by a repeated result, not by a restore, not after the turn settled.
        entry.linkedRequest?.let {
            return same(AdmissionOutcome.AlreadyLinked(TurnKey(key.conversation, it)))
        }
        if (entry.lifecycle != CaptureLifecycle.FINISHING &&
            entry.lifecycle != CaptureLifecycle.AWAITING_RESULT
        ) {
            return ineligible(AdmissionIneligible.WRONG_LIFECYCLE)
        }
        if (turns.scopeStatus(key.conversation.enrollment) !=
            ScopeStatus.ACTIVE
        ) {
            return ineligible(AdmissionIneligible.SCOPE_FROZEN)
        }
        if (monotonicRefusal(captures.nanos(), now) != null) {
            return same(
                AdmissionOutcome.Refused(
                    AdmissionRefusal.Prepare(PrepareRefusal.CLOCK_ROLLBACK, null)
                )
            )
        }

        val why = draftReason(captures, key, authority, now)
        if (why != null) {
            val offered = captures.offerDraft(turns, key, result)
            val outcome =
                offered.result as? DraftOutcome.Offered
                    ?: return same(
                        AdmissionOutcome.Refused(
                            AdmissionRefusal.Prepare(PrepareRefusal.INVALID_TEXT, null)
                        )
                    )
            return AdmissionStep(
                turns,
                offered.ledger,
                AdmissionOutcome.DraftOnly(outcome.draft, why),
                offered.effects
            )
        }

        val prepared = turns.prepare(TurnDraft(key.conversation, text, ttlMs), now, entropy)
        val turnKey = when (val outcome = prepared.result) {
            is PrepareOutcome.Backpressure -> return same(
                AdmissionOutcome.Refused(AdmissionRefusal.TurnLedgerFull(outcome.limit))
            )
            is PrepareOutcome.Refused -> return same(
                AdmissionOutcome.Refused(AdmissionRefusal.Prepare(outcome.reason, outcome.mint))
            )
            is PrepareOutcome.Prepared -> outcome.key
        }
        if (turnKey.requestId.value == key.captureId.value ||
            captures.idInUse(turnKey.requestId.value)
        ) {
            return same(AdmissionOutcome.Refused(AdmissionRefusal.IdKindCollision))
        }
        val linked = captures.linked(key, turnKey.requestId, result.provider, now)
        aggregateTooMany(prepared.ledger, shape, linked)?.let {
            return same(
                AdmissionOutcome.Refused(AdmissionRefusal.AggregateTooMany(it.path, it.limit))
            )
        }
        return AdmissionStep(
            prepared.ledger,
            linked,
            AdmissionOutcome.Linked(key, turnKey),
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.MayDiscardServerCopy(key))
        )
    }

    private fun draftReason(
        captures: VoiceCaptureLedger,
        key: CaptureKey,
        authority: RelayAuthority,
        now: ServerInstant
    ): DraftReason? {
        val intent = captures.intentOf(key)
        val released = intent?.releasedAtMs ?: return DraftReason.NO_INTENT
        val current = authority.current(key.conversation.enrollment)
        if (current == null || current.serial != intent.bindingSerial ||
            current.conversationId != key.conversation.conversationId ||
            !authority.isCurrent(current)
        ) {
            return DraftReason.BINDING_CHANGED
        }
        if (now.serverMs - released >
            VoiceLimits.INTENT_WINDOW_MS
        ) {
            return DraftReason.INTENT_WINDOW_PASSED
        }
        return null
    }
}
