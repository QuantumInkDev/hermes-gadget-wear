package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.Protocol
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayFailure
import dev.quantumink.hermesgadget.protocol.relay.RelayFrame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real DirectConnection/channel + the exact production foreground bridge, no AndroidViewModel/native execution. */
class PhoneRelayChannelHandoffTest {
    private class Fixture : WebSocket.Factory {
        val requests = LinkedBlockingQueue<JsonObject>()
        lateinit var peer: WebSocket
        lateinit var listener: WebSocketListener
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            peer = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize(): Long = 0
                override fun send(bytes: ByteString): Boolean = true
                override fun close(code: Int, reason: String?): Boolean = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val value = Json.parseToJsonElement(text).jsonObject
                    when (value["type"]!!.jsonPrimitive.content) {
                        "hello" -> deliver(
                            """{"type":"challenge","nonce":"AAAAAAAAAAAAAAAAAAAAAA==","enrolled":true}"""
                        )
                        "auth" -> deliver(
                            """{"type":"welcome","proto":1,"paired":true,"heartbeat_s":3600,"relay":{"version":1}}"""
                        )
                        "relay.request" -> requests.add(value)
                    }
                    return true
                }
            }
            listener.onOpen(
                peer,
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols")
                    .header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL).build()
            )
            return peer
        }

        fun deliver(text: String) = listener.onMessage(peer, text)

        fun info(): String {
            val request = requests.poll(5, TimeUnit.SECONDS) ?: error("No info request")
            assertEquals("relay.info", request["method"]!!.jsonPrimitive.content)
            // Existing captured producer recording, replay only; correlation is the only replacement.
            val resource = javaClass.getResource("/relay-v1/relay-info.json")!!
            val case = Json.parseToJsonElement(resource.readText()).jsonObject["cases"]!!.jsonArray
                .first { it.jsonObject["id"]!!.jsonPrimitive.content == "relay-info/ok-03" }
            val response = case.jsonObject["response"]!!.jsonObject
            return JsonObject(response + ("id" to request["id"]!!)).toString()
        }
    }

    private class Owner {
        // This monitor is passed to the same bridge that PhoneConversation passes `this` to.
        var epoch = 1L
        var lease = 10L
        val posted = ConcurrentLinkedQueue<Runnable>()
        val applied = ArrayList<Pair<RelayChannelState, Long>>()
        var drainStarted: CountDownLatch? = null
        fun bridge(): PhoneRelayChannelHandoff {
            val connectionEpoch = epoch
            val foregroundLease = lease
            return PhoneRelayChannelHandoff(
                this,
                { task ->
                    posted.add(
                        Runnable {
                            drainStarted?.countDown()
                            task.run()
                        }
                    )
                },
                { epoch == connectionEpoch && lease == foregroundLease },
                { state, generation ->
                    assertTrue(Thread.holdsLock(this))
                    applied.add(state to generation)
                }
            )
        }
        fun drain() {
            while (true) (posted.poll() ?: return).run()
        }
    }

    private fun connection(
        fixture: Fixture,
        bridge: PhoneRelayChannelHandoff,
        entered: CountDownLatch,
        returned: CountDownLatch
    ) = DirectConnection(
        Endpoint.parse("wss://example.invalid/gadget"),
        DeviceIdentity.generate(),
        object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) = Unit
            override fun effect(effect: ConversationEffect, generation: Long) = Unit
        },
        relay = PhoneRelay.optIn(true, { 5_000_000_000L }) { state, generation ->
            if (state is RelayChannelState.Ready) entered.countDown()
            bridge.relayState(state, generation)
            if (state is RelayChannelState.Ready) returned.countDown()
        },
        webSockets = fixture
    )

    @Test(timeout = 15000)
    fun actualFrameListenerReturnsWhileForegroundOwnsMonitorThenCloseAndReplacementTerminate() {
        val owner = Owner()
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val old = connection(fixture, owner.bridge(), entered, returned)
        var draining: Thread? = null
        try {
            old.connect()
            val info = fixture.info()
            owner.drain()
            owner.applied.clear()
            synchronized(owner) {
                // Actual serial event thread enters monitored RelayChannel.frame.
                fixture.deliver(info)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(
                    "channel listener waited for the foreground monitor",
                    returned.await(1, TimeUnit.SECONDS)
                )
                val drainStarted = CountDownLatch(1)
                owner.drainStarted = drainStarted
                draining = thread(isDaemon = true, name = "foreground-drain") { owner.drain() }
                assertTrue(drainStarted.await(5, TimeUnit.SECONDS))
                // The drain waits for this owner without a mailbox/channel lock needed by close.
                // Opposite direction: foreground monitor -> DirectConnection.close -> channel monitor.
                owner.epoch++
                owner.lease++
                old.close()
                // A new foreground lease must survive both old Ready and close callbacks.
                owner.bridge().relayState(RelayChannelState.NotOffered, 99)
            }
            draining!!.join(5000)
            assertTrue("posted owner drain must terminate after teardown", !draining.isAlive)
            owner.drain()
            assertEquals(listOf(RelayChannelState.NotOffered to 99L), owner.applied)
        } finally {
            // RED assertion releases the owner before cleanup; no deadlocked JVM is abandoned.
            old.close()
            draining?.join(5000)
        }
    }

    private fun queuedNotification(changeEpoch: Boolean, changeLease: Boolean) {
        val owner = Owner()
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        connection(fixture, owner.bridge(), entered, returned).use { conn ->
            conn.connect()
            val info = fixture.info()
            owner.drain()
            owner.applied.clear()
            fixture.deliver(info)
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            synchronized(owner) {
                if (changeEpoch) owner.epoch++
                if (changeLease) owner.lease++
            }
            owner.drain()
            if (changeEpoch || changeLease) {
                assertTrue(
                    "queued notification crossed an owner replacement",
                    owner.applied.isEmpty()
                )
            } else {
                assertEquals(1, owner.applied.size)
                assertTrue(owner.applied.single().first is RelayChannelState.Ready)
                assertEquals(conn.relayTransport!!.generation, owner.applied.single().second)
            }
        }
    }

    @Test fun queuedFrameIsRejectedAfterConnectionEpochChanges() = queuedNotification(true, false)

    @Test fun queuedFrameIsRejectedAfterForegroundLeaseChanges() = queuedNotification(false, true)

    @Test fun validCurrentFrameIsConsumedWithItsOriginalGeneration() = queuedNotification(
        false,
        false
    )

    @Test fun burstHasOnePostedDrainAndBoundedOrderedWorkThenFailsClosed() {
        val owner = Owner()
        val bridge = owner.bridge()
        repeat(1000) { bridge.relayState(RelayChannelState.LoadingInfo, it.toLong()) }
        assertEquals("at most one outstanding owner task", 1, owner.posted.size)
        owner.drain()
        assertTrue(owner.applied.size <= 16)
        val failed = owner.applied.last().first as RelayChannelState.Failed
        assertEquals(RelayFailure.SEND_REFUSED, failed.reason)
        assertEquals((0L..14L).toList(), owner.applied.dropLast(1).map { it.second })
        bridge.relayState(RelayChannelState.LoadingInfo, 1001)
        // Overflow seals this connection's bridge; replacement gets a new one.
        assertTrue(owner.posted.isEmpty())
    }

    private fun verdict(code: String): RelayChannelState.Failed {
        val frame = RelayEnvelope.decodeFrame(
            """{"type":"relay.response","v":1,"id":"r1-1","error":{"code":"$code"}}"""
        ) as RelayFrame.Failure
        return RelayChannelState.Failed(RelayFailure.INFO_REFUSED, frame.error)
    }

    @Test fun overflowDoesNotFlattenAnAlreadyQueuedPermissionDenialOrDowngradeIt() {
        val owner = Owner()
        val bridge = owner.bridge()
        bridge.relayState(verdict("permission_denied"), 1)
        repeat(1000) { bridge.relayState(RelayChannelState.LoadingInfo, 1) }
        bridge.relayState(verdict("profile_changed"), 1)
        owner.drain()
        assertEquals(16, owner.applied.size)
        assertEquals(
            RelayErrorCode.PERMISSION_DENIED,
            (owner.applied.first().first as RelayChannelState.Failed).error!!.code
        )
    }

    @Test fun overflowStillDeliversLaterAuthoritativeDenialBeforeSealingStrongerVerdicts() {
        val owner = Owner()
        val bridge = owner.bridge()
        repeat(1000) { bridge.relayState(RelayChannelState.LoadingInfo, 1) }
        owner.drain()
        bridge.relayState(verdict("profile_changed"), 1)
        bridge.relayState(verdict("permission_denied"), 1)
        owner.drain()
        assertEquals(
            listOf(RelayErrorCode.PROFILE_CHANGED, RelayErrorCode.PERMISSION_DENIED),
            owner.applied.takeLast(2).map { (it.first as RelayChannelState.Failed).error!!.code }
        )
        bridge.relayState(verdict("permission_denied"), 1)
        assertTrue(owner.posted.isEmpty())
    }
}
