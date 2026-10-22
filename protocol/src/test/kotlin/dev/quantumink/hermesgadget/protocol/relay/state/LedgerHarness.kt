package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives a [TurnLedger] the way a controller would, recording every step. Replies go through the REAL decode path:
 * a frame is built, parsed by [RelayEnvelope] and interpreted by the planned call, so strict decoding and the
 * correlation check are exercised, not mocked.
 */
internal class LedgerHarness(
    val enrollment: EnrollmentScope = StateTestSupport.enrollment,
    private val template: Binding = StateTestSupport.attached(0)
) {
    var ledger: TurnLedger = TurnLedger.empty()
    var authority: RelayAuthority = RelayAuthority.fresh()
    val entropy = StateTestSupport.CountingEntropy()
    val effects = mutableListOf<TurnEffect>()

    /** The wire methods of every call the ledger planned, in order. */
    val dispatched = mutableListOf<String>()
    private val clock = StateTestSupport.clockAt(1_000_000_000L)
    var nanos = 1_000_000_000L
        private set
    private var bindings = 0
    private var correlations = 0

    val conversation: ConversationScope get() = ConversationScope(
        enrollment,
        template.conversationId
    )

    fun now(): ServerInstant = StateTestSupport.instant(clock, nanos)

    fun instantAtNanos(value: Long): ServerInstant = StateTestSupport.instant(clock, value)

    fun advance(ms: Long): ServerInstant {
        nanos += ms * 1_000_000L
        return now()
    }

    /** The estimated server time right now. */
    val serverMs: Long get() = now().serverMs

    fun <R> apply(step: LedgerStep<R>): R {
        ledger = step.ledger
        effects.addAll(step.effects)
        return step.result
    }

    fun prepare(text: String = "hello", ttlMs: Long = StateLimits.DEFAULT_TURN_TTL_MS): TurnKey {
        val outcome = apply(ledger.prepare(TurnDraft(conversation, text, ttlMs), now(), entropy))
        return (outcome as PrepareOutcome.Prepared).key
    }

    fun attach(): BindingToken {
        bindings++
        val binding = Binding(
            BindingId.of("00000000-0000-4000-8000-%012d".format(bindings))!!,
            template.conversationId,
            template.authorityEpoch,
            template.executionGeneration,
            template.revision,
            template.replayEpoch,
            template.lastSequence,
            template.executionState
        )
        val step = authority.attach(enrollment, binding)
        authority = step.authority
        return step.token
    }

    fun correlation(): CorrelationId = CorrelationId.of("c-%06d".format(++correlations))!!

    fun entry(key: TurnKey): TurnEntry = ledger.entry(key)!!

    fun phase(key: TurnKey): TurnPhase = entry(key).phase

    fun submit(
        key: TurnKey,
        token: BindingToken,
        correlation: CorrelationId = correlation()
    ): PlanOutcome {
        val outcome = apply(ledger.planSubmit(key, authority, token, now(), correlation))
        if (outcome is PlanOutcome.Planned) dispatched.add(outcome.dispatch.call.method.wire)
        return outcome
    }

    fun lookup(
        key: TurnKey,
        token: BindingToken,
        correlation: CorrelationId = correlation()
    ): PlanOutcome {
        val outcome = apply(ledger.planLookup(key, authority, token, now(), correlation))
        if (outcome is PlanOutcome.Planned) dispatched.add(outcome.dispatch.call.method.wire)
        return outcome
    }

    /** Attaches, plans the first submit of [key] on that binding and returns both. */
    fun token1(key: TurnKey): Pair<BindingToken, TurnDispatch> {
        val token = attach()
        return token to planned(submit(key, token))
    }

    /** Plans a submit against an explicit [authority], without recording it as dispatched. */
    fun submitUsing(key: TurnKey, authority: RelayAuthority, token: BindingToken): PlanOutcome =
        ledger.planSubmit(key, authority, token, now(), correlation()).result

    fun planned(outcome: PlanOutcome): TurnDispatch = (outcome as PlanOutcome.Planned).dispatch

    fun refusal(outcome: PlanOutcome): PlanRefusal = (outcome as PlanOutcome.Refused).reason

    fun reply(dispatch: TurnDispatch, receipt: Receipt): ReplyOutcome =
        apply(ledger.onReply(dispatch.ref, success(dispatch, receipt)))

    fun fail(
        dispatch: TurnDispatch,
        code: String,
        snapshotRequired: Boolean = false
    ): ReplyOutcome = apply(ledger.onReply(dispatch.ref, error(dispatch, code, snapshotRequired)))

    fun timeout(dispatch: TurnDispatch): ReplyOutcome = apply(ledger.onTimeout(dispatch.ref))

    fun success(dispatch: TurnDispatch, receipt: Receipt): RelayOutcome<Receipt> =
        dispatch.call.interpret(
            dispatch.ref.correlation,
            RelayEnvelope.decodeFrame(
                frame(dispatch.ref.correlation.value, "result", receipt.toWire())
            )
        )

    fun error(
        dispatch: TurnDispatch,
        code: String,
        snapshotRequired: Boolean = false
    ): RelayOutcome<Receipt> {
        val body =
            mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "code" to JsonPrimitive(code)
            )
        if (snapshotRequired) body["snapshot_required"] = JsonPrimitive(true)
        return dispatch.call.interpret(
            dispatch.ref.correlation,
            RelayEnvelope.decodeFrame(
                frame(dispatch.ref.correlation.value, "error", JsonObject(body))
            )
        )
    }

    private fun frame(id: String, member: String, body: JsonObject): String = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("relay.response"),
            "v" to JsonPrimitive(1),
            "id" to JsonPrimitive(id),
            member to body
        )
    ).toString()

    /** A receipt about [key]'s turn for the one admission [admission]. */
    fun receipt(
        key: TurnKey,
        status: ReceiptStatus,
        generation: Long? = null,
        outcome: String? = null,
        admission: String = ADMISSION,
        epoch: Long = 1
    ): Receipt = Receipt(
        key.requestId,
        AdmissionId.of(admission)!!,
        key.conversation.conversationId,
        status,
        outcome,
        epoch,
        generation
    )

    companion object {
        const val ADMISSION = "b51c880f-2574-4650-99fb-cd685725b5b3"
        const val OTHER_ADMISSION = "a885fe07-4cde-44b0-85b9-4457fa6f9b0d"

        /** The captured receipt-shaped successes of submit, receipt and cancel, decoded by the Task1 codec. */
        val capturedReceipts: List<Receipt> by lazy {
            listOf(RelayMethod.TURN_SUBMIT, RelayMethod.TURN_RECEIPT, RelayMethod.TURN_CANCEL)
                .flatMap { RelayVectors.forMethod(it) }
                .filter { it.isSuccess }
                .map { Receipt.decode(it.result) }
        }
    }
}
