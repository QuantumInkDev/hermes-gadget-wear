package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.Task
import dev.quantumink.hermesgadget.protocol.relay.TaskId
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRefreshCompletionTest {
    private fun attaching(timing: RelayTiming = RelayTiming(maxRetries = 1)): ControllerFixture =
        ControllerFixture(timing = timing).also {
            it.enable()
            it.connect()
            it.listAnd(Dto.summary())
            it.controller.select(Dto.conversation.value)
            it.run()
            it.answer("relay.conversation.attach", Dto.binding(1).toWire())
        }

    private fun tasks(fx: ControllerFixture, cursor: String? = null, selected: Boolean = false) {
        val task = Task(
            TaskId.of("00000000-0000-4000-8000-000000000003")!!,
            if (selected) Dto.conversation else Dto.otherConversation,
            Dto.admission,
            null,
            TaskState.WORKING,
            null,
            Dto.SERVER_TIME
        )
        fx.answer(
            "relay.tasks.list",
            TaskPage(if (selected) listOf(task) else emptyList(), cursor).toWire()
        )
    }

    private fun owed(fx: ControllerFixture): Set<String> =
        fx.store.reread().snapshot.deliveries.single().obligations.map { it.kind }.toSet()

    @Test fun selectedTaskOnPageTwoIsIncludedAfterEmptyCursoredProgress() {
        val fx = attaching()
        fx.answer("relay.history.page", Dto.history().toWire())
        tasks(fx, "page-two")
        assertEquals(2, fx.frames("relay.tasks.list").size)
        assertEquals(
            "page-two",
            fx.transport.next("relay.tasks.list").params["cursor"]!!.jsonPrimitive.content
        )
        tasks(fx, selected = true)
        assertEquals(1, fx.ui.tasksWorking)
        assertFalse(fx.ui.refreshOwed)
    }

    private fun taskFailure(kind: String) {
        val fx = attaching()
        fx.answer("relay.history.page", Dto.history().toWire())
        repeat(2) {
            val call = fx.transport.open("relay.tasks.list").firstOrNull()
            if (call != null) {
                when (kind) {
                    "busy" -> fx.transport.fail(call, "busy")
                    "timeout" -> fx.transport.timeout(call)
                    "invalid" -> fx.transport.reply(call, JsonObject(emptyMap()))
                    else -> {
                        call.answered = true
                        call.deliver(RelayCallResult.Refused(RelayRefusal.NOT_ADVERTISED))
                    }
                }
                fx.run()
                fx.advanceTime(fx.timing.backoffBaseMs)
            }
        }
        assertTrue("failed task snapshot remains owed", fx.ui.refreshOwed)
        assertEquals(setOf("TASKS"), owed(fx))
        assertNull("unknown snapshot must not display zero current tasks", fx.ui.tasksWorking)
        val restored = ControllerFixture(store = fx.store)
        restored.prepare()
        restored.connect()
        assertTrue(restored.ui.refreshOwed)
        assertTrue(restored.frames("relay.conversation.attach").isEmpty())
    }

    @Test fun busyExhaustionKeepsTaskRefreshOwedAcrossRestore() = taskFailure("busy")

    @Test fun timeoutKeepsTaskRefreshOwedAcrossRestore() = taskFailure("timeout")

    @Test fun invalidTaskReplyKeepsTaskRefreshOwedAcrossRestore() = taskFailure("invalid")

    @Test fun localRefusalKeepsTaskRefreshOwedAcrossRestore() = taskFailure("local")

    @Test fun cappedHistoryIsNotFulfilledBySuccessfulTasks() {
        val fx = attaching(RelayTiming(maxHistoryPages = 1))
        fx.answer("relay.history.page", Dto.history(next = "more-history").toWire())
        tasks(fx)
        assertTrue(fx.ui.historyIncomplete)
        assertTrue(fx.ui.refreshOwed)
        assertEquals(setOf("HISTORY"), owed(fx))
    }

    @Test fun historyRetryExhaustionIsNotFulfilledBySuccessfulTasks() {
        val fx = attaching()
        repeat(2) {
            fx.transport.timeout(fx.transport.next("relay.history.page"))
            fx.run()
            fx.advanceTime(fx.timing.backoffBaseMs)
        }
        tasks(fx)
        assertTrue(fx.ui.refreshOwed)
        assertEquals(setOf("HISTORY"), owed(fx))
    }

    @Test fun taskPageCapKeepsUnknownViewAndRetryCanFulfillIt() {
        val fx = attaching(RelayTiming(maxTaskPages = 1))
        fx.answer("relay.history.page", Dto.history().toWire())
        tasks(fx, "more")
        assertEquals(setOf("TASKS"), owed(fx))
        assertNull(fx.ui.tasksWorking)
        fx.controller.select(Dto.conversation.value) // the production Retry refresh action
        fx.run()
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        fx.answer("relay.history.page", Dto.history().toWire())
        tasks(fx, selected = true)
        assertFalse(fx.ui.refreshOwed)
        assertEquals(1, fx.ui.tasksWorking)
    }

    @Test fun repeatedTaskCursorStopsBoundedlyWithoutClaimingCompletion() {
        val fx = attaching()
        fx.answer("relay.history.page", Dto.history().toWire())
        tasks(fx, "same-cursor")
        tasks(fx, "same-cursor")
        assertEquals(2, fx.frames("relay.tasks.list").size)
        assertEquals(setOf("TASKS"), owed(fx))
        assertNull(fx.ui.tasksWorking)
    }

    @Test fun historyRequestBudgetStopsEmptyCursoredWorkWithAnOwedRefresh() {
        val fx = attaching(RelayTiming(maxHistoryRequests = 2))
        fx.answer("relay.history.page", Dto.history(next = "history-two").toWire())
        fx.answer("relay.history.page", Dto.history(next = "history-three").toWire())
        assertEquals(2, fx.frames("relay.history.page").size)
        tasks(fx)
        assertEquals(setOf("HISTORY"), owed(fx))
        assertTrue(fx.ui.historyIncomplete)
    }

    @Test fun taskBusyRetryUsesTheSameCursorAndCanFulfillTheSnapshot() {
        val fx = attaching()
        fx.answer("relay.history.page", Dto.history().toWire())
        tasks(fx, "page-two")
        val failed = fx.transport.next("relay.tasks.list")
        fx.transport.fail(failed, "busy")
        fx.run()
        fx.advanceTime(fx.timing.backoffBaseMs)
        assertEquals(failed.params, fx.transport.next("relay.tasks.list").params)
        tasks(fx, selected = true)
        assertFalse(fx.ui.refreshOwed)
        assertEquals(1, fx.ui.tasksWorking)
    }
}
