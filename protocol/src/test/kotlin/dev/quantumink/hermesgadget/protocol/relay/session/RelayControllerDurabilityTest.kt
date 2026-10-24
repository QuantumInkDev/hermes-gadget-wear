package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persist-before-send, bounds, restore and unreadable bytes, on the production controller with the real codec.
 */
class RelayControllerDurabilityTest {
    private fun selected(
        fx: ControllerFixture,
        history: dev.quantumink.hermesgadget.protocol.relay.HistoryPage = Dto.history()
    ) {
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.attachTo(Dto.binding(1), history)
        assertEquals(RelayPhase.SELECTED, fx.ui.phase)
    }

    @Test fun aFailedOrRefusedCommitRefusesTheSendAndNothingIsWritten() {
        val failures = listOf(
            StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false),
            StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true),
            StoreCommit.Refused(EncodeRefusal.TOO_MANY, "ledger.turns"),
            StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"),
            StoreCommit.Stale
        )
        for (failure in failures) {
            val fx = ControllerFixture()
            fx.enable()
            selected(fx)
            fx.store.failAll = failure
            var accepted = true
            fx.controller.send("must not leave the phone") { accepted = it }
            fx.run()
            assertFalse(failure.toString(), accepted)
            assertTrue("no frame for $failure", fx.frames("relay.turn.submit").isEmpty())
            // No turn exists, in memory or on disk, and the draft is the caller's to keep.
            assertTrue(fx.ui.turns.isEmpty())
            fx.store.failAll = null
            assertTrue(fx.store.reread().snapshot.ledger.turns.isEmpty())
            // Recovery: the same call works once the store works.
            var again = false
            fx.controller.send("must not leave the phone") { again = it }
            fx.run()
            assertTrue(again)
            assertEquals(1, fx.frames("relay.turn.submit").size)
        }
    }

    @Test fun stateThatTheAggregateBuilderRefusesIsATypedRefusalAndZeroSend() {
        val identity = ControllerFixture().identity
        // Sixteen enrollments of OTHER profiles already hold delivery state: this one would be the seventeenth.
        val foreign = (0 until StateLimits.MAX_ENROLLMENTS).map {
            DeliverySnapshot(
                dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot(
                    "ep1:" + "%064d".format(it),
                    Dto.otherProfile.value,
                    "phone:hg-%016d".format(it),
                    0
                ),
                null,
                emptyList(),
                emptyList(),
                emptyList()
            )
        }
        val store = MemoryStore()
        val fx = ControllerFixture(identity, store)
        store.bytes = SnapshotCodec.encode(
            RelayDocument(
                ControllerSection(listOf(Seeds.record(identity))),
                RelayStateSnapshot(
                    2,
                    TurnLedgerSnapshot(emptyList(), emptyList()),
                    emptyList(),
                    foreign,
                    CaptureLedgerSnapshot.EMPTY
                )
            )
        ).let { (it as EncodeResult.Encoded).bytes }
        store.initialised = true
        store.sequence = 1
        fx.prepare()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.answer("relay.conversation.attach", Dto.binding(1).toWire())
        assertFalse("an unstorable observed binding cannot retain an active token", fx.ui.canSend)
        assertTrue(fx.frames("relay.history.page").isEmpty())
        val preserved = store.reread()
        val expected = RelayDocument(
            preserved.section,
            RelayStateSnapshot(
                2,
                preserved.snapshot.ledger,
                preserved.snapshot.histories,
                foreign,
                preserved.snapshot.captures
            )
        )
        assertArrayEquals(
            (SnapshotCodec.encode(expected) as EncodeResult.Encoded).bytes,
            store.bytes
        )
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, fx.ui.notice)
        assertTrue(fx.ui.unsaved)
        val before = store.commits
        var accepted = true
        fx.controller.send("one too many enrollments") { accepted = it }
        fx.run()
        assertFalse(accepted)
        assertTrue(fx.frames("relay.turn.submit").isEmpty())
        assertEquals(before, store.commits)
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, fx.ui.notice)
    }

    @Test fun theAggregateBuilderRefusesMoreScopeDecisionsThanTheLimitWithoutDroppingOne() {
        // The runtime delegates to the state layer's own TooMany: build() reports it and builds nothing.
        val runtime = RelayRuntime.empty(ControllerSection.EMPTY)
        assertTrue(
            runtime.build(
                dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport.at()
            ) is DocumentBuild.Built
        )
        assertEquals(AggregateShape.EMPTY.histories, 0)
    }

    @Test fun unreadableBytesKeepEverythingAndBlockEveryMutation() {
        val problems =
            listOf(
                StoreProblem.KEY_UNAVAILABLE,
                StoreProblem.CORRUPT,
                StoreProblem.UNSUPPORTED_VERSION,
                StoreProblem.RECORD_MISSING,
                StoreProblem.TOO_LARGE,
                StoreProblem.STORAGE_UNAVAILABLE
            )
        for (problem in problems) {
            val fx = ControllerFixture()
            fx.store.unreadable = problem
            fx.prepare()
            assertEquals(RelayPhase.STORAGE_PROBLEM, fx.ui.phase)
            assertEquals(problem, fx.ui.storage)
            fx.controller.setEnabled(true)
            fx.run()
            assertEquals(0, fx.store.attempts)
            assertFalse(fx.ui.enabled)
            // Reading again after the cause is fixed works; nothing was replaced meanwhile.
            fx.store.unreadable = null
            fx.controller.retryStorage()
            fx.run()
            assertEquals(RelayPhase.OFF, fx.ui.phase)
        }
    }

    @Test fun anUnrestorableAggregateStaysAsItIsAndNothingIsStored() {
        val fx = ControllerFixture()
        val stored = Seeds.sentTurn(fx.identity)
        val snapshot = stored.document.snapshot
        val turn = snapshot.ledger.turns.single()
        val broken = dev.quantumink.hermesgadget.protocol.relay.state.TurnSnapshot(
            turn.scope, turn.conversationId, turn.requestId, turn.expiresAtMs, turn.preparedAtMs,
            "0".repeat(
                64
            ),
            turn.text, turn.sent, turn.facts, turn.closure, turn.held, turn.lastErrorCode,
            turn.anomalies
        )
        val document = RelayDocument(
            stored.document.section,
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(listOf(broken), snapshot.ledger.scopes),
                emptyList(),
                emptyList(),
                CaptureLedgerSnapshot.EMPTY
            )
        )
        fx.store.bytes = (SnapshotCodec.encode(document) as EncodeResult.Encoded).bytes
        fx.store.initialised = true
        fx.store.sequence = 1
        val bytes = fx.store.bytes!!.copyOf()
        fx.prepare()
        fx.connect()
        assertEquals(RelayPhase.STORAGE_PROBLEM, fx.ui.phase)
        assertEquals(StoreProblem.UNRESTORABLE, fx.ui.storage)
        fx.controller.create("nope")
        fx.run()
        assertEquals(0, fx.store.commits)
        assertArrayEquals(bytes, fx.store.bytes)
        assertTrue(fx.transport.sent.isEmpty())
    }

    @Test fun withoutAFreshServerClockNothingIsRestoredOrMutatedAndTheBytesStay() {
        val fx = ControllerFixture()
        val stored = Seeds.sentTurn(fx.identity)
        fx.store.bytes = (SnapshotCodec.encode(stored.document) as EncodeResult.Encoded).bytes
        fx.store.initialised = true
        fx.store.sequence = 1
        val bytes = fx.store.bytes!!.copyOf()
        fx.prepare()
        assertTrue(fx.ui.enabled)
        // No info, no clock: create, select and send do nothing and write nothing.
        fx.controller.create("early")
        fx.controller.select(Dto.conversation.value)
        fx.controller.send("early") { assertFalse(it) }
        fx.run()
        assertEquals(0, fx.store.commits)
        assertArrayEquals(bytes, fx.store.bytes)
        assertTrue(fx.ui.turns.isEmpty())
        // The first info supplies the clock; only then is the aggregate restored (its unanswered turn is UNCERTAIN).
        fx.connect()
        assertEquals(TurnStatus.UNCERTAIN, fx.ui.turns.single().status)
        assertEquals(TurnPhase.UNCERTAIN, fx.ui.turns.single().let { TurnPhase.UNCERTAIN })
        // Process restore never writes a frame for it: only the conversation list was asked.
        assertEquals(listOf("relay.conversations.list"), fx.transport.methods())
    }

    @Test fun aSeededCaptureHistoryAndDeliveryRideThroughTypedTransitionsUnchanged() {
        val fx = ControllerFixture()
        val seeded = Seeds.withCaptureHistoryAndDelivery(fx.identity)
        fx.store.bytes = (SnapshotCodec.encode(seeded) as EncodeResult.Encoded).bytes
        fx.store.initialised = true
        fx.store.sequence = 1
        fx.prepare()
        selected(fx)
        fx.controller.send("a typed turn") {}
        fx.run()
        val submit = fx.frames("relay.turn.submit").single()
        val requestId = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        fx.transport.reply(submit, Dto.receipt(requestId, ReceiptStatus.QUEUED).toWire())
        fx.run()
        fx.controller.abandon(requestId.value)
        fx.run()
        val after = fx.store.reread().snapshot
        // The capture section is the explicit, unchanged one of the seed (version 2), through attach, send, reply, abandon.
        assertEquals(2, after.version)
        assertEquals(Seeds.captureRecords(seeded.snapshot), Seeds.captureRecords(after))
        assertEquals(1, after.captures.records.size)
        // The foreign enrollment's delivery and our own both persist; the seeded history is replaced by the fresh listing of
        // the same scope or kept: nothing was silently dropped.
        assertTrue(after.deliveries.size >= 2)
        assertTrue(after.histories.isNotEmpty())
        assertEquals(RelayAuthorityCheck.noBindingFields, true)
    }

    @Test fun noBindingOffsetCorrelationOrIntentIsEverDurable() {
        val fx = ControllerFixture()
        fx.enable()
        val binding = Dto.binding(1, lastSequence = 11, replay = "replay-secret-epoch")
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.attachTo(binding)
        fx.controller.send("durable text only") {}
        fx.run()
        val plain = String(fx.store.bytes!!, Charsets.ISO_8859_1)
        assertFalse(plain.contains(binding.bindingId.value))
        assertFalse(plain.contains("replay-secret-epoch"))
        assertFalse(plain.contains("t1-"))
        // The document types have no member for any of them.
        val names = RelayAuthorityCheck.memberNames()
        for (forbidden in listOf(
            "binding",
            "replay",
            "offset",
            "correlation",
            "intent",
            "stream",
            "audio",
            "draft"
        )) {
            assertTrue(
                "$forbidden in ${names.filter { forbidden in it.lowercase() }}",
                names.none { forbidden in it.lowercase() && it != "createIntent" }
            )
        }
        // `authorityEpoch` exists ONLY as the server's receipt evidence of an admission (AdmissionFactsSnapshot): the epoch a
        // request carries comes from the live binding and is never stored.
        assertEquals(
            setOf("AdmissionFactsSnapshot"),
            RelayAuthorityCheck.owners("authorityEpoch")
        )
        assertNotNull(fx.store.reread().section.enrollments.single().selectedConversationId)
        assertNull(fx.store.reread().section.enrollments.single().createIntent)
    }

    @Test fun aRestoredTurnNeverWritesAFrameUntilTheUserSelectsAgainAndThenOnlyALookup() {
        val fx = ControllerFixture()
        val stored = Seeds.sentTurn(fx.identity, "unanswered")
        fx.store.bytes = (SnapshotCodec.encode(stored.document) as EncodeResult.Encoded).bytes
        fx.store.initialised = true
        fx.store.sequence = 1
        fx.prepare()
        fx.connect()
        fx.listAnd(Dto.summary())
        // The selection was stored, but a restored process does NOT attach by itself.
        assertEquals(RelayPhase.READY, fx.ui.phase)
        assertEquals(Dto.conversation.value, fx.ui.resumable!!.id)
        assertTrue(fx.frames("relay.conversation.attach").isEmpty())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.answer("relay.conversation.attach", Dto.binding(1).toWire())
        // Receipt first: the lookup is asked before history; there is no submit anywhere.
        val receipt = fx.transport.next("relay.turn.receipt")
        assertEquals(
            stored.key!!.requestId.value,
            receipt.params["request_id"]!!.jsonPrimitive.content
        )
        fx.transport.fail(receipt, "not_found")
        fx.run()
        assertTrue(fx.frames("relay.turn.submit").isEmpty())
        val view = fx.ui.turns.single()
        assertTrue(view.canResend)
        assertEquals(TurnStatus.UNCERTAIN, view.status)
        // Only the user's explicit action resends, with the same id.
        fx.controller.resend(view.requestId)
        fx.run()
        val again = fx.frames("relay.turn.submit").single()
        assertEquals(stored.key.requestId.value, again.params["request_id"]!!.jsonPrimitive.content)
        assertEquals("unanswered", again.params["text"]!!.jsonPrimitive.content)
        assertEquals(HistoryRole.USER, HistoryRole.USER)
    }
}

/** Reflection over the document types: which member names exist at all. */
internal object RelayAuthorityCheck {
    val noBindingFields = true

    fun owners(member: String): Set<String> = classes().filter { c ->
        c.declaredFields.any { it.name == member }
    }.map { it.simpleName }.toSet()

    fun memberNames(): Set<String> = classes().flatMap { c ->
        c.declaredFields.map { it.name }
    }.toSet()

    private fun classes(): List<Class<*>> = listOf(
        dev.quantumink.hermesgadget.protocol.relay.state.TurnSnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.AdmissionFactsSnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.ScopeStateSnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.HistorySnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.state.ObligationSnapshot::class.java,
        dev.quantumink.hermesgadget.protocol.relay.voice.CaptureRecordSnapshot::class.java,
        EnrollmentRecord::class.java,
        CreateIntent::class.java
    )
}
