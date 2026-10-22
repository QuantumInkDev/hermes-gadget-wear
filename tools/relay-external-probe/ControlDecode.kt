package probe

import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.Prompt
import dev.quantumink.hermesgadget.protocol.relay.PromptSource

/** Control: the decoder entry points (and FieldReader) are module-internal. */
fun main() {
    val reader = FieldReader.of("x", null, emptySet())
    println(Prompt.decode(reader, PromptSource.TASK_GET))
}
