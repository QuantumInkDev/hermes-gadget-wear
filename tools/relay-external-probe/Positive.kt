package probe

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayEncoded
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RequestId

/** Positive controls through public API only: genuine fetch works; event and notice stay non-actionable. */
fun main() {
    val request = RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!
    val fetched = Real.fetchedTask().prompt!!
    val actionable = fetched.asActionable() ?: error("fetched actionable prompt was refused")
    val answer = if (actionable.freeText) {
        PromptAnswer.Text(
            "go"
        )
    } else {
        PromptAnswer.Choice(actionable.choices.first())
    }
    val frame = RelayEnvelope.encodeRequest(
        CorrelationId.of("p1")!!,
        RelayCalls.respond(Real.binding, actionable, answer, request)
    ) as RelayEncoded.Frame
    println("POSITIVE fetched asActionable=non-null respond-frame-bytes=${frame.bytes}")

    val event = Real.eventPrompt()
    check(event.actionable) { "probe needs an actionable-looking event prompt" }
    check(event.asActionable() == null) { "event prompt became actionable" }
    println("POSITIVE event actionable-flag=true asActionable=null")

    check(Real.notice().asActionable() == null) { "notice became actionable" }
    println("POSITIVE notice asActionable=null")
}
