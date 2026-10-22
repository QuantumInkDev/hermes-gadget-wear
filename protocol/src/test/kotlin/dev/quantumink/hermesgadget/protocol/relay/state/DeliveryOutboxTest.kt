package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayEvent
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.TaskId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O5 copies against the RECORDED `relay.outbox.read` / `relay.outbox.ack` frames: display only, ack after storing. */
class DeliveryOutboxTest {
    private fun ids(batch: StagedOutbox) = batch.items.map { it.deliveryId.value.take(8) }

    @Test fun aRecordedFinalTextCopyIsStagedAndAcknowledgedOnlyAfterTheConsumerStoredIt() {
        val read = DeliveryHarness.outbox("ok-08")
        val ack = DeliveryHarness.ack("ok-03")
        val delivery = read.result["items"]!!.jsonArray.single().let {
            (it as kotlinx.serialization.json.JsonObject)["delivery_id"]!!.jsonPrimitive.content
        }
        // The two recorded exchanges are one story: the acknowledged id IS the delivery just read.
        assertEquals(
            listOf(delivery),
            ack.params["delivery_ids"]!!.jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        val h = DeliveryHarness()
        val plan = h.planOutbox()
        assertEquals(read.params, plan.call.params)
        val batch = h.stagedOutbox(h.stageOutbox(plan, read))
        assertTrue(batch.items.single() is OutboxItem.Final)
        // Nothing is owed or acknowledged while merely staged.
        assertTrue(h.delivery.acknowledgementsOwed.isEmpty())
        assertEquals(
            OutboxRefusal.NOTHING_TO_ACK,
            (h.apply(h.delivery.planAck(h.correlation())) as OutboxPlanOutcome.Refused).reason
        )
        assertEquals(
            OutboxRefusal.BATCH_OUTSTANDING,
            (
                h.apply(
                    h.delivery.planOutboxRead(h.correlation())
                ) as OutboxPlanOutcome.Refused
                ).reason
        )
        h.apply(h.delivery.commitOutbox(batch.batchId, h.now()))
        assertEquals(listOf(delivery), h.delivery.acknowledgementsOwed.map { it.value })
        assertTrue(h.effects.contains(DeliveryEffect.AcknowledgementsOwed(1)))
        // Our acknowledgement request equals the recorded one; the recorded answer clears the debt.
        val ackPlan = h.planAck()
        assertEquals(ack.params, ackPlan.call.params)
        val confirmed = h.apply(
            h.delivery.onAckReply(ackPlan.ref, DeliveryHarness.outcomeOf(ackPlan.call, ack))
        )
        assertEquals(1, (confirmed as AckOutcome.Confirmed).removed)
        assertTrue(h.delivery.acknowledgementsOwed.isEmpty())
    }

    @Test fun finalTextRefsStayOpaqueAndNeverMeetACanonicalIdentityOfTheSameSpelling() {
        // Conversation "stock-chat", event "stock-final": not UUIDs
        val stock = DeliveryHarness.outbox("ok-09")
        val h = DeliveryHarness()
        val batch = h.stagedOutbox(h.stageOutbox(h.planOutbox(), stock))
        val item = batch.items.single() as OutboxItem.Final
        assertEquals("stock-chat", item.conversationRef.value)
        assertEquals("stock-final", item.eventRef.value)
        h.apply(h.delivery.commitOutbox(batch.batchId, h.now()))
        assertTrue(h.delivery.hasSeen(DedupKey.Opaque("stock-chat", "stock-final")))
        // The same spelling as a canonical key (a UUID-shaped opaque text) is a different identity.
        val uuid = "00000000-0000-4000-8000-000000000001"
        val canonical = DedupKey.Canonical(ConversationId.of(uuid)!!, EventId.of(uuid)!!)
        val opaque = DedupKey.Opaque(uuid, uuid)
        assertFalse(h.delivery.hasSeen(canonical))
        assertFalse(opaque == canonical)
        val twin = OutboxItem.Final(
            DeliveryId.of("00000000-0000-4000-8000-0000000000d1")!!,
            OpaqueConversationRef.of(uuid)!!,
            OpaqueEventRef.of(uuid)!!,
            0,
            10,
            OutboxPayload.FinalText("copy", null)
        )
        val page = OutboxPage(listOf(twin), 0)
        val plan = h.planOutbox()
        val staged = h.stagedOutbox(
            h.apply(h.delivery.stageOutbox(plan.ref, RelayOutcome.Success(page)))
        )
        h.apply(h.delivery.commitOutbox(staged.batchId, h.now()))
        assertTrue(h.delivery.hasSeen(opaque))
        assertFalse(
            "an opaque ref is never coerced into canonical authority",
            h.delivery.hasSeen(canonical)
        )
    }

    @Test fun aCopyOfAnEventTheLiveStreamAlreadyDeliveredIsTheSameCanonicalIdentity() {
        val h = DeliveryHarness()
        val read = DeliveryHarness.outbox("ok-06")
        val change = (read.result["items"]!!.jsonArray[1] as kotlinx.serialization.json.JsonObject)
        val conversation = ConversationId.of(change["conversation_id"]!!.jsonPrimitive.content)!!
        val eventId = EventId.of(change["event_id"]!!.jsonPrimitive.content)!!
        // The live task event and the O5 copy share one event_id by contract.
        val binding = dev.quantumink.hermesgadget.protocol.relay.Binding(
            dev.quantumink.hermesgadget.protocol.relay.BindingId.of(
                "00000000-0000-4000-8000-0000000000b1"
            )!!,
            conversation,
            1,
            0,
            0,
            "epoch-1",
            0,
            dev.quantumink.hermesgadget.protocol.relay.ExecutionState.IDLE
        )
        val token = h.attach(binding)
        val live =
            RelayEvent(
                eventId,
                conversation,
                token.bindingId,
                1,
                token.replayEpoch,
                EventPayload.Changed
            )
        val plan = h.planRead()
        val liveBatch = h.staged(
            h.apply(
                h.delivery.stageEvents(
                    h.authority,
                    plan.ref,
                    RelayOutcome.Success(
                        dev.quantumink.hermesgadget.protocol.relay.EventsPage(
                            listOf(live),
                            token.replayEpoch,
                            1,
                            false
                        )
                    )
                )
            )
        )
        h.apply(h.delivery.commitEvents(liveBatch.batchId, h.now()))
        val outbox = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), read))
        // The task-change copy: already stored via the live event
        assertEquals(1, outbox.duplicates.size)
        assertEquals(1, outbox.items.size) // the final text is new
        assertTrue(outbox.items.single() is OutboxItem.Final)
    }

    @Test fun theFirstObservationAndAnyLargerGapGenerationOweARefreshAndNeverAnExecution() {
        val h = DeliveryHarness()
        // No baseline yet: even gap 0 cannot be called "nothing missed".
        val first = h.stagedOutbox(h.stageOutbox(h.planOutbox(), DeliveryHarness.outbox("ok-04")))
        assertEquals(
            setOf(RefreshKind.HISTORY, RefreshKind.TASKS),
            first.refresh.map {
                it.kind
            }.toSet()
        )
        assertTrue(
            first.refresh.all {
                it.cause == RefreshCause.OUTBOX_BASELINE_UNKNOWN &&
                    it.conversation == null
            }
        )
        assertNull(h.delivery.committedGapGeneration)
        h.apply(h.delivery.commitOutbox(first.batchId, h.now()))
        assertEquals(0L, h.delivery.committedGapGeneration)
        h.apply(h.delivery.refreshed(null, setOf(RefreshKind.HISTORY, RefreshKind.TASKS)))
        // Same generation: nothing owed.
        val same = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-11")))
        assertTrue(same.refresh.none { it.cause == RefreshCause.OUTBOX_GAP })
        h.apply(h.delivery.abortOutbox(same.batchId))
        // A larger generation (the first observation of an existing source, recorded at 1): refresh.
        val larger = h.stagedOutbox(
            h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-10"))
        )
        assertEquals(1, larger.gapGeneration)
        assertTrue(larger.refresh.any { it.cause == RefreshCause.OUTBOX_GAP })
        h.apply(h.delivery.commitOutbox(larger.batchId, h.now()))
        assertEquals(1L, h.delivery.committedGapGeneration)
        assertTrue(
            h.effects.all {
                it is DeliveryEffect.PersistDelivery ||
                    it is DeliveryEffect.RefreshRequired ||
                    it is DeliveryEffect.AcknowledgementsOwed
            }
        )
    }

    @Test fun aSmallerGapGenerationIsAnAnomalyThatNeverLowersTheBaseline() {
        val h = DeliveryHarness()
        // Gap 2
        val high = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-12")))
        h.apply(h.delivery.commitOutbox(high.batchId, h.now()))
        assertEquals(2L, h.delivery.committedGapGeneration)
        // Gap 1
        val low = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-13")))
        assertTrue(low.refresh.any { it.cause == RefreshCause.OUTBOX_GAP_REGRESSED })
        h.apply(h.delivery.commitOutbox(low.batchId, h.now()))
        assertEquals(2L, h.delivery.committedGapGeneration)
    }

    @Test fun supersededAndCurrentCopiesAreDisplayOnlyAndANeedsInputCopyAsksForAFetchFirst() {
        // Two superseded snapshots then the current needs_input of ONE task (recorded).
        val h = DeliveryHarness()
        val current = h.stagedOutbox(
            h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-16"))
        )
        assertEquals(3, current.items.size)
        val last = current.items.last() as OutboxItem.Change
        val task: TaskId = (last.payload as OutboxPayload.TaskChange.Relay).task.taskId
        // "wanted input then": fetch before any prompt
        assertEquals(listOf(task), current.fetchBeforePrompt)
        assertEquals(setOf(task), current.newestPerTask.keys)
        assertEquals(current.items.last(), current.newestPerTask.getValue(task))
        assertTrue(current.refresh.none { it.cause == RefreshCause.SUPERSEDED_COPY })

        // The same ids later: the needs_input copy is now superseded by a working one (recorded).
        val h2 = DeliveryHarness()
        val later = h2.stagedOutbox(
            h2.stageOutbox(h2.planOutbox(50), DeliveryHarness.outbox("ok-17"))
        )
        assertTrue(
            "a superseded needs_input never asks for a prompt",
            later.fetchBeforePrompt.isEmpty()
        )
        assertEquals(setOf(task), later.newestPerTask.keys)
        assertTrue(later.refresh.none { it.cause == RefreshCause.SUPERSEDED_COPY })

        // Only an older snapshot (nothing newer in the page): show the newer one — refresh to get it.
        val h3 = DeliveryHarness()
        val onlyOld = h3.stagedOutbox(
            h3.stageOutbox(h3.planOutbox(50), DeliveryHarness.outbox("ok-14"))
        )
        assertTrue(
            onlyOld.refresh.any {
                it.cause == RefreshCause.SUPERSEDED_COPY &&
                    it.kind == RefreshKind.TASKS
            }
        )
    }

    @Test fun aReplayedCopyIsADuplicateStillAcknowledgedButNeverDisplayedTwice() {
        val h = DeliveryHarness()
        val first = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-07")))
        assertEquals(4, first.items.size)
        h.apply(h.delivery.commitOutbox(first.batchId, h.now()))
        // The recorded replay (ok-15) carries the first three of those very deliveries again.
        val replay = h.stagedOutbox(
            h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-15"))
        )
        assertTrue(replay.items.isEmpty())
        assertEquals(3, replay.duplicates.size)
        h.apply(h.delivery.commitOutbox(replay.batchId, h.now()))
        // No delivery id is owed twice.
        assertEquals(4, h.delivery.acknowledgementsOwed.size)
        assertEquals(4, h.delivery.acknowledgementsOwed.toSet().size)
    }

    private fun items(from: Int, count: Int): List<OutboxItem> = (from until from + count).map {
        OutboxItem.Final(
            DeliveryId.of("00000000-0000-4000-8000-%012d".format(it))!!,
            OpaqueConversationRef.of("conversation")!!,
            OpaqueEventRef.of("event-$it")!!,
            0,
            10,
            OutboxPayload.FinalText("copy $it", null)
        )
    }

    private fun stage(h: DeliveryHarness, list: List<OutboxItem>): OutboxStageOutcome {
        val plan = h.planOutbox(50)
        return h.apply(h.delivery.stageOutbox(plan.ref, RelayOutcome.Success(OutboxPage(list, 0))))
    }

    @Test fun anAcknowledgementCarriesAtMostFiftyStoredDeliveriesAndALostReplyIsRepeatedSafely() {
        val h = DeliveryHarness()
        val batch = h.stagedOutbox(stage(h, items(1, 50)))
        h.apply(h.delivery.commitOutbox(batch.batchId, h.now()))
        val more = h.stagedOutbox(stage(h, items(51, 20)))
        h.apply(h.delivery.commitOutbox(more.batchId, h.now()))
        assertEquals(70, h.delivery.acknowledgementsOwed.size)
        val first = h.planAck()
        assertEquals(50, first.call.params["delivery_ids"]!!.jsonArray.size)
        // Only one acknowledgement at a time.
        assertEquals(
            OutboxRefusal.ACK_OUTSTANDING,
            (h.apply(h.delivery.planAck(h.correlation())) as OutboxPlanOutcome.Refused).reason
        )
        // The reply is lost: nothing is removed and the very same ids are asked again.
        assertTrue(h.apply(h.delivery.onAckTimeout(first.ref)) is AckOutcome.NotAcknowledged)
        assertEquals(70, h.delivery.acknowledgementsOwed.size)
        val again = h.planAck()
        assertEquals(first.call.params, again.call.params)
        // A late answer to the first, already abandoned, request changes nothing.
        assertTrue(
            h.apply(
                h.delivery.onAckReply(
                    first.ref,
                    RelayOutcome.Failed(RelayError(RelayErrorCode.BUSY, false))
                )
            ) is AckOutcome.Ignored
        )
        // (ref carries ids internally; the call params are what is sent)
        val ackedIds = first.ref.toString()
        assertTrue(ackedIds.contains("ids=50"))
        // The server names 50 deliveries, but 20 of them were never part of this request: they are NOT removed.
        val named = h.delivery.acknowledgementsOwed.drop(20).take(50)
        val reply = RelayOutcome.Success(
            dev.quantumink.hermesgadget.protocol.relay.Acknowledged(named)
        )
        val confirmed = h.apply(h.delivery.onAckReply(again.ref, reply)) as AckOutcome.Confirmed
        assertEquals(30, confirmed.removed)
        assertEquals(40, confirmed.stillPending)
    }

    @Test fun anAcknowledgementFailureLeavesTheDebtAndOnlyAnAcknowledgedIdIsRemoved() {
        val h = DeliveryHarness()
        val batch = h.stagedOutbox(stage(h, items(1, 3)))
        h.apply(h.delivery.commitOutbox(batch.batchId, h.now()))
        val plan = h.planAck()
        val failed = h.apply(
            h.delivery.onAckReply(
                plan.ref,
                RelayOutcome.Failed(RelayError(RelayErrorCode.NOT_FOUND, false))
            )
        )
        assertEquals(RelayErrorCode.NOT_FOUND, (failed as AckOutcome.NotAcknowledged).code)
        assertEquals(3, h.delivery.acknowledgementsOwed.size)
        val second = h.planAck()
        // The server acknowledges only two of the three (and names one we never asked about): exactly those two go.
        val partial = dev.quantumink.hermesgadget.protocol.relay.Acknowledged(
            listOf(
                h.delivery.acknowledgementsOwed[0],
                h.delivery.acknowledgementsOwed[2],
                DeliveryId.of("00000000-0000-4000-8000-0000000000ff")!!
            )
        )
        val confirmed = h.apply(
            h.delivery.onAckReply(second.ref, RelayOutcome.Success(partial))
        ) as AckOutcome.Confirmed
        assertEquals(2, confirmed.removed)
        assertEquals(1, confirmed.stillPending)
    }

    @Test fun theUnacknowledgedBacklogIsBoundedAndBackpressureConsumesNothing() {
        val h = DeliveryHarness()
        var next = 1
        while (h.delivery.acknowledgementsOwed.size + 50 <= StateLimits.MAX_PENDING_ACKS) {
            val batch = h.stagedOutbox(stage(h, items(next, 50)))
            h.apply(h.delivery.commitOutbox(batch.batchId, h.now()))
            next += 50
        }
        assertEquals(250, h.delivery.acknowledgementsOwed.size)
        val plan = h.planOutbox(50)
        val page = OutboxPage(items(next, 7), 0) // 250 + 7 > 256
        val outcome = h.apply(h.delivery.stageOutbox(plan.ref, RelayOutcome.Success(page)))
        assertEquals(DeliveryLimit.PENDING_ACKS, (outcome as OutboxStageOutcome.Backpressure).limit)
        assertEquals(250, h.delivery.acknowledgementsOwed.size)
        // The read stays outstanding; once acknowledgements drain, the very same page is accepted.
        val ack = h.planAck()
        val reply = RelayOutcome.Success(
            dev.quantumink.hermesgadget.protocol.relay.Acknowledged(
                h.delivery.acknowledgementsOwed.take(50)
            )
        )
        h.apply(h.delivery.onAckReply(ack.ref, reply))
        assertTrue(
            h.apply(
                h.delivery.stageOutbox(plan.ref, RelayOutcome.Success(page))
            ) is OutboxStageOutcome.Staged
        )
    }

    @Test fun recordedOutboxErrorsAreFailuresOrRetriesNeverCopies() {
        val denied = DeliveryHarness.outbox("error-permission-denied-02")
        val unavailable = DeliveryHarness.outbox("error-temporarily-unavailable-03")
        val invalid = DeliveryHarness.outbox("error-invalid-request-01")
        val h = DeliveryHarness()
        val a = h.stageOutbox(h.planOutbox(), denied)
        assertEquals(RelayErrorCode.PERMISSION_DENIED, (a as OutboxStageOutcome.Failed).code)
        assertTrue(h.effects.contains(DeliveryEffect.EvictScopeContent(h.enrollment)))
        val b = h.stageOutbox(h.planOutbox(), unavailable)
        assertEquals(
            RelayErrorCode.TEMPORARILY_UNAVAILABLE,
            (b as OutboxStageOutcome.RetrySameRead).code
        )
        val c = h.stageOutbox(h.planOutbox(), invalid)
        assertEquals(RelayErrorCode.INVALID_REQUEST, (c as OutboxStageOutcome.Failed).code)
        assertEquals(0, h.delivery.seenCount)
        assertTrue(h.delivery.acknowledgementsOwed.isEmpty())
    }

    @Test fun anAbortedOutboxBatchLeavesNothingStoredOrOwed() {
        val h = DeliveryHarness()
        val batch = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-07")))
        assertTrue(h.apply(h.delivery.abortOutbox(batch.batchId)))
        assertEquals(0, h.delivery.seenCount)
        assertTrue(h.delivery.acknowledgementsOwed.isEmpty())
        assertNull(h.delivery.committedGapGeneration)
        // The same items come back and are NEW again.
        val again = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-07")))
        assertEquals(4, again.items.size)
        assertEquals(0, again.duplicates.size)
        assertFalse(h.apply(h.delivery.abortOutbox(batch.batchId + 99)))
    }

    @Test fun anOutboxReadThatTimesOutIsRepeatedAndALateAnswerIsIgnored() {
        val h = DeliveryHarness()
        val plan = h.planOutbox(50)
        assertTrue(
            h.apply(h.delivery.onOutboxTimeout(plan.ref)) is OutboxStageOutcome.RetrySameRead
        )
        val late = h.apply(
            h.delivery.stageOutbox(
                plan.ref,
                DeliveryHarness.outcomeOf(plan.call, DeliveryHarness.outbox("ok-07"))
            )
        )
        assertTrue(late is OutboxStageOutcome.Ignored)
        assertEquals(0, h.delivery.seenCount)
        assertEquals(plan.call.params, h.planOutbox(50).call.params)
    }

    @Test fun copiesStayDisplayOnlyTheyCarryNoPromptRequestOrBindingMember() {
        val h = DeliveryHarness()
        val batch = h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-16")))
        for (item in batch.items) {
            val names = item.payload.javaClass.methods.map { it.name.lowercase() }
            assertTrue(names.none { it.contains("prompt") && it != "toString" })
            assertTrue(
                names.none {
                    it.contains("binding") || it.contains("respond") ||
                        it.contains("submit")
                }
            )
        }
        val printed = listOf(
            batch.toString(),
            h.delivery.toString(),
            batch.items.joinToString {
                it.toString()
            }
        ).joinToString()
        assertFalse(printed.contains("synthetic"))
    }
}
