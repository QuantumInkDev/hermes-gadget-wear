package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Real registered response bodies, actual phone cache/aggregate ingestion and matching server ACK
 * acceptance.
 */
@RunWith(Parameterized::class)
class RegisteredInboxCaptureTest(private val scenario: String) {
    private class Disk : InboxFile {
        var saved = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(saved))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != saved.revision) return InboxWrite.Stale
            saved = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private fun json(name: String) = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResourceAsStream("/task5-inbox/$scenario/$name")
        ).bufferedReader().use {
            it.readText()
        }
    )
    private fun method(step: JsonObject) =
        step["request"]!!.jsonObject["method"]!!.jsonPrimitive.content
    private fun result(step: JsonObject) = step["response"]!!.jsonObject["result"]!!.jsonObject

    @Test fun phoneIngestsConfirmedCopiesBeforeExactRecordedAckAcceptance() {
        val session = json("session.json").jsonObject
        val steps = session["steps"]!!.jsonArray.map { it.jsonObject }.filter {
            it["device"]!!.jsonPrimitive.content == "watch"
        }
        val infoStep = steps.last { method(it) == "relay.info" }
        val info = Info.decode(result(infoStep))
        val disk = Disk()
        val repository = InboxRepository(disk)
        val store = MemoryStore()
        val f = ControllerFixture(store = store, inbox = repository)
        val record = Seeds.record(f.identity, pinned = info.profileId)
        assertTrue(
            store.commit(Seeds.empty(ControllerSection(listOf(record))), 0) is StoreCommit.Committed
        )
        val owner = HistoryNamespace.from(record)!!
        repository.configure(
            InboxProfile(
                owner,
                "registered",
                "Captured profile",
                "Registered box",
                existingTasks = true,
                existingAvailable = true
            )
        )
        val readSteps = steps.filter {
            method(it) == "relay.outbox.read" &&
                it["response"]!!.jsonObject["result"] != null
        }
        val recorded = if (scenario in
            setOf("existing-withdrawal", "expiry-gap")
        ) {
            readSteps.last()
        } else {
            readSteps.lastOrNull { result(it)["items"]!!.jsonArray.isNotEmpty() }
                ?: readSteps.last()
        }
        if (scenario == "existing-withdrawal") {
            // Exact cache-first crash preimage. No old aggregate/ACK fact is invented.
            val earlier = readSteps.first { result(it)["items"]!!.jsonArray.isNotEmpty() }
            repository.accept(
                owner,
                OutboxPage.decode(result(earlier)).items,
                Dto.SERVER_TIME,
                info.serverTimeMs!!
            )
        }
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(info)
        val expectedItems = OutboxPage.decode(result(recorded)).items
        var observedAck = false
        var ackAttempts = 0
        f.transport.onSend = { sent ->
            if (sent.method == "relay.outbox.ack") {
                val ids = sent.params["delivery_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue(
                    ids.all { id ->
                        disk.load().copies.any {
                            it.namespace == owner &&
                                it.item.deliveryId.value == id
                        }
                    }
                )
                assertEquals(ids, store.reread().snapshot.deliveries.single().pendingAcks)
                assertTrue(f.controller.currentState.identity == f.identity)
                observedAck = true
                ackAttempts++
            }
        }
        f.answer("relay.outbox.read", result(recorded))
        for (item in expectedItems) {
            assertEquals(
                item.toWire(),
                disk.load().copies.single {
                    it.item.deliveryId ==
                        item.deliveryId
                }.item.toWire()
            )
        }
        if (expectedItems.isNotEmpty()) {
            var ack = f.transport.next("relay.outbox.ack")
            val firstParams = ack.params
            val before = disk.load().copies.map {
                Triple(it.handle, it.receivedAtMs, it.item.expiresAtMs)
            }
            if (scenario == "completed-lost-ack") {
                ack.answered = true
                ack.deliver(RelayCallResult.TimedOut)
                f.run()
                assertTrue(store.reread().snapshot.deliveries.single().pendingAcks.isNotEmpty())
                f.controller.refreshInbox()
                f.run()
                ack = f.transport.next("relay.outbox.ack")
                assertEquals(firstParams, ack.params)
                assertEquals(
                    before,
                    disk.load().copies.map {
                        Triple(it.handle, it.receivedAtMs, it.item.expiresAtMs)
                    }
                )
            }
            val match = steps.first {
                method(it) == "relay.outbox.ack" &&
                    it["response_accepted"] == JsonPrimitive(true) &&
                    it["request"]!!.jsonObject["params"] == ack.params
            }
            assertEquals(ack.params["delivery_ids"], result(match)["acknowledged"])
            f.transport.reply(ack, result(match))
            f.run()
            assertTrue(observedAck)
            assertTrue(store.reread().snapshot.deliveries.single().pendingAcks.isEmpty())
        } else {
            assertFalse(observedAck)
        }
        // Complete actual phone read-only refresh using only recorded current response bodies.
        var delivered = 0
        while (true) {
            val next = f.transport.sent.firstOrNull { !it.answered } ?: break
            val captured = steps.lastOrNull {
                method(it) == next.method &&
                    (it["request"]!!.jsonObject["params"] ?: buildJsonObject {}) == next.params
            }
                ?: break
            assertTrue(++delivered <= 64)
            val response = captured["response"]!!.jsonObject
            if (response["result"] != null) {
                f.transport.reply(next, response["result"]!!.jsonObject)
            } else {
                next.frame(
                    buildJsonObject {
                        put("id", next.correlation.value)
                        put("error", response["error"]!!)
                    }
                ).also { next.answered = true }
            }
            f.run()
        }
        assertFalse(
            f.transport.methods().any {
                it in
                    listOf(
                        "relay.conversation.attach",
                        "relay.conversation.create",
                        "relay.turn.submit",
                        "relay.prompt.respond",
                        "relay.voice.speak"
                    )
            }
        )
        if (scenario == "existing-withdrawal") {
            assertTrue(info.advertises(RelayMethod.EXISTING_TASKS_LIST))
            assertEquals(1, disk.load().unavailable.size)
            assertNull(InboxRoute.resolve(disk.load(), disk.load().copies.single().handle))
            assertEquals(Standing.ACTIVE, store.reread().section.enrollments.single().standing)
        }
        if (scenario == "completed-lost-ack") {
            assertTrue(
                steps.any {
                    method(it) == "relay.outbox.ack" &&
                        it["response_accepted"] == JsonPrimitive(false)
                }
            )
            assertEquals(expectedItems.size, disk.load().copies.size)
            assertEquals(2, ackAttempts)
        }
    }
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            "completed-lost-ack", "interrupted", "cancelled", "unknown", "needs-input", "quota-gap",
            "expiry-gap", "existing-current-superseded", "existing-withdrawal", "needs-input-answer"
        ).map {
            arrayOf(it)
        }
    }
}
