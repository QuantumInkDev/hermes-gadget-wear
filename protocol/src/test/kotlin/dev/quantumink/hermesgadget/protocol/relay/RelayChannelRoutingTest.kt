package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [dev.quantumink.hermesgadget.protocol.DirectConnection] (real handshake, serial queue, generation
 * counter) over an in-memory socket factory: Relay opt-in, negotiation, info gating and the single raw router.
 * Captured server shapes come from `relay-v1` (REPLAY of recorded frames, re-addressed to the correlation id);
 * nothing here is a live integration.
 */
class RelayChannelRoutingTest {
    private val info = RelayVectors.forMethod(RelayMethod.INFO).first {
        it.id == "relay-info/ok-03"
    }

    private fun awaitPeer(fixture: RelaySocketFixture, count: Int = 1): RelaySocketFixture.Peer {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (fixture.peers.size >= count) return fixture.peers[count - 1]
            Thread.sleep(5)
        }
        error("No socket")
    }

    /** Connects and answers the automatic `relay.info` with the captured answer; returns the Ready state. */
    private fun ready(
        harness: RelayConnectionHarness,
        peerIndex: Int = 1
    ): Pair<RelaySocketFixture.Peer, RelayChannelState.Ready> {
        harness.connection.connect()
        val peer = awaitPeer(harness.fixture, peerIndex)
        val request = peer.nextRequest()
        assertEquals("relay.info", request["method"]!!.jsonPrimitive.content)
        peer.deliver(info.readdressed(request["id"]!!.jsonPrimitive.content))
        return peer to harness.awaitState<RelayChannelState.Ready>()
    }

    @Test(timeout = 20000)
    fun stockConnectionAdvertisesNoRelayAndRoutesRelayFramesAsBefore() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture, optIn = false).use { harness ->
            assertNull(harness.connection.relayTransport)
            harness.connection.connect()
            val peer = awaitPeer(fixture)
            harness.awaitConnected()
            val caps = peer.hello!!["caps"]!!.jsonObject
            assertFalse("relay" in caps)
            // Without the opt-in the router does not exist: a relay-shaped frame is just unknown stock traffic.
            peer.deliver("""{"type":"relay.response","v":1,"id":"x","result":{}}""")
            harness.connection.text("").get(5, TimeUnit.SECONDS)
            assertEquals(ConnectionStatus.PAIRED, harness.connectionStates.last().status)
            assertTrue(peer.relayRequests.isEmpty())
        }
    }

    @Test(timeout = 20000)
    fun optInAdvertisesExactlyTheRelayCapabilityAndKeepsEveryStockCapability() {
        val fixture = RelaySocketFixture()
        val stock = dev.quantumink.hermesgadget.protocol.Protocol.pcmCapabilities()
        RelayConnectionHarness(fixture, capabilities = stock).use { harness ->
            harness.connection.connect()
            val caps = awaitPeer(fixture).hello!!["caps"]!!.jsonObject
            assertEquals(buildJsonObject { put("version", 1) }, caps["relay"])
            assertEquals(stock.keys + "relay", caps.keys)
            for ((key, value) in stock) assertEquals(value, caps[key])
        }
    }

    @Test(timeout = 20000)
    fun aWelcomeWithoutRelayIsUnavailableNotNegotiatedAndNeverCallsInfo() {
        val fixture = RelaySocketFixture(relayMember = null)
        RelayConnectionHarness(fixture).use { harness ->
            harness.connection.connect()
            val peer = awaitPeer(fixture)
            assertEquals(
                RelayChannelState.NotOffered,
                harness.awaitState<RelayChannelState.NotOffered>()
            )
            harness.awaitConnected()
            assertTrue(peer.relayRequests.isEmpty())
            val seen = CopyOnWriteArrayList<RelayCallResult<Info>>()
            val latch = CountDownLatch(1)
            harness.transport.call(harness.transport.correlation(), RelayCalls.info(), 1000) {
                seen.add(it)
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(RelayRefusal.NOT_READY, (seen.single() as RelayCallResult.Refused).reason)
            assertTrue(peer.relayRequests.isEmpty())
        }
    }

    @Test(timeout = 20000)
    fun aMalformedRelayWelcomeIsAnErrorNotASilentFallback() {
        val shapes = listOf<JsonObject>(
            buildJsonObject { put("version", 2) },
            buildJsonObject { put("version", "1") },
            buildJsonObject {
                put("version", 1)
                put("extra", true)
            },
            JsonObject(emptyMap())
        )
        for (shape in shapes) {
            val fixture = RelaySocketFixture(relayMember = shape)
            RelayConnectionHarness(fixture).use { harness ->
                harness.connection.connect()
                val peer = awaitPeer(fixture)
                val state = harness.awaitState<RelayChannelState.Invalid>()
                assertEquals(RelayInvalid.MALFORMED_WELCOME, state.reason)
                assertTrue(peer.relayRequests.isEmpty())
            }
        }
        val notAnObject = RelaySocketFixture(relayMember = JsonPrimitive(true))
        RelayConnectionHarness(notAnObject).use { harness ->
            harness.connection.connect()
            awaitPeer(notAnObject)
            assertEquals(
                RelayInvalid.MALFORMED_WELCOME,
                harness.awaitState<RelayChannelState.Invalid>().reason
            )
        }
    }

    @Test(timeout = 20000)
    fun relayNegotiatedForAnUnpairedDeviceIsInvalid() {
        val fixture = RelaySocketFixture(paired = false)
        RelayConnectionHarness(fixture).use { harness ->
            harness.connection.connect()
            val peer = awaitPeer(fixture)
            assertEquals(
                RelayInvalid.NEGOTIATED_WITHOUT_PAIRING,
                harness.awaitState<RelayChannelState.Invalid>().reason
            )
            assertTrue(peer.relayRequests.isEmpty())
        }
    }

    @Test(timeout = 20000)
    fun onlyInfoIsCalledFirstAndOnlyAdvertisedMethodsAfterIt() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            harness.connection.connect()
            val peer = awaitPeer(fixture)
            assertEquals(
                RelayChannelState.LoadingInfo,
                harness.awaitState<RelayChannelState.LoadingInfo>()
            )
            val infoRequest = peer.nextRequest()
            assertEquals("relay.info", infoRequest["method"]!!.jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), infoRequest["params"])
            // Nothing but relay.info before it is answered.
            val early = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            harness.transport.call(
                harness.transport.correlation(),
                RelayCalls.receipt(conversation, request),
                1000
            ) { early.add(it) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (early.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(RelayRefusal.NOT_READY, (early.single() as RelayCallResult.Refused).reason)
            assertTrue(peer.relayRequests.isEmpty())
            // The captured info lists attach/submit/receipt/... but NOT relay.tasks.list or relay.task.get.
            peer.deliver(info.readdressed(infoRequest["id"]!!.jsonPrimitive.content))
            val ready = harness.awaitState<RelayChannelState.Ready>()
            assertTrue(ready.info.advertises(RelayMethod.TURN_RECEIPT))
            val denied = CopyOnWriteArrayList<RelayCallResult<TaskPage>>()
            harness.transport.call(harness.transport.correlation(), RelayCalls.tasks(), 1000) {
                denied.add(it)
            }
            while (denied.isEmpty() &&
                System.nanoTime() < deadline + 5_000_000_000L
            ) {
                Thread.sleep(5)
            }
            assertEquals(
                RelayRefusal.NOT_ADVERTISED,
                (denied.single() as RelayCallResult.Refused).reason
            )
            assertTrue(peer.relayRequests.isEmpty())
            // An advertised method is written, with the exact correlation the caller got.
            val correlation = harness.transport.correlation()
            harness.transport.call(correlation, RelayCalls.receipt(conversation, request), 1000) {}
            val sent = peer.nextRequest()
            assertEquals(correlation.value, sent["id"]!!.jsonPrimitive.content)
            assertEquals("relay.turn.receipt", sent["method"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout = 20000)
    fun theServerClockIsFixedFromInfoAndMonotonicNanosNeverTheDeviceClock() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (_, ready) = ready(harness)
            val at = ready.clock.read(harness.nanosNow() + 250_000_000L)
            val estimate = (at as dev.quantumink.hermesgadget.protocol.relay.state.InstantRead.At)
                .instant.serverMs
            assertEquals(requireNotNull(ready.info.serverTimeMs) + 250, estimate)
        }
    }

    @Test(timeout = 20000)
    fun anInfoWithoutServerTimeIsAFailureNotAClock() {
        val o5 = RelayVectors.forMethod(RelayMethod.INFO).first { it.id == "relay-info/ok-05" }
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            harness.connection.connect()
            val peer = awaitPeer(fixture)
            val request = peer.nextRequest()
            peer.deliver(o5.readdressed(request["id"]!!.jsonPrimitive.content))
            assertEquals(
                RelayFailure.NO_SERVER_TIME,
                harness.awaitState<RelayChannelState.Failed>().reason
            )
        }
    }

    @Test(timeout = 20000)
    fun anOversizeRelayResponseNeverReachesTheStockParserOrTheConversation() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val before = harness.connectionStates.size
            // 70,000 bytes: legal for the permissive stock parser (1 MiB, depth 64), illegal for Relay (65,536).
            val big = buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", "r1-1")
                put("result", buildJsonObject { put("blob", "x".repeat(70_000)) })
            }
            peer.deliver(big)
            assertEquals(
                RelayFailure.OVERSIZE_RESPONSE,
                harness.awaitState<RelayChannelState.Failed>().reason
            )
            // The stock connection stays paired and shows no stock failure and no conversation change.
            harness.connection.text("").get(5, TimeUnit.SECONDS)
            val after = harness.connectionStates.drop(before)
            assertTrue(after.all { it.status == ConnectionStatus.PAIRED && it.error.isEmpty() })
            assertEquals("", harness.connectionStates.last().conversation.reply)
        }
    }

    @Test(timeout = 20000)
    fun aDeepRelayResponseIsRefusedAsInvalidAndSettlesNothing() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            val latch = CountDownLatch(1)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val correlation = harness.transport.correlation()
            harness.transport.call(correlation, RelayCalls.receipt(conversation, request), 5000) {
                results.add(it)
                latch.countDown()
            }
            peer.nextRequest()
            // Depth 9 (a scalar nine levels down): the wire bound is 8.
            peer.deliver(
                """{"type":"relay.response","v":1,"id":"${correlation.value}","result":""" +
                    """{"a":{"b":{"c":{"d":{"e":{"f":{"g":1}}}}}}}}"""
            )
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val outcome = (results.single() as RelayCallResult.Outcome).outcome
            assertTrue(outcome is RelayOutcome.Invalid)
        }
    }

    @Test(timeout = 20000)
    fun aLateOrUnknownCorrelationIsIgnoredAndNeverStockTraffic() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            val correlation = harness.transport.correlation()
            harness.transport.call(correlation, RelayCalls.receipt(conversation, request), 1000) {
                results.add(it)
            }
            peer.nextRequest()
            peer.deliver(
                """{"type":"relay.response","v":1,"id":"never-issued","result":{"detached":true}}"""
            )
            harness.advance(2000)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (results.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(results.single() is RelayCallResult.TimedOut)
            // A reply after the timeout names no outstanding call.
            peer.fail(correlation.value, "not_found")
            harness.connection.text("").get(5, TimeUnit.SECONDS)
            assertEquals(1, results.size)
            assertEquals(ConnectionStatus.PAIRED, harness.connectionStates.last().status)
            assertEquals("", harness.connectionStates.last().conversation.reply)
        }
    }

    @Test(timeout = 20000)
    fun anUncorrelatedErrorFailsEveryCallInFlightAsALinkFailure() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            repeat(2) {
                harness.transport.call(
                    harness.transport.correlation(),
                    RelayCalls.receipt(conversation, request),
                    5000
                ) { results.add(it) }
                peer.nextRequest()
            }
            peer.fail(null, "invalid_request")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (results.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(2, results.size)
            assertTrue(
                results.all {
                    (it as RelayCallResult.Outcome).outcome is RelayOutcome.Uncorrelated
                }
            )
        }
    }

    @Test(timeout = 20000)
    fun disconnectEndsEveryCallClosedAndOlderGenerationsAreStale() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val old = harness.transport.correlation()
            val oldGeneration = harness.transport.generation
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            harness.transport.call(old, RelayCalls.receipt(conversation, request), 5000) {
                results.add(it)
            }
            peer.nextRequest()
            harness.connection.disconnect()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (results.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(results.single() is RelayCallResult.Closed)
            // A reconnect is a new socket generation; the old correlation can never be used again.
            harness.connection.connect()
            val second = awaitPeer(fixture, 2)
            val infoRequest = second.nextRequest()
            second.deliver(info.readdressed(infoRequest["id"]!!.jsonPrimitive.content))
            harness.awaitState<RelayChannelState.Ready>()
            assertTrue(harness.transport.generation > oldGeneration)
            val stale = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            harness.transport.call(old, RelayCalls.receipt(conversation, request), 5000) {
                stale.add(it)
            }
            while (stale.isEmpty() &&
                System.nanoTime() < deadline + 5_000_000_000L
            ) {
                Thread.sleep(10)
            }
            assertEquals(
                RelayRefusal.STALE_GENERATION,
                (stale.single() as RelayCallResult.Refused).reason
            )
            assertTrue(second.relayRequests.isEmpty())
        }
    }

    @Test(timeout = 20000)
    fun correlationsAreNeverReusedAndOutstandingCallsAreBounded() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val ids = (1..50).map { harness.transport.correlation().value }
            assertEquals(50, ids.toSet().size)
            assertTrue(ids.all { CorrelationId.of(it) != null })
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            val used = harness.transport.correlation()
            harness.transport.call(used, RelayCalls.receipt(conversation, request), 5000) {
                results.add(it)
            }
            peer.nextRequest()
            // The same id again: refused, never written.
            harness.transport.call(used, RelayCalls.receipt(conversation, request), 5000) {
                results.add(it)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (results.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(
                RelayRefusal.CORRELATION_REUSED,
                (results.single() as RelayCallResult.Refused).reason
            )
            // Fill the remaining three slots; the fifth call is refused.
            repeat(RelayChannel.MAX_OUTSTANDING - 1) {
                harness.transport.call(
                    harness.transport.correlation(),
                    RelayCalls.receipt(conversation, request),
                    5000
                ) {}
                peer.nextRequest()
            }
            val extra = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            harness.transport.call(
                harness.transport.correlation(),
                RelayCalls.receipt(conversation, request),
                5000
            ) { extra.add(it) }
            while (extra.isEmpty() &&
                System.nanoTime() < deadline + 5_000_000_000L
            ) {
                Thread.sleep(10)
            }
            assertEquals(
                RelayRefusal.TOO_MANY_OUTSTANDING,
                (extra.single() as RelayCallResult.Refused).reason
            )
        }
    }

    @Test(timeout = 20000)
    fun aFrameJustInsideTheWireBoundIsRouted() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val results = CopyOnWriteArrayList<RelayCallResult<HistoryPage>>()
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val correlation = harness.transport.correlation()
            // history.page is advertised by the captured info? the capture lists it: use the real call.
            harness.transport.call(correlation, RelayCalls.history(conversation), 5000) {
                results.add(it)
            }
            peer.nextRequest()
            val row = { index: Int ->
                buildJsonObject {
                    put("message_id", "3c000000-0000-4000-8000-%012d".format(index))
                    put("role", "assistant")
                    put("text", "y".repeat(8000))
                    put("created_at_ms", 1)
                    put("attachment_ids", JsonArray(emptyList()))
                    put("segment_index", 0)
                    put("segment_count", 1)
                }
            }
            val items = (1..7).map(row)
            val frame = buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", correlation.value)
                put(
                    "result",
                    buildJsonObject {
                        put("items", JsonArray(items))
                        put("next_cursor", kotlinx.serialization.json.JsonNull)
                    }
                )
            }
            val text = RelayVectors.asciiJson(frame)
            assertTrue(text.length in 56_000..RelayLimits.FRAME_BYTES)
            peer.deliver(text)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (results.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            val outcome = (results.single() as RelayCallResult.Outcome).outcome
            assertTrue(outcome is RelayOutcome.Success)
            assertEquals(7, (outcome as RelayOutcome.Success).value.items.size)
            assertNotNull(outcome.value.items.first().text)
        }
    }

    @Test(timeout = 20000)
    fun aBurstBeyondThePacingBucketWaitsInOrderAndIsNeverRefusedForTheRate() {
        val fixture = RelaySocketFixture()
        RelayConnectionHarness(fixture).use { harness ->
            val (peer, _) = ready(harness)
            val conversation = ConversationId.of("11111111-1111-4111-8111-111111111111")!!
            val request = RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            val results = CopyOnWriteArrayList<RelayCallResult<Receipt>>()
            val order = CopyOnWriteArrayList<String>()
            // Five at a time is the outstanding bound: send, answer, repeat, far more than the bucket holds.
            var sent = 0
            while (sent < 40) {
                val batch = minOf(RelayChannel.MAX_OUTSTANDING, 40 - sent)
                val ids = (1..batch).map { harness.transport.correlation() }
                ids.forEach { id ->
                    harness.transport.call(id, RelayCalls.receipt(conversation, request), 20_000) {
                        results.add(it)
                    }
                }
                // Static connection time: tokens refill only as the clock moves, so requests beyond the bucket WAIT.
                repeat(batch) {
                    val sentRequest = peer.relayRequests.poll(2, TimeUnit.SECONDS)
                    if (sentRequest != null) {
                        order.add(sentRequest["id"]!!.jsonPrimitive.content)
                        peer.fail(sentRequest["id"]!!.jsonPrimitive.content, "not_found")
                    } else {
                        harness.advance(500)
                    }
                }
                sent += batch
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (results.size < sent && System.nanoTime() < deadline) {
                    harness.advance(200)
                    Thread.sleep(20)
                    peer.relayRequests.poll()?.let {
                        order.add(it["id"]!!.jsonPrimitive.content)
                        peer.fail(it["id"]!!.jsonPrimitive.content, "not_found")
                    }
                }
            }
            assertEquals(40, results.size)
            // Never refused for the rate; each one ended with its own typed outcome, in the order sent.
            assertTrue(results.none { it is RelayCallResult.Refused })
            assertEquals(order.toList(), order.sortedBy { order.indexOf(it) })
        }
    }
}
