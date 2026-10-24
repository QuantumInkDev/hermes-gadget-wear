package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannel
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayConnectionHarness
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real DirectConnection/RelayChannel pacing and Peer acceptance; software stores, no TCP/native
 * claim.
 */
class InboxPacedAckTest {
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(state))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (state.revision != base) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class Owner : AutoCloseable {
        val net = RelayConnectionHarness(RelaySocketFixture())
        val scheduler = ManualScheduler()
        val store = MemoryStore()
        val disk = Disk()
        val repository = InboxRepository(disk)
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:paced-inbox")
        val document = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResourceAsStream("/task5-inbox/completed-lost-ack/session.json")
            ).bufferedReader().use {
                it.readText()
            }
        ).jsonObject
        val steps = document["steps"]!!.jsonArray.map { it.jsonObject }.filter {
            it["device"] ==
                JsonPrimitive("watch")
        }
        val info = Info.decode(
            steps.last {
                method(it) == "relay.info"
            }["response"]!!.jsonObject["result"]!!.jsonObject
        )
        val page = steps.last {
            method(it) == "relay.outbox.read" &&
                it["response"]!!.jsonObject["result"]!!.jsonObject["items"]!!.jsonArray.isNotEmpty()
        }["response"]!!.jsonObject["result"]!!.jsonObject
        val owner = HistoryNamespace.from(Seeds.record(identity, pinned = info.profileId))!!
        val failures = CopyOnWriteArrayList<Throwable>()
        var acknowledgements = 0
        val controller = RelayController(
            store,
            scheduler,
            net::nanosNow,
            Dto.scriptedEntropy,
            inbox = repository,
            localMillis = { Dto.SERVER_TIME }
        ) {}
        val peer get() = net.fixture.latest
        init {
            store.commit(
                Seeds.empty(
                    ControllerSection(listOf(Seeds.record(identity, pinned = info.profileId)))
                ),
                0
            )
            repository.configure(InboxProfile(owner, "actual", "Actual", "Box"))
            net.fixture.onRelayAcceptance =
                { request ->
                    if (method(request, false) == "relay.outbox.ack") {
                        try {
                            val ids = request["params"]!!.jsonObject["delivery_ids"]!!
                                .jsonArray.map {
                                    it.jsonPrimitive.content
                                }
                            check(ids == store.reread().snapshot.deliveries.single().pendingAcks)
                            check(
                                ids.all { id ->
                                    disk.load().copies.any { it.item.deliveryId.value == id }
                                }
                            )
                            acknowledgements++
                        } catch (t: Throwable) {
                            failures.add(t)
                        }
                    }
                }
        }
        fun method(value: JsonObject, step: Boolean = true) =
            (if (step) value["request"]!!.jsonObject else value)["method"]!!.jsonPrimitive.content
        fun run() = scheduler.runAll()
        fun await(test: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                run()
                if (test()) return
                Thread.sleep(5)
            }
            error("Actual inbox owner condition not reached")
        }
        fun next(method: String): JsonObject {
            await { peer.relayRequests.isNotEmpty() }
            return peer.nextRequest().also { assertEquals(method, this.method(it, false)) }
        }
        fun reply(request: JsonObject, value: JsonObject) =
            peer.reply(request["id"]!!.jsonPrimitive.content, value)
        fun staged(): JsonObject {
            controller.prepare(identity)
            controller.observeInboxOnly(true)
            controller.attachTransport(net.transport)
            run()
            net.connection.connect()
            net.awaitConnected()
            reply(next("relay.info"), info.toWire())
            controller.onChannel(net.awaitState<RelayChannelState.Ready>(), 1)
            val outbox = next("relay.outbox.read")
            while (peer.relayTexts.size < 15) {
                val done = CountDownLatch(1)
                net.transport.call(
                    net.transport.correlation(),
                    RelayCalls.conversations(),
                    15_000
                ) {
                    done.countDown()
                }
                reply(next("relay.conversations.list"), Dto.page().toWire())
                check(done.await(5, TimeUnit.SECONDS))
            }
            reply(outbox, page)
            await {
                store.reread().snapshot.deliveries.singleOrNull()?.pendingAcks?.isNotEmpty() ==
                    true
            }
            assertEquals(0, acknowledgements)
            return outbox
        }
        fun releasePacing() {
            net.advance(1000)
            val sentinel = net.transport.correlation()
            net.transport.call(sentinel, RelayCalls.conversations(), 15_000) {}
            await {
                peer.relayTexts.any {
                    Json.parseToJsonElement(it).jsonObject["id"] ==
                        JsonPrimitive(sentinel.value)
                }
            }
        }
        override fun close() = net.close()
    }

    @Test fun currentPacedAckMatchesConfirmedCopyAndAggregateAtActualPeerAcceptance() {
        Owner().use { h ->
            h.staged()
            h.releasePacing()
            assertEquals(1, h.acknowledgements)
            assertTrue(h.failures.toString(), h.failures.isEmpty())
        }
    }

    @Test fun sourceOptOutWithdrawsOnlyInboxAckBeforeItsPacedFinalSend() {
        Owner().use { h ->
            h.staged()
            h.controller.configureInbox(h.disk.load().profiles.single().copy(enabled = false))
            h.run()
            assertFalse(h.disk.load().profiles.single().enabled)
            h.releasePacing()
            assertEquals(
                "Disabled inbox cannot release a formerly queued ACK",
                0,
                h.acknowledgements
            )
            assertTrue(h.store.reread().snapshot.deliveries.single().pendingAcks.isNotEmpty())
            assertTrue(h.disk.load().copies.isNotEmpty())
            assertTrue(h.failures.toString(), h.failures.isEmpty())
        }
    }
}
