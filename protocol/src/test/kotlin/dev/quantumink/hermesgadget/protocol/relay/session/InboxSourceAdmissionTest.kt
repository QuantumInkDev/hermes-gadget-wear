package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ExistingTaskPage
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxSourceAdmissionTest {
    private class Disk : InboxFile {
        var s = InboxSnapshot()
        var failReadAfterWrite = false
        var unreadable = false
        override fun load(): InboxSnapshot {
            if (unreadable) throw InboxFailure(InboxProblem.STORAGE)
            return InboxCodec.decode(InboxCodec.encode(s))
        }
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != s.revision) return InboxWrite.Stale
            s = InboxCodec.decode(InboxCodec.encode(next))
            if (failReadAfterWrite) unreadable = true
            return InboxWrite.Committed
        }
    }
    private class Owner {
        val steps = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResourceAsStream(
                    "/task5-inbox/existing-current-superseded/session.json"
                )
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
        val info = Info.decode(result(steps.last { method(it) == "relay.info" }))
        val page = result(
            steps.last {
                method(it) == "relay.outbox.read" &&
                    result(it)["items"]!!.jsonArray.isNotEmpty()
            }
        )
        val d = Disk()
        val r = InboxRepository(d)
        val f = ControllerFixture(inbox = r)
        val n = HistoryNamespace.from(Seeds.record(f.identity, pinned = info.profileId))!!
        init {
            f.store.commit(
                Seeds.empty(
                    ControllerSection(listOf(Seeds.record(f.identity, pinned = info.profileId)))
                ),
                0
            )
            r.configure(
                InboxProfile(
                    n,
                    "profile",
                    "Profile",
                    "Box",
                    existingTasks = true,
                    existingAvailable = true
                )
            )
            f.prepare()
            f.controller.observeInboxOnly(true)
            f.run()
            f.connect(info)
        }
    }

    @Test fun existingOptOutRetainsOwedBodiesButCannotRetryAckUntilSourceReadmission() {
        val h = Owner()
        val f = h.f
        f.answer("relay.outbox.read", h.page)
        assertTrue(h.d.s.copies.any { it.existing })
        f.transport.timeout(f.transport.next("relay.outbox.ack"))
        f.run()
        val ids = f.store.reread().snapshot.deliveries.single().pendingAcks
        h.f.controller.configureInbox(h.d.s.profiles.single().copy(existingTasks = false))
        f.run()
        f.controller.refreshInbox()
        f.run()
        assertEquals(1, f.frames("relay.outbox.ack").size)
        assertEquals(ids, f.store.reread().snapshot.deliveries.single().pendingAcks)
        assertTrue(h.d.s.copies.isNotEmpty())
        assertEquals(Standing.ACTIVE, f.store.reread().section.enrollments.single().standing)
        f.controller.configureInbox(h.d.s.profiles.single().copy(existingTasks = true))
        f.run()
        f.controller.refreshInbox()
        f.run()
        assertEquals(2, f.frames("relay.outbox.ack").size)
        assertEquals(
            f.frames("relay.outbox.ack").first().params,
            f.frames("relay.outbox.ack").last().params
        )
    }

    @Test fun ackAuthorityDenialWithdrawsWholeEnrollmentBeforeFurtherObservation() {
        val h = Owner()
        val f = h.f
        f.answer("relay.outbox.read", h.page)
        f.answerError("relay.outbox.ack", "permission_denied")
        assertEquals(Standing.STOPPED, f.store.reread().section.enrollments.single().standing)
        f.controller.refreshInbox()
        f.run()
        assertEquals(1, f.frames("relay.outbox.ack").size)
        assertEquals(1, f.frames("relay.outbox.read").size)
    }

    @Test fun failedAuthenticatedRereadBetweenAggregateAndAckWithdrawsObserverWithoutLosingDebt() {
        val h = Owner()
        val f = h.f
        h.d.failReadAfterWrite = true
        f.answer("relay.outbox.read", h.page)
        assertTrue(h.d.s.copies.isNotEmpty())
        val ids = f.store.reread().snapshot.deliveries.single().pendingAcks
        assertTrue(ids.isNotEmpty())
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
        assertEquals("Inbox storage unavailable", f.controller.inboxStatus)
        h.d.failReadAfterWrite = false
        h.d.unreadable = false
        f.controller.refreshInbox()
        f.run()
        assertEquals(1, f.frames("relay.outbox.ack").size)
        assertEquals(ids, f.store.reread().snapshot.deliveries.single().pendingAcks)
    }

    @Test
    fun actualControllerSeparatesEqualTaskIdsAcrossOriginsAndRestoresSeenDuplicateBodyBeforeAck() {
        fun captured(name: String): List<JsonObject> = Json.parseToJsonElement(
            requireNotNull(javaClass.getResourceAsStream("/task5-inbox/$name/session.json"))
                .bufferedReader().use { it.readText() }
        ).jsonObject["steps"]!!.jsonArray.map { it.jsonObject }.filter {
            it["device"] == JsonPrimitive("watch")
        }
        fun result(step: JsonObject) = step["response"]!!.jsonObject["result"]!!.jsonObject
        fun changes(steps: List<JsonObject>) = steps.filter {
            it["request"]!!.jsonObject["method"] == JsonPrimitive("relay.outbox.read")
        }.flatMap { OutboxPage.decode(result(it)).items }.filterIsInstance<OutboxItem.Change>()
        val existingSteps = captured("existing-current-superseded")
        val info = Info.decode(
            result(
                existingSteps.last {
                    it["request"]!!.jsonObject["method"] == JsonPrimitive("relay.info")
                }
            )
        )
        val relayCopy = changes(captured("completed-lost-ack")).first {
            it.payload is OutboxPayload.TaskChange.Relay
        }
        val source = changes(existingSteps).first {
            it.payload is OutboxPayload.TaskChange.Existing
        }
        val relayTask = (relayCopy.payload as OutboxPayload.TaskChange.Relay).task
        val original = (source.payload as OutboxPayload.TaskChange.Existing).task
        // Valid controlled collision, NOT a claimed producer emission of equal UUIDs. Keep all
        // other actual existing source/provenance/nullables unchanged; frozen resources stay exact.
        val twin = original.copy(
            taskId = relayTask.taskId,
            conversationId = relayTask.conversationId
        )
        val existingCopy = OutboxItem.Change(
            source.deliveryId,
            relayCopy.conversationId,
            source.eventId,
            source.createdAtMs,
            source.expiresAtMs,
            OutboxPayload.TaskChange.Existing(twin, source.payload.reference)
        )
        assertTrue(relayCopy.deliveryId != existingCopy.deliveryId)
        assertTrue(relayCopy.eventId != existingCopy.eventId)
        val d = Disk()
        val r = InboxRepository(d)
        val f = ControllerFixture(inbox = r)
        val record = Seeds.record(f.identity, pinned = info.profileId)
        val section = ControllerSection(listOf(record))
        val empty = Seeds.empty(section).snapshot
        val delivery = EnrollmentDelivery.begin(Seeds.scope(f.identity, info.profileId)).snapshot()
        val seenOnly = DeliverySnapshot(
            delivery.scope,
            0,
            listOf(
                SeenKeySnapshot(
                    "canonical",
                    relayCopy.conversationId.value,
                    relayCopy.eventId.value,
                    Dto.SERVER_TIME - 1000
                )
            ),
            emptyList(),
            emptyList()
        )
        f.store.commit(
            RelayDocument(
                section,
                RelayStateSnapshot(
                    empty.version,
                    empty.ledger,
                    empty.histories,
                    listOf(seenOnly),
                    empty.captures
                )
            ),
            0
        )
        val n = HistoryNamespace.from(record)!!
        r.configure(
            InboxProfile(
                n,
                "profile",
                "Profile",
                "Box",
                existingTasks = true,
                existingAvailable = true
            )
        )
        // A prior authenticated Relay projection remains retained when this current Info lacks
        // global Relay task listing. The actual opted-in existing refresh must not replace it.
        r.project(n, relay = listOf(relayTask), at = Dto.SERVER_TIME - 1000)
        assertTrue(d.load().copies.isEmpty())
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(info)
        var atSend = false
        f.transport.onSend = { call ->
            if (call.method == "relay.outbox.ack") {
                assertEquals(2, d.load().copies.size)
                assertEquals(
                    relayCopy.toWire(),
                    d.load().copies.single {
                        it.item.deliveryId == relayCopy.deliveryId
                    }.item.toWire()
                )
                assertEquals(2, f.store.reread().snapshot.deliveries.single().pendingAcks.size)
                atSend = true
            }
        }
        f.answer("relay.outbox.read", OutboxPage(listOf(relayCopy, existingCopy), 0).toWire())
        assertTrue(atSend)
        val ids = f.store.reread().snapshot.deliveries.single().pendingAcks
        f.answer(
            "relay.outbox.ack",
            buildJsonObject {
                put("acknowledged", JsonArray(ids.map { JsonPrimitive(it) }))
            }
        )
        assertTrue(f.frames("relay.tasks.list").isEmpty())
        f.answer("relay.conversations.list", Dto.page().toWire())
        f.answer("relay.existing.tasks.list", ExistingTaskPage(listOf(twin), null).toWire())
        val tasks = d.load().tasks
        assertEquals(2, tasks.size)
        assertEquals(1, tasks.map { it.id }.distinct().size)
        assertEquals(1, tasks.map { it.conversation }.distinct().size)
        assertEquals(2, tasks.map { it.handle }.distinct().size)
        assertEquals(1, tasks.count { it.existing != null })
        assertEquals(
            original.provenance,
            tasks.single {
                it.existing != null
            }.existing!!.provenance
        )
        for (task in tasks) assertTrue(InboxRoute.resolve(d.load(), task.handle) != null)
        assertTrue(f.frames("relay.existing.list").isEmpty())
        assertTrue(f.frames("relay.conversation.attach").isEmpty())
        assertTrue(f.frames("relay.turn.submit").isEmpty())
    }
}
