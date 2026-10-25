package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeAnswer
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeBackend
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeResult
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeStatus
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTarget
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTargetId
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTargets
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeUnavailableReason
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote wake through the production controller: deliberate, one frame per tap, never stored and never a scope stop. */
class RelayWakeControllerTest {
    private val box = WakeTargetId.of("42f07b48-4fba-457e-9a9e-31ba5eff5dfa")!!
    private val asleep = WakeTargetId.of("4bb9b3a4-3f0a-4a51-9b2e-a1d04b0b9e1c")!!
    private val wakeInfo = Dto.info(
        methods =
        Dto.ALL_METHODS + listOf("wake.targets", "wake.request")
    )

    private fun listed() = WakeTargets(
        listOf(
            WakeTarget(box, "Parallels VM", WakeBackend.VM_START_RESUME, true, null),
            WakeTarget(
                asleep,
                "Old tower",
                WakeBackend.WOL,
                false,
                WakeUnavailableReason.UNAVAILABLE
            )
        )
    )

    private fun ready(
        info: dev.quantumink.hermesgadget.protocol.relay.Info = wakeInfo
    ): ControllerFixture {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect(info)
        fx.listAnd()
        return fx
    }

    private fun withTargets(): ControllerFixture {
        val fx = ready()
        fx.controller.listWakeTargets()
        fx.run()
        fx.answer("wake.targets", listed().toWire())
        return fx
    }

    @Test fun nothingWakesOrListsByItselfAndTheListIsOneDeliberateRead() {
        val fx = ready()
        assertTrue(fx.ui.wake.canList && fx.ui.wake.canRequest)
        assertNull(fx.ui.wake.targets)
        assertTrue(fx.frames("wake.targets").isEmpty() && fx.frames("wake.request").isEmpty())
        // Reconnecting does not wake or list either.
        fx.disconnect()
        fx.connect(wakeInfo)
        fx.listAnd()
        assertTrue(fx.frames("wake.targets").isEmpty() && fx.frames("wake.request").isEmpty())
        fx.controller.listWakeTargets()
        fx.controller.listWakeTargets()
        fx.run()
        assertEquals(1, fx.frames("wake.targets").size)
        assertTrue(fx.ui.wake.listing)
        fx.answer("wake.targets", listed().toWire())
        assertEquals(listed().targets, fx.ui.wake.targets)
        assertFalse(fx.ui.wake.listing)
        assertTrue(fx.frames("wake.request").isEmpty())
    }

    @Test fun oneTapSendsExactlyOneRequestWithServerClockExpiryAndStoresNothing() {
        val fx = withTargets()
        val commits = fx.store.commits
        fx.advanceTime(4_000)
        fx.controller.wake(box.value)
        fx.controller.wake(box.value) // a second tap while the first is in flight does nothing
        fx.run()
        val sent = fx.frames("wake.request").single()
        assertEquals(box.value, sent.params["target_id"]!!.jsonPrimitive.content)
        val requestId = RequestId.of(sent.params["request_id"]!!.jsonPrimitive.content)!!
        assertEquals(Dto.SERVER_TIME + 4_000, RequestIds.timestampMs(requestId))
        assertEquals(
            Dto.SERVER_TIME + 4_000 + 20_000,
            sent.params["expires_at_ms"]!!.jsonPrimitive.content.toLong()
        )
        assertEquals(box.value, fx.ui.wake.sending)
        fx.answer("wake.request", WakeResult(requestId, WakeStatus.ACCEPTED).toWire())
        assertEquals(WakeAnswer.ACCEPTED, fx.ui.wake.answers[box.value])
        assertNull(fx.ui.wake.sending)
        assertEquals(commits, fx.store.commits)
        // A new tap is a new request with a new id; nothing repeats by itself.
        fx.advanceTime(120_000)
        assertEquals(1, fx.frames("wake.request").size)
        fx.controller.wake(box.value)
        fx.run()
        val second = fx.frames("wake.request").last()
        assertFalse(second.params["request_id"] == sent.params["request_id"])
    }

    @Test fun unlistedOrUnavailableTargetsAreNeverRequested() {
        val fx = withTargets()
        fx.controller.wake(asleep.value)
        fx.controller.wake("00000000-0000-4000-8000-000000000001")
        fx.controller.wake("not-a-uuid")
        fx.run()
        assertTrue(fx.frames("wake.request").isEmpty())
    }

    @Test fun aWakeDenialIsShownButNeverStopsTheConversationScope() {
        val fx = withTargets()
        fx.controller.wake(box.value)
        fx.run()
        fx.answerError("wake.request", "permission_denied")
        assertEquals(WakeAnswer.NOT_PERMITTED, fx.ui.wake.answers[box.value])
        assertEquals(RelayPhase.READY, fx.ui.phase)
        assertEquals(Standing.ACTIVE, fx.store.reread().section.enrollments.single().standing)
        fx.advanceTime(60_000)
        fx.controller.wake(box.value)
        fx.run()
        fx.answerError("wake.request", "rate_limited")
        assertEquals(WakeAnswer.TOO_SOON, fx.ui.wake.answers[box.value])
        fx.advanceTime(120_000)
        // rate_limited is not a retry loop here.
        assertEquals(2, fx.frames("wake.request").size)
    }

    @Test fun aLostAnswerIsNotConfirmedAndNeverRetried() {
        val fx = withTargets()
        fx.controller.wake(box.value)
        fx.run()
        fx.transport.timeout(fx.transport.next("wake.request"))
        fx.run()
        assertEquals(WakeAnswer.NOT_CONFIRMED, fx.ui.wake.answers[box.value])
        fx.controller.wake(box.value)
        fx.run()
        // The connection ends with the request in flight: its callback is withdrawn, the outcome stays unknown.
        fx.disconnect()
        assertEquals(WakeAnswer.NOT_CONFIRMED, fx.ui.wake.answers[box.value])
        assertNull(fx.ui.wake.sending)
        fx.connect(wakeInfo)
        fx.listAnd()
        fx.advanceTime(120_000)
        assertEquals(2, fx.frames("wake.request").size)
    }

    @Test fun notAdvertisedWakeSendsNothingAndSaysSo() {
        val fx = ready(Dto.info())
        assertFalse(fx.ui.wake.offered)
        fx.controller.listWakeTargets()
        fx.controller.wake(box.value)
        fx.run()
        assertEquals(WakeAnswer.NOT_OFFERED, fx.ui.wake.listAnswer)
        assertTrue(fx.frames("wake.targets").isEmpty() && fx.frames("wake.request").isEmpty())
        // Read permission only: the list works and the request is never offered.
        val read = ready(Dto.info(methods = Dto.ALL_METHODS + "wake.targets"))
        assertTrue(read.ui.wake.canList)
        assertFalse(read.ui.wake.canRequest)
        read.controller.listWakeTargets()
        read.run()
        read.answer("wake.targets", listed().toWire())
        read.controller.wake(box.value)
        read.run()
        assertEquals(WakeAnswer.NOT_OFFERED, read.ui.wake.answers[box.value])
        assertTrue(read.frames("wake.request").isEmpty())
    }

    @Test fun withRelayOffNothingIsOfferedOrSent() {
        val fx = ControllerFixture()
        fx.prepare()
        fx.controller.listWakeTargets()
        fx.controller.wake(box.value)
        fx.run()
        assertEquals(RelayPhase.OFF, fx.ui.phase)
        assertFalse(fx.ui.wake.offered)
        assertTrue(fx.transport.sent.isEmpty())
        // Turning Relay off forgets the transient wake view.
        val on = withTargets()
        on.controller.setEnabled(false)
        on.run()
        assertNull(on.ui.wake.targets)
        assertFalse(on.ui.wake.offered)
    }
}
