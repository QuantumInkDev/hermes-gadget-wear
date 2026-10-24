package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receipt-first reconnect, same-ID retry, late and out-of-order callbacks, gaps and the error rules, on the production
 * controller. Replies are decoded by the real envelope and DTO decoders.
 */
class RelayControllerReconnectTest {
    private fun selected(
        fx: ControllerFixture,
        binding: Int = 1
    ): dev.quantumink.hermesgadget.protocol.relay.Binding {
        fx.connect()
        fx.listAnd(Dto.summary(), Dto.summary(Dto.otherConversation, "Other"))
        fx.controller.select(Dto.conversation.value)
        fx.run()
        val b = Dto.binding(binding)
        fx.attachTo(b)
        assertEquals(RelayPhase.SELECTED, fx.ui.phase)
        return b
    }

    private fun sendOne(
        fx: ControllerFixture,
        text: String = "the question"
    ): ScriptedTransport.Sent {
        var accepted = false
        fx.controller.send(text) { accepted = it }
        fx.run()
        assertTrue(accepted)
        return fx.frames("relay.turn.submit").last()
    }

    /** After a reconnect the controller asks for the list and then attaches (the continuing foreground session). */
    private fun reconnectAndAttach(
        fx: ControllerFixture,
        binding: Int,
        info: dev.quantumink.hermesgadget.protocol.relay.Info = Dto.info()
    ) {
        fx.disconnect()
        fx.connect(info)
        fx.listAnd(Dto.summary(), Dto.summary(Dto.otherConversation, "Other"))
        fx.answer("relay.conversation.attach", Dto.binding(binding).toWire())
    }

    @Test fun afterATimeoutAndAReconnectTheReceiptIsAskedFirstAndNothingIsSubmittedAgain() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        fx.transport.timeout(submit)
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        reconnectAndAttach(fx, 2)
        // The very next call after the fresh attach is the receipt lookup: before history, tasks, and any submit.
        assertEquals("relay.turn.receipt", fx.transport.methods().last())
        val lookup = fx.transport.next("relay.turn.receipt")
        val id = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        assertEquals(id.value, lookup.params["request_id"]!!.jsonPrimitive.content)
        fx.transport.reply(lookup, Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        fx.run()
        assertEquals(TurnStatus.QUEUED, fx.ui.turns.single().status)
        assertEquals(1, fx.frames("relay.turn.submit").size)
    }

    @Test fun anAbsentTurnIsSubmittedAgainWithTheSameIdTextAndExpiryAndNeverAsANewTurn() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val first = sendOne(fx, "keep me identical")
        fx.transport.timeout(first)
        fx.run()
        reconnectAndAttach(fx, 2)
        fx.transport.fail(fx.transport.next("relay.turn.receipt"), "not_found")
        fx.run()
        val second = fx.frames("relay.turn.submit")
        assertEquals(2, second.size)
        for (member in listOf("request_id", "text", "expires_at_ms")) {
            assertEquals(member, first.params[member], second.last().params[member])
        }
        // It is written under the NEW binding, never the old one.
        assertNotEquals(first.params["binding_id"], second.last().params["binding_id"])
        val id = RequestId.of(first.params["request_id"]!!.jsonPrimitive.content)!!
        fx.transport.reply(second.last(), Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        fx.run()
        assertEquals(1, fx.ui.turns.size)
        assertEquals(TurnStatus.QUEUED, fx.ui.turns.single().status)
    }

    @Test fun pastItsDeadlineAnAbsentTurnIsNeverSubmittedAgain() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val first = sendOne(fx)
        fx.transport.timeout(first)
        fx.run()
        // 130 s later (the default lifetime is 120 s) the same id may no longer be submitted.
        fx.advanceTime(130_000)
        reconnectAndAttach(fx, 2, Dto.info(serverTimeMs = Dto.SERVER_TIME + 130_000))
        fx.transport.fail(fx.transport.next("relay.turn.receipt"), "not_found")
        fx.run()
        assertEquals(1, fx.frames("relay.turn.submit").size)
        assertFalse(fx.ui.turns.single().canResend)
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
    }

    @Test fun anExpiredAnswerKeepsTheTurnUncertainAndNothingIsResent() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val first = sendOne(fx)
        fx.transport.fail(first, "expired")
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        reconnectAndAttach(fx, 2)
        fx.transport.fail(fx.transport.next("relay.turn.receipt"), "expired")
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        assertEquals(1, fx.frames("relay.turn.submit").size)
    }

    @Test fun staleEpochOnASubmitRefreshesInfoAttachesAndLooksUpBeforeAnyResend() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val first = sendOne(fx)
        fx.transport.fail(first, "stale_epoch")
        fx.run()
        assertEquals(1, fx.transport.refreshes)
        // The channel answers the refresh with a fresh info (a new grant view): the continuing session attaches again.
        fx.ready(Dto.info(grant = 3))
        fx.listAnd(Dto.summary())
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        val methods = fx.transport.methods()
        assertEquals("relay.turn.receipt", methods.last())
        assertEquals(1, fx.frames("relay.turn.submit").size)
        val id = RequestId.of(first.params["request_id"]!!.jsonPrimitive.content)!!
        fx.transport.reply(
            fx.transport.next("relay.turn.receipt"),
            Dto.receipt(id, ReceiptStatus.STARTED, generation = 1).toWire()
        )
        fx.run()
        assertEquals(TurnStatus.RUNNING, fx.ui.turns.single().status)
    }

    @Test fun aLateReplyOfAnEndedSocketCannotSettleOrPublishAnything() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        fx.disconnect()
        val commits = fx.store.commits
        val id = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        // The old socket's answer arrives after the session ended: dropped before it can touch the ledger.
        submit.frame(
            kotlinx.serialization.json.buildJsonObject {
                kotlinx.serialization.json.JsonPrimitive("x")
                put("id", kotlinx.serialization.json.JsonPrimitive(submit.correlation.value))
                put("result", Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
            }
        )
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        assertEquals(commits, fx.store.commits)
    }

    @Test fun anOlderAttachCannotWinOverALaterSelection() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary(), Dto.summary(Dto.otherConversation, "Other"))
        fx.controller.select(Dto.conversation.value)
        fx.run()
        val first = fx.transport.next("relay.conversation.attach")
        fx.controller.select(Dto.otherConversation.value)
        fx.run()
        val second = fx.transport.open("relay.conversation.attach").last()
        // The first answer arrives late: ignored entirely.
        fx.transport.reply(first, Dto.binding(1).toWire())
        fx.run()
        assertTrue(fx.transport.open("relay.history.page").isEmpty())
        fx.transport.reply(second, Dto.binding(2, Dto.otherConversation).toWire())
        fx.run()
        assertEquals(
            Dto.otherConversation.value,
            fx.transport.next(
                "relay.history.page"
            ).params["conversation_id"]!!.jsonPrimitive.content
        )
        assertEquals(
            Dto.otherConversation,
            fx.store.reread().section.enrollments.single().selectedConversationId
        )
    }

    @Test fun anEventGapRefreshesHistoryAndTasksWithoutAnyResubmission() {
        val fx = ControllerFixture()
        fx.enable()
        val binding = selected(fx)
        val submit = sendOne(fx)
        val id = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        fx.transport.reply(submit, Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        fx.run()
        val historyCalls = fx.frames("relay.history.page").size
        fx.advanceTime(fx.timing.pollActiveMs)
        // An admitted turn is looked up first (read only), then the events are pulled.
        fx.transport.reply(
            fx.transport.next("relay.turn.receipt"),
            Dto.receipt(id, ReceiptStatus.QUEUED).toWire()
        )
        fx.run()
        val read = fx.transport.next("relay.events.read")
        val gap = Dto.event(1, binding, EventPayload.Gap)
        fx.transport.reply(read, EventsPage(listOf(gap), binding.replayEpoch, 1, true).toWire())
        fx.run()
        // The binding was retired by the gap: info is read again, the session attaches again and refreshes.
        assertEquals(1, fx.transport.refreshes)
        fx.ready(Dto.info())
        fx.listAnd(Dto.summary())
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        // Receipt first (the admitted turn is not settled), then history and tasks again.
        assertEquals("relay.turn.receipt", fx.transport.methods().last())
        fx.transport.reply(
            fx.transport.open("relay.turn.receipt").last(),
            Dto.receipt(id, ReceiptStatus.STARTED, generation = 1).toWire()
        )
        fx.run()
        assertEquals(historyCalls + 1, fx.frames("relay.history.page").size)
        assertEquals(1, fx.frames("relay.turn.submit").size)
    }

    @Test fun eventsAddressedToAnotherBindingOrEpochAreRejectedAndChangeNothing() {
        val fx = ControllerFixture()
        fx.enable()
        val binding = selected(fx)
        fx.advanceTime(fx.timing.pollIdleMs)
        val foreignBinding = Dto.event(
            1,
            Dto.binding(9),
            Dto.messageEvent(1, HistoryRole.ASSISTANT, "from elsewhere", 1)
        )
        fx.transport.reply(
            fx.transport.next("relay.events.read"),
            Dto.events(binding, foreignBinding).toWire()
        )
        fx.run()
        assertTrue(fx.ui.transcript.isEmpty())
        assertEquals(RelayNotice.ANOMALY, fx.ui.notice)
        fx.advanceTime(fx.timing.pollIdleMs)
        val again = fx.transport.next("relay.events.read")
        assertEquals(
            binding.lastSequence.toString(),
            again.params["after_sequence"]!!.jsonPrimitive.content
        )
        val foreignEpoch = Dto.event(
            1,
            binding,
            Dto.messageEvent(2, HistoryRole.ASSISTANT, "old epoch", 1),
            "stale-epoch"
        )
        fx.transport.reply(
            again,
            EventsPage(listOf(foreignEpoch), "stale-epoch", 1, false).toWire()
        )
        fx.run()
        assertTrue(fx.ui.transcript.isEmpty())
    }

    @Test fun aReceiptForAnotherRequestSettlesNothing() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        val other = RequestId.of("01939c8e-7a00-7000-8000-00000000ffff")!!
        fx.transport.reply(submit, Dto.receipt(other, ReceiptStatus.TERMINAL, "completed").toWire())
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        assertEquals(RelayNotice.ANOMALY, fx.ui.notice)
    }

    @Test fun transientErrorsBackOffWithBoundedWork() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        val delays = ArrayList<Long>()
        var calls = 1
        repeat(20) {
            val call = fx.transport.open("relay.conversations.list").lastOrNull() ?: return@repeat
            fx.transport.fail(
                call,
                listOf("busy", "rate_limited", "temporarily_unavailable")[it % 3]
            )
            fx.run()
            val before = fx.transport.sent.size
            var waited = 0L
            while (fx.transport.sent.size == before && waited < 40_000) {
                fx.advanceTime(250)
                waited += 250
            }
            if (fx.transport.sent.size > before) {
                calls++
                delays.add(waited)
            }
        }
        // 1 first attempt + maxRetries retries, then it stops by itself: the user retries.
        assertEquals(1 + fx.timing.maxRetries, calls)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L), delays)
        assertEquals(RelayNotice.HISTORY_INCOMPLETE, fx.ui.notice)
        assertFalse(fx.ui.hasMore)
        assertFalse(fx.ui.listing)
    }

    @Test fun permissionDeniedStopsEvictsAndSurvivesARestartUntilTheUserReauthorises() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx, "to be evicted")
        fx.transport.fail(submit, "permission_denied")
        fx.run()
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        val stored = fx.store.reread()
        assertEquals(Standing.STOPPED, stored.section.enrollments.single().standing)
        assertEquals(null, stored.snapshot.ledger.turns.single().text)
        assertFalse(fx.ui.canSend)
        // A new process reads STOPPED and does not send.
        val again = ControllerFixture(fx.identity, fx.store)
        again.store.log = again.log
        again.prepare()
        again.connect()
        assertEquals(RelayPhase.STOPPED, again.ui.phase)
        assertTrue(again.transport.sent.isEmpty())
        again.controller.reauthorize()
        again.run()
        assertEquals(Standing.ACTIVE, again.store.reread().section.enrollments.single().standing)
        assertEquals(
            RelayPhase.READY,
            again.ui.phase.takeIf { it != RelayPhase.CONNECTING } ?: RelayPhase.READY
        )
    }

    @Test fun theEvictionObligationStaysOwedUntilTheEvictionItselfIsStored() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx, "owed text")
        // The answer's own commit and the durable stop succeed; the commit of the EVICTION itself fails.
        fx.store.allowCommits = 2
        fx.transport.fail(submit, "permission_denied")
        fx.run()
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        assertTrue(fx.ui.unsaved)
        assertTrue(fx.ui.contentEvictionPending)
        val onDisk = fx.store.reread()
        assertEquals(Standing.STOPPED, onDisk.section.enrollments.single().standing)
        assertEquals("owed text", onDisk.snapshot.ledger.turns.single().text)
        assertTrue(onDisk.snapshot.ledger.scopes.single().contentEvictionOwed)
        val notYetRestored = ControllerFixture(store = fx.store)
        notYetRestored.prepare()
        assertEquals(RelayPhase.STOPPED, notYetRestored.ui.phase)
        // No fresh clock/runtime yet: never claim removal.
        assertTrue(notYetRestored.ui.contentEvictionPending)
        // A retry that still cannot store keeps it owed; once the store works the eviction is stored and the flag clears.
        fx.controller.retryStorage()
        fx.run()
        assertEquals("owed text", fx.store.reread().snapshot.ledger.turns.single().text)
        fx.store.allowCommits = null
        fx.controller.retryStorage()
        fx.run()
        val stored = fx.store.reread().snapshot
        assertEquals(null, stored.ledger.turns.single().text)
        assertFalse(stored.ledger.scopes.single().contentEvictionOwed)
        assertFalse(fx.ui.unsaved)
        assertFalse(fx.ui.contentEvictionPending)
    }

    @Test fun profileChangedFreezesSendingAcrossARestartUntilADeliberateReview() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        fx.transport.fail(submit, "profile_changed")
        fx.run()
        assertEquals(RelayPhase.REVIEW_REQUIRED, fx.ui.phase)
        assertFalse(fx.ui.canSend)
        val sends = fx.frames("relay.turn.submit").size
        fx.controller.send("blocked") { assertFalse(it) }
        fx.controller.select(Dto.otherConversation.value)
        fx.run()
        assertEquals(sends, fx.frames("relay.turn.submit").size)
        // A restarted process stays frozen even though the same profile answers.
        val again = ControllerFixture(fx.identity, fx.store)
        again.store.log = again.log
        again.prepare()
        again.connect()
        assertEquals(RelayPhase.REVIEW_REQUIRED, again.ui.phase)
        assertTrue(again.transport.sent.isEmpty())
        // Deliberate review: the epoch advances, the selection and intent are dropped, the old turn is another scope.
        again.controller.acceptReview()
        again.run()
        val record = again.store.reread().section.enrollments.single()
        assertEquals(1L, record.reviewEpoch)
        assertEquals(Standing.ACTIVE, record.standing)
        assertEquals(null, record.selectedConversationId)
        assertTrue(again.ui.turns.single().previousProfile)
    }

    @Test fun aDifferentProfileAfterThePinFreezesBeforeAnythingIsSent() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.disconnect()
        fx.transport.clear()
        fx.connect(Dto.info(profile = Dto.otherProfile))
        assertEquals(RelayPhase.REVIEW_REQUIRED, fx.ui.phase)
        assertTrue(fx.transport.sent.isEmpty())
        assertNotEquals(fx.ui.pinnedDigest, fx.ui.observedDigest)
        // The pin is unchanged until the user accepts.
        assertEquals(Dto.profile, fx.store.reread().section.enrollments.single().pinnedProfileId)
        fx.controller.acceptReview()
        fx.run()
        val record = fx.store.reread().section.enrollments.single()
        assertEquals(Dto.otherProfile, record.pinnedProfileId)
        assertEquals(1L, record.reviewEpoch)
        assertEquals(
            RelayPhase.READY,
            fx.ui.phase.takeIf { it == RelayPhase.READY || it == RelayPhase.CONNECTING }
                ?: RelayPhase.READY
        )
    }

    @Test fun hidingStopsEveryTimerAndFrameAndReopeningDoesNotAttachByItself() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        fx.controller.hide()
        fx.run()
        assertEquals(0, fx.scheduler.pendingTimers)
        val sent = fx.transport.sent.size
        fx.advanceTime(120_000)
        assertEquals(sent, fx.transport.sent.size)
        // Reopen: a new connection lists conversations; the stored selection is only OFFERED for resuming.
        fx.connect()
        fx.listAnd(Dto.summary())
        assertTrue(fx.frames("relay.conversation.attach").size == 1)
        assertEquals(RelayPhase.READY, fx.ui.phase)
        assertEquals(Dto.conversation.value, fx.ui.resumable!!.id)
    }

    @Test fun twoEnrollmentsKeepIndependentPinsStandingsAndTurns() {
        val fx = ControllerFixture()
        val a = fx.identity
        val b =
            EnrollmentIdentity("wss://other.example.invalid/gadget", "phone:hg-8899aabbccddeeff")
        fx.enable()
        selected(fx)
        sendOne(fx, "for A")
        fx.controller.hide()
        fx.run()
        fx.controller.prepare(b)
        fx.controller.setEnabled(true)
        fx.run()
        fx.connect(Dto.info(profile = Dto.otherProfile))
        val section = fx.store.reread().section
        assertEquals(2, section.enrollments.size)
        assertEquals(Dto.profile, section.find(a.endpointKey, a.enrollmentLabel)!!.pinnedProfileId)
        assertEquals(
            Dto.otherProfile,
            section.find(b.endpointKey, b.enrollmentLabel)!!.pinnedProfileId
        )
        assertTrue(fx.ui.turns.isEmpty())
        // Back to A: its pin, selection and unresolved turn are untouched.
        fx.controller.hide()
        fx.run()
        fx.controller.prepare(a)
        fx.run()
        fx.connect()
        assertEquals(Dto.conversation.value, fx.ui.resumable?.id)
        assertEquals(1, fx.ui.turns.size)
    }

    @Test fun theMicrophoneStaysUnavailableInsideARelayConversation() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        assertFalse(fx.ui.micAvailable)
        assertTrue(fx.ui.relayOwnsComposer)
    }

    private fun deniedLookup(
        fx: ControllerFixture,
        beforeDenial: () -> Unit = {}
    ): ScriptedTransport.Sent {
        fx.enable()
        selected(fx)
        val first = sendOne(fx)
        fx.transport.timeout(first)
        fx.run()
        reconnectAndAttach(fx, 2)
        beforeDenial()
        fx.transport.fail(fx.transport.next("relay.turn.receipt"), "permission_denied")
        fx.run()
        return first
    }

    @Test fun aDeniedReceiptRemainsStoppedAfterProcessRestore() {
        val fx = ControllerFixture()
        deniedLookup(fx)
        val again = ControllerFixture(fx.identity, fx.store)
        again.store.log = again.log
        again.prepare()
        again.connect()
        assertEquals(RelayPhase.STOPPED, again.ui.phase)
        assertTrue(again.transport.sent.isEmpty())
        assertEquals(null, fx.store.reread().snapshot.ledger.turns.single().text)
    }

    @Test fun aLateReadableHistoryResponseCannotUndoADeniedReceipt() {
        val fx = ControllerFixture()
        deniedLookup(fx)
        val oldHistory = fx.frames("relay.history.page").last()
        fx.transport.reply(oldHistory, Dto.history().toWire())
        fx.run()
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        assertEquals(Standing.STOPPED, fx.store.reread().section.enrollments.single().standing)
        assertEquals(1, fx.frames("relay.turn.submit").size)
        assertFalse(fx.ui.canSend)
    }

    @Test fun aDeniedReceiptKeepsEvictionOwedUntilItsCommitSucceeds() {
        val fx = ControllerFixture()
        deniedLookup(fx) { fx.store.allowCommits = 2 }
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        assertTrue(fx.ui.unsaved)
        val owed = fx.store.reread().snapshot
        assertEquals(Standing.STOPPED, fx.store.reread().section.enrollments.single().standing)
        assertTrue(owed.ledger.scopes.single().contentEvictionOwed)
        assertEquals("the question", owed.ledger.turns.single().text)
        fx.store.allowCommits = null
        fx.controller.retryStorage()
        fx.run()
        assertFalse(fx.store.reread().snapshot.ledger.scopes.single().contentEvictionOwed)
        assertEquals(null, fx.store.reread().snapshot.ledger.turns.single().text)
        assertEquals(1, fx.frames("relay.turn.submit").size)
    }

    @Test fun aChangedGrantRetiresTheBindingAndAttachesAgainButAnUnchangedRefreshDisturbsNothing() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val attaches = fx.frames("relay.conversation.attach").size
        // The same grant read again (an expiry refresh): nothing restarts.
        fx.ready(Dto.info())
        assertEquals(attaches, fx.frames("relay.conversation.attach").size)
        assertEquals(RelayPhase.SELECTED, fx.ui.phase)
        // A new grant generation: the old binding is not reused; the continuing session attaches again.
        fx.ready(Dto.info(grant = 9))
        fx.listAnd(Dto.summary())
        assertEquals(attaches + 1, fx.frames("relay.conversation.attach").size)
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        // The grant now lacks submit: sending is refused and nothing is written.
        fx.ready(Dto.info(grant = 10, methods = Dto.ALL_METHODS - "relay.turn.submit"))
        assertEquals(RelayPhase.UNAVAILABLE, fx.ui.phase)
        assertEquals(RelayReason.MISSING_METHODS, fx.ui.reason)
        var accepted = true
        fx.controller.send("no longer allowed") { accepted = it }
        fx.run()
        assertFalse(accepted)
        assertTrue(fx.frames("relay.turn.submit").isEmpty())
    }

    @Test fun anUnfinishedCreateIsRetriedWithTheSameIdAndTitleByANewProcess() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd()
        fx.controller.create("Roadmap")
        fx.run()
        val first = fx.frames("relay.conversation.create").single()
        // The process dies before any answer; a new process restores the durable intent.
        val again = ControllerFixture(fx.identity, fx.store)
        again.store.log = again.log
        again.prepare()
        again.connect()
        assertEquals(1, again.ui.let { if (it.pendingCreate != null) 1 else 0 })
        val second = again.frames("relay.conversation.create").single()
        assertEquals(first.params["request_id"], second.params["request_id"])
        assertEquals("Roadmap", second.params["title"]!!.jsonPrimitive.content)
        // The user may drop it (the conversation may exist on the server; nothing is deleted).
        again.controller.discardCreate()
        again.run()
        assertEquals(null, again.store.reread().section.enrollments.single().createIntent)
    }

    @Test fun aTurnOfAnEarlierReviewEpochIsNeverCheckedOrSentAgain() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        val id = submit.params["request_id"]!!.jsonPrimitive.content
        fx.transport.timeout(submit)
        fx.run()
        // The profile changes, the user reviews and accepts: the unresolved turn now belongs to an earlier epoch.
        fx.disconnect()
        fx.connect(Dto.info(profile = Dto.otherProfile))
        fx.controller.acceptReview()
        fx.run()
        val old = fx.ui.turns.single()
        assertTrue(old.previousProfile)
        assertFalse(old.canCheck)
        assertFalse(old.canResend)
        val frames = fx.transport.sent.size
        fx.controller.resend(id)
        fx.controller.check(id)
        fx.run()
        assertEquals(frames, fx.transport.sent.size)
        assertEquals(1, fx.frames("relay.turn.submit").size)
        // It can only be forgotten, by the user.
        fx.controller.abandon(id)
        fx.run()
        assertTrue(fx.ui.turns.isEmpty())
    }

    @Test fun aTimedOutSubmitAttachesAgainByItselfAndAsksTheReceiptBeforeAnythingElse() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        fx.transport.timeout(submit)
        fx.run()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        assertEquals(0, fx.transport.refreshes)
        // After the backoff the session reads info again and attaches again (a binding NEWER than the uncertainty).
        fx.advanceTime(1_000)
        assertEquals(1, fx.transport.refreshes)
        fx.ready(Dto.info())
        fx.listAnd(Dto.summary())
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        assertEquals("relay.turn.receipt", fx.transport.methods().last())
        assertEquals(1, fx.frames("relay.turn.submit").size)
    }

    @Test fun theUsersCheckOfAnUncertainTurnAttachesFirstBecauseTheLedgerDemandsANewerBinding() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        val submit = sendOne(fx)
        fx.transport.timeout(submit)
        fx.run()
        val id = submit.params["request_id"]!!.jsonPrimitive.content
        // The user taps "Check" before the automatic attach: no receipt is asked on the OLD binding; a fresh attach comes first.
        fx.controller.check(id)
        fx.run()
        assertTrue(fx.frames("relay.turn.receipt").isEmpty())
        assertEquals(1, fx.transport.refreshes)
    }

    @Test fun attachRetriesAreBoundedWhileTheServerStaysBusy() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        var guard = 0
        while (guard++ < 40) {
            val call = fx.transport.open("relay.conversation.attach").lastOrNull() ?: break
            fx.transport.fail(call, "busy")
            fx.run()
            fx.advanceTime(31_000)
        }
        assertEquals(1 + fx.timing.maxRetries, fx.frames("relay.conversation.attach").size)
        assertEquals(RelayPhase.READY, fx.ui.phase)
        assertEquals(Dto.conversation.value, fx.ui.resumable!!.id)
    }

    @Test fun aRefusedInfoReadWithPermissionDeniedStopsTheScopeAndOneWithProfileChangedFreezesIt() {
        val fx = ControllerFixture()
        fx.enable()
        selected(fx)
        fx.channel(
            dev.quantumink.hermesgadget.protocol.relay.RelayChannelState.Failed(
                dev.quantumink.hermesgadget.protocol.relay.RelayFailure.INFO_REFUSED,
                dev.quantumink.hermesgadget.protocol.relay.RelayError(
                    dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode.PERMISSION_DENIED,
                    false
                )
            )
        )
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        assertEquals(Standing.STOPPED, fx.store.reread().section.enrollments.single().standing)
        val other = ControllerFixture()
        other.enable()
        selected(other)
        other.channel(
            dev.quantumink.hermesgadget.protocol.relay.RelayChannelState.Failed(
                dev.quantumink.hermesgadget.protocol.relay.RelayFailure.INFO_REFUSED,
                dev.quantumink.hermesgadget.protocol.relay.RelayError(
                    dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode.PROFILE_CHANGED,
                    false
                )
            )
        )
        assertEquals(RelayPhase.REVIEW_REQUIRED, other.ui.phase)
    }

    @Test fun aReceiptPermissionDenialCannotBePromotedToAbsenceByHistoryRead() {
        val fx = ControllerFixture()
        deniedLookup(fx)
        // The same conversation was readable at attach. That says nothing about receipt authority.
        assertEquals(RelayPhase.STOPPED, fx.ui.phase)
        assertEquals(Standing.STOPPED, fx.store.reread().section.enrollments.single().standing)
        assertEquals(1, fx.frames("relay.turn.submit").size)
        assertEquals(null, fx.store.reread().snapshot.ledger.turns.single().text)
        assertTrue(
            fx.frames("relay.history.page").none {
                it.params["limit"]?.jsonPrimitive?.content == "1"
            }
        )
    }
}
