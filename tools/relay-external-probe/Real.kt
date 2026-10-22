package probe

import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.BindingRef
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.Prompt
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.Task
import dev.quantumink.hermesgadget.protocol.relay.TaskId

/** Only PUBLIC API of the protocol module: how an outside consumer obtains real decoded prompts. */
object Real {
    val binding = BindingRef(BindingId.of("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")!!, 3)

    /** A prompt decoded from a recorded `relay.task.get` reply (genuinely fetched, actionable). */
    fun fetchedTask(): Task {
        val call = RelayCalls.task(TaskId.of(Frames.FETCHED_TASK_ID)!!)
        val outcome = call.interpret(
            CorrelationId.of(Frames.FETCHED_CORRELATION)!!,
            RelayEnvelope.decodeFrame(Frames.FETCHED)
        )
        return (outcome as? RelayOutcome.Success)?.value
            ?: error("recorded task.get did not decode")
    }

    /** An actionable-looking prompt decoded from a recorded `relay.events.read` reply (an EVENT, never a fetch). */
    fun eventPrompt(): Prompt {
        val call = RelayCalls.events(binding, "e", 0)
        val outcome = call.interpret(
            CorrelationId.of(Frames.EVENT_CORRELATION)!!,
            RelayEnvelope.decodeFrame(Frames.EVENT)
        )
        val page: EventsPage =
            (outcome as? RelayOutcome.Success)?.value ?: error("recorded events did not decode")
        return page.events.map {
            it.payload
        }.filterIsInstance<EventPayload.PromptShown>().first().prompt
    }

    /** A recorded `too_large` notice (never actionable). */
    fun notice(): Prompt {
        val call = RelayCalls.task(TaskId.of(Frames.NOTICE_TASK_ID)!!)
        val outcome = call.interpret(
            CorrelationId.of(Frames.NOTICE_CORRELATION)!!,
            RelayEnvelope.decodeFrame(Frames.NOTICE)
        )
        return (
            (outcome as? RelayOutcome.Success)?.value ?: error(
                "recorded notice did not decode"
            )
            ).prompt!!
    }
}
