package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayConnectionHarness
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
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

/** Combined errors are valid constructed contract inputs, not newly captured producer emissions.
 * The Peer is entirely in-process; DirectConnection/RelayChannel final SEND are production owners.
 */
class RelayAuthorityFix2Test {
    private class Owner(val retries: Int, retained: RetainedHistory? = null) : AutoCloseable {
        val net = RelayConnectionHarness(RelaySocketFixture())
        val scheduler = ManualScheduler()
        val store = MemoryStore()
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:fix2")
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

    private fun queued(code: String, retries: Int) {
        Owner(retries).use { h ->
            h.selected()
            val tail = h.cursor()
            h.spendTokens()
            var accepted = false
            h.controller.send("queued before the authority verdict") { accepted = it }
            h.run()
            assertTrue("valid current turn must reach the durable queue boundary", accepted)
            assertTrue(h.store.reread().snapshot.ledger.turns.single().sent)
            assertEquals(0, h.submitCount())
            val before = h.publications.get()
            h.error(tail, code)
            h.await { h.publications.get() > before }
            h.net.advance(1_000)
            if (h.ui.phase == RelayPhase.REVIEW_REQUIRED) {
                // Profile refresh invalidates old Ready work.
                h.await { h.peer.relayRequests.isNotEmpty() }
            } else {
                val sentinel = h.net.transport.correlation()
                h.net.transport.call(sentinel, RelayCalls.conversations(), 15_000) {}
                h.await {
                    h.peer.relayTexts.any {
                        Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content ==
                            sentinel.value
                    }
                }
            }
            assertEquals("the queued turn must fail the final SEND fence", 0, h.submitCount())
            assertEquals(0, h.durableSubmits)
            val standing = if (code ==
                "permission_denied"
            ) {
                Standing.STOPPED
            } else {
                Standing.REVIEW_REQUIRED
            }
            assertEquals(standing, h.store.reread().section.enrollments.single().standing)
            assertFalse(h.ui.canSend)
            assertEquals("needle", h.ui.onlineQuery)
            assertEquals(true, h.ui.bookmarkFilter)
            assertTrue(h.failures.toString(), h.failures.isEmpty())
        }
    }

    @Test fun denialBeforeCursorRestartWithdrawsAQueuedFinalSend() = queued("permission_denied", 6)

    @Test fun denialAtRetryExhaustionWithdrawsAQueuedFinalSend() = queued("permission_denied", 0)

    @Test fun profileBeforeCursorRestartWithdrawsAQueuedFinalSend() = queued("profile_changed", 6)

    @Test fun profileAtRetryExhaustionWithdrawsAQueuedFinalSend() = queued("profile_changed", 0)

    @Test fun currentSelectedBStillAcceptsAnExactlyCommittedTurnAtPeerSend() {
        Owner(6).use { h ->
            h.selected()
            var accepted = false
            h.controller.send("valid current B") { accepted = it }
            h.run()
            assertTrue(accepted)
            h.take("relay.turn.submit")
            assertEquals(1, h.submitCount())
            assertEquals(1, h.durableSubmits)
            assertTrue(h.failures.toString(), h.failures.isEmpty())
        }
    }

    @Test fun fullCapacityBoundDeletionEventPreservesPendingSectionsAndEmitsNoUnrelatedAck() {
        val pending = object : RetainedHistory {
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
            override fun reviewRetention(
                namespace: HistoryNamespace,
                days: Int
            ): HistoryEraseReview = error("unused")
            override fun erase(review: HistoryEraseReview, namespace: HistoryNamespace) =
                error("unused")
            override fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot =
                error("unused")
        }
        Owner(6, pending).use { h ->
            val record = Seeds.record(h.identity)
            val owner = HistoryNamespace.from(record)!!
            val old = (1..64).map {
                HistoryEviction(
                    owner,
                    "00000000-0000-4000-8000-%012d".format(it),
                    EvictionKind.CACHE_ONLY
                )
            }
            val emergency = HistoryEviction(owner, Dto.conversation.value, EvictionKind.DELETED)
            val seed = Seeds.withCaptureHistoryAndDelivery(h.identity)
            assertTrue(
                h.store.commit(
                    RelayDocument(
                        ControllerSection(
                            listOf(record),
                            evictions = old,
                            emergencyDeletion = emergency
                        ),
                        seed.snapshot
                    ),
                    0
                ) is StoreCommit.Committed
            )
            h.selected()
            var accepted = false
            h.controller.send("pending B remains exact after its deletion observation") {
                accepted =
                    it
            }
            h.run()
            assertTrue(accepted)
            h.take("relay.turn.submit")
            val before = h.store.reread()
            h.scheduler.advance(8_000)
            val events = h.take("relay.events.read")
            val binding = Dto.binding(1, Dto.otherConversation)
            h.reply(
                events,
                Dto.events(
                    binding,
                    Dto.event(
                        1,
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
                    )
                ).toWire()
            )
            h.await { h.store.reread().section.enrollments.single().selectionDeleted }
            val after = h.store.reread()
            assertEquals(
                Dto.otherConversation,
                after.section.enrollments.single().selectedConversationId
            )
            assertEquals(old, after.section.evictions)
            assertEquals(emergency, after.section.emergencyDeletion)
            assertEquals(before.snapshot.ledger.turns, after.snapshot.ledger.turns)
            assertEquals(before.snapshot.captures.records, after.snapshot.captures.records)
            assertArrayEquals(
                (SnapshotCodec.encode(before) as EncodeResult.Encoded).bytes,
                (
                    SnapshotCodec.encode(
                        RelayDocument(before.section, after.snapshot)
                    ) as EncodeResult.Encoded
                    ).bytes
            )
            assertTrue(
                h.peer.relayTexts.none {
                    Json.parseToJsonElement(
                        it
                    ).jsonObject["method"]!!.jsonPrimitive.content.endsWith(".ack")
                }
            )
            assertFalse(h.ui.canSend)
            assertTrue(h.ui.contentEvictionPending)
            assertEquals(Standing.ACTIVE, after.section.enrollments.single().standing)
        }
    }
}
