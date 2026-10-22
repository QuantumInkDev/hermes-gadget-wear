package probe

import dev.quantumink.hermesgadget.protocol.relay.Prompt
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.PromptSource
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RequestId

/**
 * The review's I1 reconstruction, written exactly as an outside consumer would: rebuild a real EVENT prompt with a
 * caller-chosen TASK_GET provenance, obtain an ActionablePrompt and pass it to respond().
 * Valid Kotlin against the public API of fa2c1f3; it must NOT compile once the constructor is not public.
 */
fun main() {
    val p = Real.eventPrompt()
    val forged = Prompt(
        p.promptId, p.taskId, p.kind, p.text, p.choices, p.generation, p.expiresAtMs, p.deadline,
        true, p.freeText, null, PromptSource.TASK_GET
    )
    val actionable = forged.asActionable() ?: error("forged prompt was not actionable")
    val call = RelayCalls.respond(
        Real.binding,
        actionable,
        PromptAnswer.Text("approve"),
        RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!
    )
    println(
        "FABRICATED actionable prompt from an EVENT prompt; respond built for ${call.method.wire}"
    )
}
