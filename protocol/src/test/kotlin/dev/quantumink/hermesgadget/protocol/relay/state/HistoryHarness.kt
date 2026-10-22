package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.jsonPrimitive

/** Drives a [HistoryAssembly] like a controller: plan a page, feed the answer, record effects. */
internal class HistoryHarness(
    val scope: HistoryScope = HistoryScope(
        ConversationScope(StateTestSupport.enrollment, StateTestSupport.attached().conversationId)
    )
) {
    var assembly: HistoryAssembly = HistoryAssembly.begin(scope)
    val effects = mutableListOf<HistoryEffect>()

    fun <R> apply(step: HistoryStep<R>): R {
        assembly = step.assembly
        effects.addAll(step.effects)
        return step.result
    }

    fun plan(limit: Int? = null): PagePlan =
        (apply(assembly.planPage(limit)) as HistoryPlanOutcome.Planned).plan

    fun planRefusal(limit: Int? = null): HistoryPlanRefusal =
        (apply(assembly.planPage(limit)) as HistoryPlanOutcome.Refused).reason

    fun accept(plan: PagePlan, page: HistoryPage): PageOutcome =
        apply(assembly.accept(plan.request, page))

    /** Plans the next page and answers it with [page]. */
    fun feed(page: HistoryPage): PageOutcome = accept(plan(), page)

    fun error(
        plan: PagePlan,
        code: RelayErrorCode,
        snapshotRequired: Boolean = false
    ): PageOutcome = apply(assembly.onError(plan.request, RelayError(code, snapshotRequired)))

    fun accepted(outcome: PageOutcome): PageOutcome.Accepted = outcome as PageOutcome.Accepted

    fun invalidated(outcome: PageOutcome): HistoryInvalidation =
        (outcome as PageOutcome.Invalidated).reason

    companion object {
        fun id(n: Int): MessageId = MessageId.of("00000000-0000-4000-8000-%012d".format(n))!!

        fun fragment(
            n: Int,
            index: Int,
            count: Int,
            text: String,
            role: HistoryRole = HistoryRole.ASSISTANT,
            createdAtMs: Long = 1_000L + n
        ) = HistoryMessage(id(n), role, text, createdAtMs, index, count)

        fun single(n: Int, text: String = "message $n") = fragment(n, 0, 1, text)

        fun page(next: String?, vararg items: HistoryMessage) = HistoryPage(items.toList(), next)

        fun cursor(n: Int) = "cursor-%04d".format(n)

        /** The captured `relay.history.page` / `relay.existing.history.page` case whose id ends with [suffix]. */
        fun captured(method: RelayMethod, suffix: String): RelayVectors.Case =
            RelayVectors.forMethod(method).first { it.id.endsWith(suffix) }

        fun capturedPage(method: RelayMethod, suffix: String): HistoryPage =
            HistoryPage.decode(captured(method, suffix).result)

        fun requestedCursor(case: RelayVectors.Case): String? =
            case.params["cursor"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content

        fun scopeOf(
            case: RelayVectors.Case,
            source: HistorySource = HistorySource.RELAY
        ): HistoryScope = HistoryScope(
            ConversationScope(
                StateTestSupport.enrollment,
                ConversationId.of(case.params["conversation_id"]!!.jsonPrimitive.content)!!
            ),
            source
        )
    }
}
