package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Info
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
