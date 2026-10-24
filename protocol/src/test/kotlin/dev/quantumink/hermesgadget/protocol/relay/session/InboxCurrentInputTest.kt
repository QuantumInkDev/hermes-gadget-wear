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
        override fun load() = InboxCodec.decode(InboxCodec.encode(saved))
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
        val f = ControllerFixture(inbox = repository, requestEntropy = entropy)
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
}
