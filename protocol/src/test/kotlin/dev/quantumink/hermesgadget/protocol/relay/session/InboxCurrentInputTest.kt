package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxCurrentInputTest {
    private class Disk : InboxFile {
        var saved = InboxSnapshot()
        var unavailable = false
        var reads = 0
        var beforeRead: () -> Unit = {}
        override fun load(): InboxSnapshot {
            reads++
            beforeRead()
            if (unavailable) throw InboxFailure(InboxProblem.STORAGE)
            return InboxCodec.decode(InboxCodec.encode(saved))
        }
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != saved.revision) return InboxWrite.Stale
            saved = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class Owner {
        val steps = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResourceAsStream("/task5-inbox/needs-input-answer/session.json")
            ).bufferedReader().use {
                it.readText()
            }
        )
            .jsonObject["steps"]!!.jsonArray.map { it.jsonObject }.filter {
            it["device"] ==
                JsonPrimitive("watch")
        }
        fun method(s: JsonObject) = s["request"]!!.jsonObject["method"]!!.jsonPrimitive.content
        fun result(s: JsonObject) = s["response"]!!.jsonObject["result"]!!.jsonObject
        val info = Info.decode(result(steps.first { method(it) == "relay.info" }))
        val promptGets = steps.filter {
            method(it) == "relay.task.get" &&
                result(it)["prompt"] != null
        }
        val response = steps.single { method(it) == "relay.prompt.respond" }
        val entropy = RequestIdEntropy { bytes ->
            val id = UUID.fromString(
                response["request"]!!.jsonObject["params"]!!
                    .jsonObject["request_id"]!!.jsonPrimitive.content
            )
            bytes[0] = (id.mostSignificantBits ushr 8).toByte()
            bytes[1] =
                id.mostSignificantBits.toByte()
            for (i in 2..9) bytes[i] = (id.leastSignificantBits ushr (8 * (9 - i))).toByte()
        }
        val disk = Disk()
        val repository = InboxRepository(disk)
        var writer = 0L
        var onUpdate: (String) -> Unit = {}
        val f = ControllerFixture(
            inbox = repository,
            requestEntropy = entropy,
            writerEpoch = { writer },
            inboxUpdates = { _, status -> onUpdate(status) }
        )
        val namespace = HistoryNamespace.from(Seeds.record(f.identity, pinned = info.profileId))!!
        val handle: String
        init {
            val record = Seeds.record(f.identity, pinned = info.profileId)
            f.store.commit(Seeds.empty(ControllerSection(listOf(record))), 0)
            repository.configure(InboxProfile(namespace, "current", "Current", "Box"))
            val waiting = steps.first {
                method(it) == "relay.outbox.read" &&
                    result(it)["items"]!!.jsonArray.any { raw ->
                        raw.jsonObject["payload"]!!.jsonObject["task"]?.jsonObject?.get("state") ==
                            JsonPrimitive("needs_input")
                    }
            }
            val items = OutboxPage.decode(result(waiting)).items
            repository.accept(namespace, items, Dto.SERVER_TIME)
            handle =
                disk.load().copies.single {
                    (it.item.payload as? OutboxPayload.TaskChange.Relay)?.task?.state ==
                        TaskState.NEEDS_INPUT
                }.handle
            f.prepare()
            f.connect(info)
            f.answer(
                "relay.conversations.list",
                result(
                    steps.first {
                        method(it) ==
                            "relay.conversations.list"
                    }
                )
            )
        }
        fun get(): InboxInput? {
            var token: InboxInput? = null
            f.controller.getInboxInput(handle) { token = it }
            f.run()
            f.answer("relay.task.get", result(promptGets.last()))
            return token
        }
        fun attach() {
            f.controller.continueInbox(handle)
            f.run()
            val actual = steps.last { method(it) == "relay.conversation.attach" }
            assertEquals(
                actual["request"]!!.jsonObject["params"],
                f.transport.next("relay.conversation.attach").params
            )
            f.answer("relay.conversation.attach", result(actual))
            f.answer(
                "relay.history.page",
                result(
                    steps.first {
                        method(it) == "relay.history.page"
                    }
                )
            )
            f.answer(
                "relay.tasks.list",
                result(
                    steps.first {
                        method(it) == "relay.tasks.list" &&
                            it["device"] == JsonPrimitive("watch")
                    }
                )
            )
        }
    }

    @Test fun recoveredCopyAndListCannotAnswerButFreshBoundTokenRequiresSeparateDeliberateAction() {
        val h = Owner()
        val f = h.f
        assertTrue(h.info.advertises(RelayMethod.PROMPT_RESPOND))
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        val unbound = h.get()!!
        assertFalse(unbound.canAnswer)
        assertTrue(f.frames("relay.conversation.attach").isEmpty())
        h.attach()
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        val current = h.get()!!
        assertTrue(current.canAnswer)
        var oldAccepted = true
        f.controller.answerInboxInput(unbound, PromptAnswer.Choice("Continue")) {
            oldAccepted = it
        }
        f.run()
        assertFalse(oldAccepted)
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        var accepted = false
        f.controller.answerInboxInput(current, PromptAnswer.Choice("Continue")) { accepted = it }
        f.run()
        val sent = f.transport.next("relay.prompt.respond")
        assertEquals(h.response["request"]!!.jsonObject["params"], sent.params)
        f.transport.reply(sent, h.result(h.response))
        f.run()
        assertTrue(accepted)
        f.controller.answerInboxInput(current, PromptAnswer.Choice("Continue")) { assertFalse(it) }
        f.run()
        assertEquals(1, f.frames("relay.prompt.respond").size)
        val last = h.steps.last { h.method(it) == "relay.task.get" }
        var superseded: InboxInput? = current
        f.controller.getInboxInput(h.handle) { superseded = it }
        f.run()
        f.answer("relay.task.get", h.result(last))
        assertNull(superseded)
        assertEquals(1, f.frames("relay.prompt.respond").size)
    }

    @Test fun lateCurrentGetterAfterOptOutCannotPublishAnInputToken() {
        val h = Owner()
        val f = h.f
        val issued = ArrayList<InboxInput?>()
        f.controller.getInboxInput(h.handle) { issued.add(it) }
        f.run()
        val held = f.transport.next("relay.task.get")
        f.controller.configureInbox(h.disk.load().profiles.single().copy(enabled = false))
        f.run()
        f.transport.reply(held, h.result(h.promptGets.last()))
        f.run()
        assertTrue(issued.isEmpty())
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        assertTrue(f.frames("relay.conversation.attach").isEmpty())
        assertFalse(h.disk.load().profiles.single().enabled)
        assertTrue(h.disk.load().copies.isNotEmpty())
    }

    @Test fun unanswerableTokenRefusesBeforeUnavailableRouteStorage() {
        val h = Owner()
        val unbound = h.get()!!
        assertFalse(unbound.canAnswer)
        val reads = h.disk.reads
        h.disk.unavailable = true
        var completed = false
        h.f.controller.answerInboxInput(unbound, PromptAnswer.Choice("Continue")) {
            assertFalse(it)
            completed = true
        }
        h.f.run()
        assertTrue(completed)
        assertEquals(reads, h.disk.reads)
        assertTrue(h.f.frames("relay.prompt.respond").isEmpty())
    }

    private fun eligibleStorageFailure(
        answer: Boolean,
        replacement: Boolean = false,
        cancel: Boolean = false,
        writerReplacement: Boolean = false
    ) {
        val h = Owner()
        val f = h.f
        h.attach()
        val input = h.get()!!
        assertTrue("Control requires an actual eligible bound input", input.canAnswer)
        var refreshes = 0
        f.controller.refreshInbox {
            refreshes++
            assertEquals("Inbox storage unavailable", it)
        }
        f.run()
        val heldObserver = f.transport.next("relay.outbox.read")
        var completed = 0
        var newActionCompleted = 0
        if (replacement) {
            h.onUpdate = { status ->
                if (status == "Inbox storage unavailable") {
                    h.onUpdate = {}
                    // An actual new action reenters publication under the same writer.
                    f.controller.getInboxInput(h.handle) {
                        newActionCompleted++
                        assertNull(it)
                    }
                    f.run()
                }
            }
        }
        if (writerReplacement) {
            h.onUpdate = { status ->
                if (status == "Inbox storage unavailable") {
                    h.onUpdate = {}
                    h.writer++
                }
            }
        }
        fun cancelOnRead() {
            h.disk.beforeRead = {
                h.disk.beforeRead = {}
                f.controller.hide()
                f.run()
            }
        }
        val reads = h.disk.reads
        if (answer) {
            if (cancel) cancelOnRead()
            h.disk.unavailable = true
            f.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) {
                completed++
                assertFalse(it)
            }
            f.run()
        } else {
            f.controller.getInboxInput(h.handle) {
                completed++
                assertNull(it)
            }
            f.run()
            val getter = f.transport.next("relay.task.get")
            assertTrue("Getter admission route read already succeeded", h.disk.reads > reads)
            if (cancel) cancelOnRead()
            h.disk.unavailable = true
            f.transport.reply(getter, h.result(h.promptGets.last()))
            f.run()
        }
        assertEquals(
            "Eligible invocation completes once unless an actual new action or cancellation replaced it",
            if (replacement || cancel || writerReplacement) 0 else 1,
            completed
        )
        assertTrue("Fault reached the eligible route read", h.disk.reads > reads)
        assertEquals(if (replacement) 1 else 0, newActionCompleted)
        if (!cancel) {
            assertEquals("Inbox storage unavailable", f.controller.inboxStatus)
            assertEquals(if (writerReplacement) 0 else 1, refreshes)
        }
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        h.disk.unavailable = false
        val outbox = h.steps.first {
            h.method(it) == "relay.outbox.read" &&
                h.result(it)["items"]!!.jsonArray.isNotEmpty()
        }
        f.transport.reply(heldObserver, h.result(outbox))
        f.run()
        assertTrue(
            "Withdrawn observer cannot acknowledge a late page",
            f.frames("relay.outbox.ack").isEmpty()
        )
        f.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) { assertFalse(it) }
        f.run()
        assertTrue(f.frames("relay.prompt.respond").isEmpty())
        assertEquals(if (replacement || cancel || writerReplacement) 0 else 1, completed)
    }

    @Test fun eligibleGetterAcceptanceStorageFailureCompletesCurrentInvocationOnce() =
        eligibleStorageFailure(answer = false)

    @Test fun eligibleBoundAnswerStorageFailureCompletesCurrentInvocationOnce() =
        eligibleStorageFailure(answer = true)

    @Test fun getterStoragePublicationNewActionSuppressesOldCompletion() =
        eligibleStorageFailure(answer = false, replacement = true)

    @Test fun answerStoragePublicationNewActionSuppressesOldCompletion() =
        eligibleStorageFailure(answer = true, replacement = true)

    @Test fun getterRouteReadCancellationSuppressesOldCompletion() =
        eligibleStorageFailure(answer = false, cancel = true)

    @Test fun answerRouteReadCancellationSuppressesOldCompletion() =
        eligibleStorageFailure(answer = true, cancel = true)

    @Test fun getterStoragePublicationWriterReplacementSuppressesOldCompletion() =
        eligibleStorageFailure(answer = false, writerReplacement = true)

    @Test fun answerStoragePublicationWriterReplacementSuppressesOldCompletion() =
        eligibleStorageFailure(answer = true, writerReplacement = true)
}
