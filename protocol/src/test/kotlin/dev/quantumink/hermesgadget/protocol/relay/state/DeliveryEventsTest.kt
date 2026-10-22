package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.ExecutionState
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayEvent
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live event application against the RECORDED `relay.events.read` frames: stage, then commit; never act. */
class DeliveryEventsTest {
    private val ok7 = DeliveryHarness.events("ok-07") // six `changed` events after sequence 0

    // A delete tombstone at sequence 7 after 5, snapshot_required
    private val ok8 = DeliveryHarness.events("ok-08")
    private val ok10 = DeliveryHarness.events("ok-10") // a gap at sequence 2 after 0
    private val ok6 = DeliveryHarness.events("ok-06") // a cursor the server does not recognise
    private val ok24 = DeliveryHarness.events("ok-24") // 28 gap events after sequence 28

    @Test fun everyAttachOwesAFreshHistoryAndTaskSnapshotAndRefreshedClearsExactlyThose() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        assertEquals(
            setOf(
                RefreshKind.HISTORY to RefreshCause.ATTACHED,
                RefreshKind.TASKS to RefreshCause.ATTACHED
            ),
            h.delivery.refreshOwed.map { it.kind to it.cause }.toSet()
        )
        assertTrue(h.delivery.refreshOwed.all { it.conversation == token.conversation })
        assertTrue(h.effects.any { it is DeliveryEffect.RefreshRequired })
        // Refreshing one conversation, or one kind, clears only what matches.
        val otherConversation =
            ConversationScope(
                h.enrollment,
                StateTestSupport.conversationId("11111111-2222-4333-8444-555555555555")
            )
        assertEquals(
            0,
            h.apply(
                h.delivery.refreshed(
                    otherConversation,
                    setOf(RefreshKind.HISTORY, RefreshKind.TASKS)
                )
            )
        )
        assertEquals(
            1,
            h.apply(h.delivery.refreshed(token.conversation, setOf(RefreshKind.HISTORY)))
        )
        assertEquals(listOf(RefreshKind.TASKS), h.delivery.refreshOwed.map { it.kind })
        assertEquals(1, h.apply(h.delivery.refreshed(null, setOf(RefreshKind.TASKS))))
        assertTrue(h.delivery.refreshOwed.isEmpty())
    }

    @Test fun aRecordedPullIsStagedAndNothingAdvancesUntilTheConsumerCommits() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        val plan = h.planRead(limit = 50)
        // Our request is exactly the recorded one.
        assertEquals(ok7.params, plan.call.params)
        val batch = h.staged(h.stage(plan, ok7))
        assertEquals(6, batch.events.size)
        assertEquals(6, batch.highSequence)
        assertTrue(batch.events.all { it.payload is EventPayload.Changed })
        // Staged only: no cursor advance, no remembered ids, no obligation beyond the attach, no further read.
        assertEquals(0L, h.delivery.committedSequence)
        assertEquals(0, h.delivery.seenCount)
        assertEquals(SessionRefusal.BATCH_OUTSTANDING, h.readRefusal())
        h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        assertEquals(6L, h.delivery.committedSequence)
        assertEquals(6, h.delivery.seenCount)
        // Only now may the read cursor acknowledge (and free) the older events: the next read carries sequence 6.
        val next = h.planRead()
        assertEquals(6, next.call.params["after_sequence"]!!.jsonPrimitive.content.toInt())
        assertTrue(h.delivery.refreshOwed.any { it.cause == RefreshCause.CHANGED_EVENT })
        assertEquals(
            token.conversation,
            h.delivery.refreshOwed.first {
                it.cause ==
                    RefreshCause.CHANGED_EVENT
            }.conversation
        )
    }

    @Test fun anAbortLeavesCursorAndIdentitiesAloneSoTheSameEventsAreReadAndStagedAgain() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok7))
        val first = h.staged(h.stage(h.planRead(50), ok7))
        assertTrue(h.apply(h.delivery.abortEvents(first.batchId)))
        assertEquals(0L, h.delivery.committedSequence)
        assertEquals(0, h.delivery.seenCount)
        val again = h.planRead(50)
        assertEquals(0, again.call.params["after_sequence"]!!.jsonPrimitive.content.toInt())
        val second = h.staged(h.stage(again, ok7))
        assertEquals(6, second.events.size) // not considered duplicates: nothing was committed
        assertEquals(0, second.duplicates)
    }

    @Test fun aRecordedGapEventDemandsARefreshAndRetiresTheBindingWithNoExecutionEffect() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok10))
        val batch = h.staged(h.stage(h.planRead(50), ok10))
        assertEquals(listOf(EventPayload.Gap), batch.events.map { it.payload })
        assertEquals(2, batch.highSequence)
        assertTrue(batch.retiresBinding)
        // The attach's own debt
        h.apply(h.delivery.refreshed(null, setOf(RefreshKind.HISTORY, RefreshKind.TASKS)))
        h.effects.clear()
        h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        assertEquals(
            setOf(RefreshKind.HISTORY, RefreshKind.TASKS),
            h.delivery.refreshOwed.filter {
                it.cause == RefreshCause.GAP_EVENT
            }.map { it.kind }.toSet()
        )
        assertEquals(SessionRefusal.BINDING_RETIRED, h.readRefusal())
        assertTrue(h.effects.contains(DeliveryEffect.ReattachRequired(h.enrollment)))
        // Everything the state layer asked for is a refresh or a re-attach: never a send, answer or speech.
        assertTrue(
            h.effects.all {
                it is DeliveryEffect.PersistDelivery || it is DeliveryEffect.RefreshRequired ||
                    it is DeliveryEffect.ReattachRequired
            }
        )
    }

    @Test fun aRecordedDeleteTombstoneIsStagedAndInvalidatesTheConversationOnlyOnCommit() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok8))
        val batch = h.staged(h.stage(h.planRead(50), ok8))
        assertTrue(batch.conversationDeleted)
        assertTrue(batch.retiresBinding)
        // Sequence 6 was replaced by the tombstone: not a discontinuity
        assertEquals(7, batch.highSequence)
        assertTrue(h.effects.none { it is DeliveryEffect.ConversationDeleted })
        h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        assertTrue(h.effects.contains(DeliveryEffect.ConversationDeleted(token.conversation)))
        assertTrue(h.delivery.refreshOwed.any { it.cause == RefreshCause.CONVERSATION_DELETED })
    }

    @Test fun aRecordedAnswerForACursorTheServerDoesNotRecogniseIsAGapNeverEvents() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok6))
        val outcome = h.stage(h.planRead(), ok6)
        assertEquals(RefreshCause.EPOCH_MISMATCH, (outcome as StageOutcome.NeedsReattach).cause)
        assertFalse(h.delivery.hasSession)
        assertTrue(h.delivery.refreshOwed.any { it.cause == RefreshCause.EPOCH_MISMATCH })
        assertEquals(SessionRefusal.NO_SESSION, h.readRefusal())
    }

    @Test fun aRecordedRunOfGapEventsIsStagedInServerOrder() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok24))
        val batch = h.staged(h.stage(h.planRead(50), ok24))
        assertEquals((29L..56L).toList(), batch.events.map { it.sequence })
        assertEquals(56, batch.highSequence)
        assertEquals(28, batch.events.map { it.eventId }.toSet().size)
    }

    @Test fun recordedTaskAndPromptEventsAreDisplayOnlyAndNeverActionable() {
        val taskRuns = DeliveryHarness.events("ok-11")
        val prompt = DeliveryHarness.events("ok-13")
        val needsInput = DeliveryHarness.events("ok-14")
        for (case in listOf(taskRuns, prompt, needsInput)) {
            val h = DeliveryHarness()
            h.attach(h.bindingOf(case))
            val batch = h.staged(h.stage(h.planRead(50), case))
            for (event in batch.events) {
                when (val payload = event.payload) {
                    is EventPayload.PromptShown -> {
                        assertFalse(payload.prompt.actionable)
                        assertNull(
                            "an event can never become an answerable prompt",
                            payload.prompt.asActionable()
                        )
                    }
                    is EventPayload.TaskChanged -> assertNull(payload.task.prompt)
                    else -> Unit
                }
            }
        }
        val h = DeliveryHarness()
        h.attach(h.bindingOf(needsInput))
        val task = (
            h.staged(
                h.stage(h.planRead(50), needsInput)
            ).events.first().payload as EventPayload.TaskChanged
            ).task
        // "wanted input then" — still only a display
        assertEquals(TaskState.NEEDS_INPUT, task.state)
    }

    private fun event(h: DeliveryHarness, token: BindingToken, id: String, sequence: Long) =
        RelayEvent(
            EventId.of(id)!!,
            token.conversationId,
            token.bindingId,
            sequence,
            token.replayEpoch,
            EventPayload.Changed
        )

    private fun page(token: BindingToken, snapshot: Boolean = false, vararg events: RelayEvent) =
        EventsPage(events.toList(), token.replayEpoch, events.lastOrNull()?.sequence ?: 0, snapshot)

    private fun success(h: DeliveryHarness, plan: ReadPlan, page: EventsPage): StageOutcome =
        h.apply(h.delivery.stageEvents(h.authority, plan.ref, RelayOutcome.Success(page)))

    @Test fun anEventIdAlreadyCommittedIsADuplicateEvenUnderALaterSequence() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        val a = "00000000-0000-4000-8000-000000000001"
        val b = "00000000-0000-4000-8000-000000000002"
        val first = h.staged(
            success(
                h,
                h.planRead(),
                page(token, false, event(h, token, a, 1), event(h, token, b, 2))
            )
        )
        h.apply(h.delivery.commitEvents(first.batchId, h.now()))
        val c = "00000000-0000-4000-8000-000000000003"
        val replay = h.staged(
            success(
                h,
                h.planRead(),
                page(
                    token,
                    false,
                    event(h, token, a, 3),
                    event(h, token, c, 4),
                    event(h, token, c, 5)
                )
            )
        )
        assertEquals(listOf(c), replay.events.map { it.eventId.value })
        // `a` was committed, the second `c` repeats the first inside the batch
        assertEquals(2, replay.duplicates)
        // The cursor still moves past the duplicates so they are freed
        assertEquals(5, replay.highSequence)
    }

    @Test fun staleForeignAndMisorderedPagesAreRefusedBeforeAnyStateChange() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        val ids = (1..6).map { "00000000-0000-4000-8000-%012d".format(it) }
        val good = h.staged(
            success(
                h,
                h.planRead(),
                page(token, false, event(h, token, ids[0], 1), event(h, token, ids[1], 2))
            )
        )
        h.apply(h.delivery.commitEvents(good.batchId, h.now()))
        val before = h.delivery
        // Not after the committed cursor.
        val stale = success(h, h.planRead(), page(token, false, event(h, token, ids[2], 2)))
        assertEquals(RejectReason.STALE_SEQUENCE, (stale as StageOutcome.Rejected).reason)
        // Another conversation / another binding / another replay epoch.
        val foreignConversation = RelayEvent(
            EventId.of(ids[3])!!,
            ConversationId.of("11111111-2222-4333-8444-555555555555")!!,
            token.bindingId,
            3,
            token.replayEpoch,
            EventPayload.Changed
        )
        val foreignBinding = RelayEvent(
            EventId.of(ids[4])!!,
            token.conversationId,
            BindingId.of("11111111-2222-4333-8444-555555555555")!!,
            3,
            token.replayEpoch,
            EventPayload.Changed
        )
        for (foreign in listOf(foreignConversation, foreignBinding)) {
            val outcome =
                success(h, h.planRead(), EventsPage(listOf(foreign), token.replayEpoch, 3, false))
            assertEquals(RejectReason.FOREIGN_EVENT, (outcome as StageOutcome.Rejected).reason)
        }
        val otherEpoch = RelayEvent(
            EventId.of(ids[5])!!,
            token.conversationId,
            token.bindingId,
            3,
            "another-epoch",
            EventPayload.Changed
        )
        val epoch =
            success(h, h.planRead(), EventsPage(listOf(otherEpoch), "another-epoch", 3, false))
        assertEquals(RejectReason.FOREIGN_EPOCH, (epoch as StageOutcome.Rejected).reason)
        // Nothing changed except finished calls.
        assertEquals(before.committedSequence, h.delivery.committedSequence)
        assertEquals(before.seenCount, h.delivery.seenCount)
        assertEquals(before.refreshOwed.size, h.delivery.refreshOwed.size)
    }

    @Test fun aJumpInSequenceWithoutAGapOrTombstoneDropsTheBindingAndOwesARefresh() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        // 1 and 2 never arrived
        val missing = event(h, token, "00000000-0000-4000-8000-000000000001", 3)
        val outcome = success(h, h.planRead(), page(token, false, missing))
        assertEquals(
            RefreshCause.SEQUENCE_DISCONTINUITY,
            (outcome as StageOutcome.NeedsReattach).cause
        )
        assertFalse(h.delivery.hasSession)
        assertEquals(0, h.delivery.seenCount)
        assertTrue(h.delivery.refreshOwed.any { it.cause == RefreshCause.SEQUENCE_DISCONTINUITY })
    }

    @Test fun aReplyForAReplacedBindingIsIgnoredAndTheOldSessionIsDropped() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok7))
        val plan = h.planRead(50)
        // A new attach retires the old binding while the read is still in flight.
        val newer = Binding(
            BindingId.of("00000000-0000-4000-8000-0000000000aa")!!,
            h.bindingOf(ok7).conversationId,
            1,
            0,
            0,
            "e-new",
            0,
            ExecutionState.IDLE
        )
        h.authority = h.authority.attach(h.enrollment, newer).authority
        val outcome = h.stage(plan, ok7)
        assertEquals(DeliveryIgnore.STALE_BINDING, (outcome as StageOutcome.Ignored).reason)
        assertFalse(h.delivery.hasSession)
        assertEquals(0, h.delivery.seenCount)
        // A read nobody planned (or planned on a dropped session) is simply unknown.
        val none = h.delivery.stageEvents(
            h.authority,
            plan.ref,
            DeliveryHarness.outcomeOf(plan.call, ok7)
        )
        assertEquals(DeliveryIgnore.UNKNOWN_CALL, (none.result as StageOutcome.Ignored).reason)
        assertSame(h.delivery, none.delivery)
    }

    @Test fun errorsMapToTheContractsActionsAndAnUnknownAnswerIsNeverAnEvent() {
        fun run(code: RelayErrorCode, flag: Boolean = false): Pair<StageOutcome, DeliveryHarness> {
            val h = DeliveryHarness()
            h.attach(h.bindingOf(ok7))
            val plan = h.planRead()
            val outcome = h.apply(
                h.delivery.stageEvents(
                    h.authority,
                    plan.ref,
                    RelayOutcome.Failed(RelayError(code, flag))
                )
            )
            return outcome to h
        }
        for (code in listOf(
            RelayErrorCode.STALE_EPOCH,
            RelayErrorCode.STALE_BINDING,
            RelayErrorCode.SNAPSHOT_REQUIRED
        )) {
            val (outcome, h) = run(code)
            assertEquals(
                RefreshCause.SNAPSHOT_REQUIRED,
                (outcome as StageOutcome.NeedsReattach).cause
            )
            assertFalse(h.delivery.hasSession)
            assertTrue(h.delivery.refreshOwed.any { it.cause == RefreshCause.SNAPSHOT_REQUIRED })
        }
        val (denied, deniedHarness) = run(RelayErrorCode.PERMISSION_DENIED)
        assertTrue(denied is StageOutcome.NeedsReattach)
        assertTrue(
            deniedHarness.effects.contains(
                DeliveryEffect.EvictScopeContent(deniedHarness.enrollment)
            )
        )
        val (changed, changedHarness) = run(RelayErrorCode.PROFILE_CHANGED)
        assertTrue(changed is StageOutcome.NeedsReattach)
        assertTrue(
            changedHarness.effects.contains(
                DeliveryEffect.RequireUserReview(changedHarness.enrollment)
            )
        )
        assertTrue(changedHarness.effects.none { it is DeliveryEffect.EvictScopeContent })
        for (code in listOf(
            RelayErrorCode.BUSY,
            RelayErrorCode.RATE_LIMITED,
            RelayErrorCode.TEMPORARILY_UNAVAILABLE
        )) {
            val (outcome, h) = run(code)
            assertEquals(code, (outcome as StageOutcome.RetrySameRead).code)
            assertTrue(h.delivery.hasSession) // a transient answer keeps the binding and the cursor
            assertEquals(0L, h.delivery.committedSequence)
            h.planRead() // the same read can be repeated
        }
        val (unknown, _) = run(RelayErrorCode.UNKNOWN)
        assertTrue(unknown is StageOutcome.Failed)
        // A fresh-view flag on any code also drops the binding.
        val (flagged, _) = run(RelayErrorCode.BUSY, flag = true)
        assertTrue(flagged is StageOutcome.NeedsReattach)
    }

    @Test fun aReadThatTimesOutIsRepeatedWithTheSameCursor() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok7))
        val plan = h.planRead(50)
        assertTrue(h.apply(h.delivery.onReadTimeout(plan.ref)) is StageOutcome.RetrySameRead)
        assertEquals(plan.call.params, h.planRead(50).call.params)
        // The old read is gone
        assertTrue(h.apply(h.delivery.onReadTimeout(plan.ref)) is StageOutcome.Ignored)
    }

    @Test fun twoEnrollmentsHoldIndependentDeliveryStateForEqualTextualIds() {
        val phone = DeliveryHarness()
        val watch = DeliveryHarness(EnrollmentScope(StateTestSupport.scope, "watch-key-1", 0))
        for (h in listOf(phone, watch)) {
            h.attach(h.bindingOf(ok7))
            val batch = h.staged(h.stage(h.planRead(50), ok7))
            h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        }
        assertEquals(6, phone.delivery.seenCount)
        assertEquals(6, watch.delivery.seenCount)
        // The phone forgetting does not make the watch forget.
        assertEquals(6, phone.apply(phone.delivery.retireOlderThan(Long.MAX_VALUE)))
        assertEquals(0, phone.delivery.seenCount)
        assertEquals(6, watch.delivery.seenCount)
        // A token of one enrollment is refused by the other.
        val foreign = phone.authority.current(phone.enrollment)!!
        val step = watch.delivery.attach(watch.authority, foreign)
        assertEquals(SessionRefusal.WRONG_SCOPE, step.result)
    }

    @Test fun theIdentityTableIsBoundedAndAFullTableRefusesThePageUntilRoomIsMadeExplicitly() {
        val h = DeliveryHarness()
        val token = h.attach(h.bindingOf(ok7))
        var sequence = 1L
        var id = 1L
        fun fill(count: Int, committedAt: Long): List<RelayEvent> = (0 until count).map {
            event(h, token, "00000000-0000-4000-8000-%012d".format(id++), sequence++)
        }
        h.nowMs = 1_000
        var remaining = StateLimits.MAX_SEEN_KEYS
        while (remaining > 0) {
            val take = minOf(remaining, 50)
            val batch = h.staged(
                success(h, h.planRead(), page(token, false, *fill(take, 1_000).toTypedArray()))
            )
            h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
            remaining -= take
        }
        assertEquals(StateLimits.MAX_SEEN_KEYS, h.delivery.seenCount)
        val plan = h.planRead()
        val extra = page(token, false, *fill(1, 2_000).toTypedArray())
        val full = success(h, plan, extra)
        assertEquals(DeliveryLimit.SEEN_KEYS, (full as StageOutcome.Backpressure).limit)
        assertEquals("the page was not consumed", StateLimits.MAX_SEEN_KEYS, h.delivery.seenCount)
        // Making room is explicit and only for what is old enough; then the SAME answer is applied.
        assertEquals(0, h.apply(h.delivery.retireOlderThan(1)))
        h.nowMs = 1_000
        assertEquals(
            StateLimits.MAX_SEEN_KEYS,
            h.apply(h.delivery.retireOlderThan(h.now().serverMs + 1))
        )
        assertTrue(
            h.apply(
                h.delivery.stageEvents(h.authority, plan.ref, RelayOutcome.Success(extra))
            ) is StageOutcome.Staged
        )
    }

    @Test fun nothingPrintsEventContentScopeLabelsOrCursors() {
        val h = DeliveryHarness()
        h.attach(h.bindingOf(ok7))
        val plan = h.planRead(50)
        val batch = h.staged(h.stage(plan, ok7))
        val printed = listOf(
            h.delivery.toString(),
            plan.toString(),
            plan.ref.toString(),
            batch.toString(),
            h.delivery.refreshOwed.toString(),
            DedupKey.Opaque("secret-conversation", "secret-event").toString(),
            h.enrollment.toString()
        ).joinToString("\n")
        for (secret in listOf(
            "secret",
            "endpoint-a",
            "phone-key-1",
            ok7.params["replay_epoch"]!!.jsonPrimitive.content
        )) {
            assertFalse(secret, printed.contains(secret))
        }
    }
}
