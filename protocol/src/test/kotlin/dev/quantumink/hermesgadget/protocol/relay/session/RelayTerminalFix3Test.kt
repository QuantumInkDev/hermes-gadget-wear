package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayConnectionHarness
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Existing producer-shaped terminal input; no new registered capture.
 * The Peer is entirely in-process; DirectConnection/RelayChannel final SEND are production owners.
 */
class RelayTerminalFix3Test {
    private class Owner(val retries: Int, retained: RetainedHistory? = null) : AutoCloseable {
        val net = RelayConnectionHarness(RelaySocketFixture())
        val scheduler = ManualScheduler()
        val store = MemoryStore()
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:fix3")
        val publications = java.util.concurrent.atomic.AtomicLong()
        val controller =
            RelayController(
                store,
                scheduler,
                net::nanosNow,
                Dto.scriptedEntropy,
                RelayTiming(maxRetries = retries),
                retainedHistory = retained
            ) {
                publications.incrementAndGet()
            }
        val peer get() = net.fixture.latest
        val ui get() = controller.currentState
        var durableSubmits = 0
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        init {
            net.fixture.onRelayAcceptance = { request ->
                if (request["method"]!!.jsonPrimitive.content == "relay.turn.submit") {
                    try {
                        val params = request["params"]!!.jsonObject
                        val turn = store.reread().snapshot.ledger.turns.single {
                            it.requestId ==
                                params["request_id"]!!.jsonPrimitive.content
                        }
                        check(turn.sent && turn.text == params["text"]!!.jsonPrimitive.content)
                        check(
                            turn.expiresAtMs ==
                                params["expires_at_ms"]!!.jsonPrimitive.content.toLong()
                        )
                        durableSubmits++
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
        }
        fun run() = scheduler.runAll()
        fun await(test: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                run()
                if (test()) return
                Thread.sleep(5)
            }
            error("owner condition was not reached")
        }
        fun take(method: String): JsonObject {
            await { peer.relayRequests.isNotEmpty() }
            val request = peer.nextRequest()
            assertEquals(method, request["method"]!!.jsonPrimitive.content)
            return request
        }
        fun reply(request: JsonObject, body: JsonObject) =
            peer.reply(request["id"]!!.jsonPrimitive.content, body)
        fun selected() {
            controller.prepare(identity)
            controller.setEnabled(true)
            controller.attachTransport(net.transport)
            run()
            net.connection.connect()
            net.awaitConnected()
            val info = take("relay.info")
            reply(info, Dto.info().toWire())
            val ready = net.awaitState<RelayChannelState.Ready>()
            controller.onChannel(ready, 1)
            run()
            reply(
                take("relay.conversations.list"),
                Dto.page(Dto.summary(Dto.otherConversation, "B")).toWire()
            )
            await { ui.conversations.isNotEmpty() }
            controller.select(Dto.otherConversation.value)
            run()
            reply(take("relay.conversation.attach"), Dto.binding(1, Dto.otherConversation).toWire())
            // Complete normal reads so the queue has only the deliberate cursored request when tested.
            reply(take("relay.history.page"), Dto.history().toWire())
            // The history callback must run on the owner before tasks is requested.
            await { peer.relayRequests.isNotEmpty() }
            reply(take("relay.tasks.list"), TaskPage(emptyList(), null).toWire())
            await { ui.tasksWorking == 0 && ui.canSend }
            assertEquals(Dto.otherConversation.value, ui.selected?.id)
        }
        fun cursor(): JsonObject {
            controller.searchOnline("needle", true)
            run()
            reply(
                take("relay.conversations.list"),
                Dto.page(Dto.summary(Dto.otherConversation, "B"), next = "tail").toWire()
            )
            await { ui.hasMore }
            controller.loadMoreConversations()
            run()
            return take("relay.conversations.list")
        }
        fun error(request: JsonObject, code: String) = peer.deliver(
            buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", request["id"]!!)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("snapshot_required", true)
                    }
                )
            }
        )
        fun spendTokens() {
            while (peer.relayTexts.size < 15) {
                val done = CountDownLatch(1)
                net.transport.call(
                    net.transport.correlation(),
                    RelayCalls.conversations(),
                    15_000
                ) {
                    done.countDown()
                }
                reply(take("relay.conversations.list"), Dto.page().toWire())
                check(done.await(5, TimeUnit.SECONDS))
            }
        }
        fun submitCount() = peer.relayTexts.count {
            Json.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content ==
                "relay.turn.submit"
        }
        override fun close() = net.close()
    }

    private val pending = object : RetainedHistory {
        override fun read(namespace: HistoryNamespace) = RetainedSnapshot(0)
        override fun capture(page: HistoryCapture) =
            RetainedSnapshot(1, threads = listOf(page.thread))
        override fun absent(namespace: HistoryNamespace, conversationId: String?) = false
        override fun evict(namespace: HistoryNamespace, conversationId: String?): Unit =
            throw HistoryFailure(HistoryProblem.STORAGE)
        override fun review(
            namespace: HistoryNamespace,
            conversationId: String?
        ): HistoryEraseReview = error("unused")
        override fun reviewRetention(namespace: HistoryNamespace, days: Int): HistoryEraseReview =
            error("unused")
        override fun erase(review: HistoryEraseReview, namespace: HistoryNamespace) =
            error("unused")
        override fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot =
            error("unused")
    }
    private fun seed(h: Owner) {
        val record = Seeds.record(h.identity)
        val namespace = HistoryNamespace.from(record)!!
        val seed = Seeds.withCaptureHistoryAndDelivery(h.identity).snapshot
        val own = EnrollmentDelivery.begin(Seeds.scope(h.identity)).snapshot()
        val full = DeliverySnapshot(
            own.scope,
            37,
            (1..4096).map {
                SeenKeySnapshot(
                    "canonical",
                    Dto.otherConversation.value,
                    "00000000-0000-4000-8000-%012d".format(it),
                    Dto.SERVER_TIME
                )
            },
            own.obligations,
            listOf("00000000-0000-4000-8000-000000090001")
        )
        val old = (1..64).map {
            HistoryEviction(
                namespace,
                "00000000-0000-4000-9000-%012d".format(it),
                EvictionKind.CACHE_ONLY
            )
        }
        val section = ControllerSection(
            listOf(record),
            evictions = old,
            emergencyDeletion = HistoryEviction(
                namespace,
                Dto.conversation.value,
                EvictionKind.DELETED
            )
        )
        val snapshot = RelayStateSnapshot(
            seed.version,
            seed.ledger,
            seed.histories,
            seed.deliveries + full,
            seed.captures
        )
        assertTrue(h.store.commit(RelayDocument(section, snapshot), 0) is StoreCommit.Committed)
    }
    private fun terminal(h: Owner, read: JsonObject) {
        val binding = Dto.binding(1, Dto.otherConversation)
        h.reply(
            read,
            Dto.events(
                binding,
                Dto.event(
                    7,
                    binding,
                    EventPayload.ConversationChanged(
                        ConversationSummary(
                            Dto.otherConversation,
                            null,
                            2,
                            false,
                            Dto.SERVER_TIME,
                            false
                        ),
                        true
                    )
                ),
                snapshotRequired = true
            ).toWire()
        )
        h.await { h.store.reread().section.enrollments.single().selectionDeleted }
    }
    private fun preserved(h: Owner, before: RelayDocument) {
        val after = h.store.reread()
        assertEquals(
            Dto.otherConversation,
            after.section.enrollments.single().selectedConversationId
        )
        assertEquals(Standing.ACTIVE, after.section.enrollments.single().standing)
        assertEquals(before.section.evictions, after.section.evictions)
        assertEquals(before.section.emergencyDeletion, after.section.emergencyDeletion)
        assertEquals(before.section.intents, after.section.intents)
        assertArrayEquals(
            (SnapshotCodec.encode(before) as EncodeResult.Encoded).bytes,
            (
                SnapshotCodec.encode(
                    RelayDocument(before.section, after.snapshot)
                ) as EncodeResult.Encoded
                ).bytes
        )
        assertFalse(h.ui.canSend)
        assertTrue(
            h.peer.relayTexts.none {
                Json.parseToJsonElement(
                    it
                ).jsonObject["method"]!!.jsonPrimitive.content.endsWith(".ack")
            }
        )
        assertTrue(h.failures.toString(), h.failures.isEmpty())
    }

    @Test fun fullSeenCurrentSendMatchesDurableStateAtPeerAcceptanceThenTerminalPreservesPending() {
        Owner(6, pending).use { h ->
            seed(h)
            h.selected()
            var accepted = false
            h.controller.send("current exact durable full-seen control") { accepted = it }
            h.run()
            assertTrue(accepted)
            h.take("relay.turn.submit")
            assertEquals(1, h.submitCount())
            assertEquals(1, h.durableSubmits)
            h.scheduler.advance(8_000)
            val read = h.take("relay.events.read")
            val before = h.store.reread()
            assertEquals(
                4096,
                before.snapshot.deliveries.single {
                    it.scope.enrollmentKey ==
                        h.identity.enrollmentLabel
                }.seen.size
            )
            terminal(h, read)
            preserved(h, before)
            assertEquals(1, h.submitCount())
        }
    }

    @Test fun fullSeenTerminalWithdrawsAPacedQueuedTurnBeforeFinalPeerSend() {
        Owner(6, pending).use { h ->
            seed(h)
            h.selected()
            h.scheduler.advance(8_000)
            val read = h.take("relay.events.read")
            h.spendTokens()
            var accepted = false
            h.controller.send("queued before a full-seen terminal") { accepted = it }
            h.run()
            assertTrue(accepted)
            assertEquals(0, h.submitCount())
            assertTrue(h.store.reread().snapshot.ledger.turns.single().sent)
            val before = h.store.reread()
            terminal(h, read)
            h.net.advance(1_000)
            val sentinel = h.net.transport.correlation()
            h.net.transport.call(sentinel, RelayCalls.conversations(), 15_000) {}
            h.await {
                h.peer.relayTexts.any {
                    Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content ==
                        sentinel.value
                }
            }
            assertEquals(0, h.submitCount())
            assertEquals(0, h.durableSubmits)
            preserved(h, before)
        }
    }
}
