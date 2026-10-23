package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayListener
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled production-controller/connection interleaving; unchanged registered producer recording, replay only. */
class RelayReconnectOrderingTest {
    @Test(timeout = 20000)
    fun reconnectListSurvivesAttachWithdrawalBeforeSocketOwnerCanSend() {
        ReplayHarness("task3-absent-denied-stop.json").use { h ->
            val holdReconnect = AtomicBoolean()
            val readyPosted = CountDownLatch(1)
            val releaseSocketOwner = CountDownLatch(1)
            val next = DirectConnection(
                Endpoint.parse(h.identity.endpointUrl),
                DeviceIdentity.generate(),
                object : ConnectionObserver {
                    override fun stateChanged(state: ConnectionState) = Unit
                    override fun effect(effect: ConversationEffect, generation: Long) = Unit
                },
                now = { System.nanoTime() / 1_000_000L },
                relay = RelayOptIn(
                    RelayListener { state, generation ->
                        h.controller.onChannel(state, generation)
                        if (state is RelayChannelState.Ready && holdReconnect.get()) {
                            // The controller owner can queue BOTH siblings before this real socket owner resumes.
                            readyPosted.countDown()
                            check(releaseSocketOwner.await(10, TimeUnit.SECONDS))
                        }
                    }
                ) { h.nanos.get() },
                webSockets = h.fixture
            )
            try {
                h.prepareAndEnable()
                h.controller.attachTransport(next.relayTransport)
                h.scheduler.runAll()
                next.connect()
                h.settle()
                h.controller.create("Planning")
                h.settle()
                assertEquals(RelayPhase.SELECTED, h.ui.phase)
                h.server.swallowNext("relay.turn.submit")
                h.controller.send("first question") {}
                h.settle()
                next.disconnect()
                h.settle()
                holdReconnect.set(true)
                next.connect()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!readyPosted.await(5, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
                    h.server.pump()
                }
                assertEquals("fresh info never reached the controlled boundary", 0L, readyPosted.count)
                h.scheduler.runAll()
                releaseSocketOwner.countDown()
                h.settle()

                assertTrue(h.server.mismatches.toString(), h.server.mismatches.isEmpty())
                assertEquals(2, h.requests("relay.conversations.list").size)
                val methods = h.server.seen.map { it["method"]!!.jsonPrimitive.content }
                val listAt = methods.indexOfLast { it == "relay.conversations.list" }
                val attachAt = methods.indexOfLast { it == "relay.conversation.attach" }
                val receiptAt = methods.indexOfLast { it == "relay.turn.receipt" }
                assertTrue(listAt < attachAt && attachAt < receiptAt)
                assertEquals("relay.turn.receipt", methods.last())
                assertEquals(1, h.requests("relay.turn.submit").size)
                assertEquals(RelayPhase.STOPPED, h.ui.phase)
                assertEquals(Standing.STOPPED, h.store.reread().section.enrollments.single().standing)
                assertFalse(h.ui.canSend)
                assertTrue(h.server.extra.isEmpty())
            } finally {
                // Release before close even after RED; no blocked event thread is abandoned.
                releaseSocketOwner.countDown()
                next.close()
            }
        }
    }
}
