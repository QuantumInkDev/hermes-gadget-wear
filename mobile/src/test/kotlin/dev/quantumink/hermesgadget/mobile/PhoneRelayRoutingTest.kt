package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.Protocol
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.session.ConversationItem
import dev.quantumink.hermesgadget.protocol.relay.session.RelayNotice
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayReason
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stock-off compatibility and the Relay input routing of the phone screen, as pure seams. Nothing here starts a socket, a
 * key or a device; the native screen itself is not exercised in this stage.
 */
class PhoneRelayRoutingTest {
    private val conversation = ConversationItem(ConversationId.of("3ff4ef74-af70-4efc-a3d4-e797b98a4dea")!!.value, "Plan", 1, true)

    @Test fun withRelayOffTheStockRoutesAreUnchangedAndNoRelayCapabilityIsOffered() {
        val off = PhoneConversationState().relay
        assertEquals(RelayPhase.OFF, off.phase)
        assertFalse(off.relayOwnsComposer)
        assertFalse(off.micAvailable)
        assertEquals(InputRoute.STOCK, PhoneRelay.typedRoute(off))
        assertEquals(InputRoute.STOCK, PhoneRelay.voiceRoute(off))
        // A connection built for a profile with Relay off advertises nothing new: no opt-in object exists at all.
        assertNull(PhoneRelay.optIn(false, { 1L }) { _, _ -> })
        val on = PhoneRelay.optIn(true, { 7L }) { _, _ -> }
        assertNotNull(on)
        assertEquals(7L, on!!.monotonicNanos())
    }

    @Test fun relayEnabledButNothingSelectedStillRoutesToTheStockChatAndStockVoice() {
        for (phase in listOf(RelayPhase.CONNECTING, RelayPhase.READY, RelayPhase.UNAVAILABLE, RelayPhase.REVIEW_REQUIRED, RelayPhase.STOPPED)) {
            val state = RelayUiState(phase = phase, enabled = true)
            assertEquals(phase.toString(), InputRoute.STOCK, PhoneRelay.typedRoute(state))
            assertEquals(phase.toString(), InputRoute.STOCK, PhoneRelay.voiceRoute(state))
        }
    }

    @Test fun insideASelectedRelayConversationTypingGoesToRelayAndTheMicrophoneIsUnavailableNeverStock() {
        for (phase in listOf(RelayPhase.SELECTED, RelayPhase.ATTACHING)) {
            val state = RelayUiState(phase = phase, enabled = true, selected = conversation, canSend = phase == RelayPhase.SELECTED)
            assertTrue(state.relayOwnsComposer)
            assertEquals(InputRoute.RELAY, PhoneRelay.typedRoute(state))
            assertEquals(InputRoute.UNAVAILABLE, PhoneRelay.voiceRoute(state))
            assertFalse(state.micAvailable)
        }
    }

    @Test fun stockAudioCapabilitiesAreExactlyTheStockOnesWhetherOrNotRelayIsOffered() {
        val stock = PhoneConversation.audioCapabilities(opus = false, clientSpeech = false)
        assertEquals(Protocol.pcmCapabilities().keys + "mic" + "speaker", stock.keys + "mic" + "speaker")
        assertFalse("relay" in stock)
        // The relay capability is added to the hello by the connection, never by the audio capability builder.
        assertFalse("relay" in PhoneConversation.audioCapabilities(opus = true, clientSpeech = true))
    }

    @Test fun everyNoticeReasonStorageProblemAndTurnStatusHasPlainText() {
        for (notice in RelayNotice.entries) assertEquals(notice.name, notice == RelayNotice.NONE, relayNoticeText(notice).isEmpty())
        for (reason in RelayReason.entries) if (reason != RelayReason.NONE) assertTrue(reason.name, relayReasonText(reason).isNotBlank())
        for (problem in StoreProblem.entries) assertTrue(problem.name, relayStorageText(problem).isNotBlank())
        for (status in TurnStatus.entries) assertTrue(status.name, turnStatusText(status).isNotBlank())
        // The uncertain states are never worded as success.
        assertFalse(turnStatusText(TurnStatus.UNCERTAIN).contains("Done"))
        assertFalse(turnStatusText(TurnStatus.UNKNOWN_ADMITTED).contains("Done"))
        assertEquals("Done", turnStatusText(TurnStatus.FINISHED_COMPLETED))
    }

    @Test fun theControllerThreadRunsTasksInOrderOffTheCallerAndCancelsTimers() {
        ExecutorRelayScheduler().use { scheduler ->
            val order = ArrayList<Int>()
            val done = CountDownLatch(3)
            val threads = HashSet<String>()
            for (n in 1..3) {
                scheduler.post(
                    Runnable {
                        synchronized(order) { order.add(n) }
                        threads.add(Thread.currentThread().name)
                        done.countDown()
                    }
                )
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), order)
            assertEquals(setOf("relay-controller"), threads)
            val fired = AtomicInteger()
            val cancelled = scheduler.postDelayed(200, Runnable { fired.incrementAndGet() })
            val kept = scheduler.postDelayed(200, Runnable { fired.addAndGet(10) })
            cancelled.cancel()
            Thread.sleep(500)
            assertEquals(10, fired.get())
            assertNotNull(kept)
        }
    }

    @Test fun theRelayChannelStateAlphabetIsWhatTheConnectionReports() {
        val reported = ArrayList<RelayChannelState>()
        val optIn = PhoneRelay.optIn(true, { 1L }) { state, _ -> reported.add(state) }!!
        optIn.listener.relayState(RelayChannelState.NotOffered, 1)
        optIn.listener.relayState(RelayChannelState.Closed, 2)
        assertEquals(listOf(RelayChannelState.NotOffered, RelayChannelState.Closed), reported)
    }
}
