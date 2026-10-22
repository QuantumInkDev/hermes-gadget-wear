package probe

import dev.quantumink.hermesgadget.protocol.relay.ActionablePrompt

/** Control: the diagnostic mechanism works. ActionablePrompt's constructor was already module-internal. */
fun main() {
    val p = Real.eventPrompt()
    val forged = ActionablePrompt(p)
    println("FABRICATED ${forged.promptId}")
}
