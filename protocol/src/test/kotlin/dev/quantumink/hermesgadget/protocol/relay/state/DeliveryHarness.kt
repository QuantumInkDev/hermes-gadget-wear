package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.ExecutionState
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Drives an [EnrollmentDelivery] like a controller, answering planned calls with the RECORDED frames. */
internal class DeliveryHarness(val enrollment: EnrollmentScope = StateTestSupport.enrollment) {
    var delivery: EnrollmentDelivery = EnrollmentDelivery.begin(enrollment)
    var authority: RelayAuthority = RelayAuthority.fresh()
    val effects = mutableListOf<DeliveryEffect>()
    private var correlations = 0
    private val clock = StateTestSupport.clockAt(1_000_000_000L)
    var nowMs = 0L

    fun <R> apply(step: DeliveryStep<R>): R {
        delivery = step.delivery
        effects.addAll(step.effects)
        return step.result
    }

    fun now(): ServerInstant = StateTestSupport.instant(clock, 1_000_000_000L + nowMs * 1_000_000L)

    fun correlation(): CorrelationId = CorrelationId.of("d-%05d".format(++correlations))!!

    /** The binding the recorded `relay.events.read` request used, rebuilt from that request's own parameters. */
    fun bindingOf(read: RelayVectors.Case): Binding {
        val conversation =
            read.response["result"]?.jsonObject?.get("events")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content
                ?: StateTestSupport.attached().conversationId.value
        return Binding(
            BindingId.of(read.params["binding_id"]!!.jsonPrimitive.content)!!,
            ConversationId.of(conversation)!!,
            1,
            0,
            0,
            read.params["replay_epoch"]!!.jsonPrimitive.content,
            read.params["after_sequence"]!!.jsonPrimitive.content.toLong(),
            ExecutionState.IDLE
        )
    }

    /** Makes [binding] the current binding of this enrollment and starts its event session. */
    fun attach(binding: Binding): BindingToken {
        val step = authority.attach(enrollment, binding)
        authority = step.authority
        val refused = apply(delivery.attach(authority, step.token))
        check(refused == null) { "attach refused: $refused" }
        return step.token
    }

    fun planRead(limit: Int? = null): ReadPlan =
        (apply(delivery.planRead(authority, correlation(), limit)) as ReadPlanOutcome.Planned).plan

    fun readRefusal(): SessionRefusal =
        (apply(delivery.planRead(authority, correlation())) as ReadPlanOutcome.Refused).reason

    fun stage(plan: ReadPlan, case: RelayVectors.Case): StageOutcome =
        apply(delivery.stageEvents(authority, plan.ref, outcomeOf(plan.call, case)))

    fun staged(outcome: StageOutcome): StagedEvents = (outcome as StageOutcome.Staged).batch

    fun planOutbox(limit: Int? = null): OutboxReadPlan =
        (apply(delivery.planOutboxRead(correlation(), limit)) as OutboxPlanOutcome.Planned).plan

    fun stageOutbox(plan: OutboxReadPlan, case: RelayVectors.Case): OutboxStageOutcome =
        apply(delivery.stageOutbox(plan.ref, outcomeOf(plan.call, case)))

    fun stagedOutbox(outcome: OutboxStageOutcome): StagedOutbox =
        (outcome as OutboxStageOutcome.Staged).batch

    fun planAck(): AckPlan =
        (apply(delivery.planAck(correlation())) as OutboxPlanOutcome.Planned).plan

    companion object {
        /** The recorded response frame, decoded by the Task1 codec and interpreted by the planned call itself. */
        fun <R : Any> outcomeOf(call: RelayCall<R>, case: RelayVectors.Case): RelayOutcome<R> =
            call.interpret(
                CorrelationId.of(case.responseId!!)!!,
                RelayEnvelope.decodeFrame(case.wireText)
            )

        fun events(suffix: String): RelayVectors.Case =
            RelayVectors.forMethod(RelayMethod.EVENTS_READ).first { it.id.endsWith(suffix) }

        fun outbox(suffix: String): RelayVectors.Case =
            RelayVectors.forMethod(RelayMethod.OUTBOX_READ).first { it.id.endsWith(suffix) }

        fun ack(suffix: String): RelayVectors.Case =
            RelayVectors.forMethod(RelayMethod.OUTBOX_ACK).first { it.id.endsWith(suffix) }
    }
}
