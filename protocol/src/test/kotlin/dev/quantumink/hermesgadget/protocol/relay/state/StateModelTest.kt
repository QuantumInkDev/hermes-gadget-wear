package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded, deterministic random walks over the pure state. They look for what hand-written cases miss: a crash, an
 * identity or deadline that changes, evidence that goes backwards, a resubmit planned out of order, an id minted by
 * anything but `prepare`, a snapshot that does not round-trip. Each run is reproducible from its seed.
 */
class StateModelTest {
    private val statuses = listOf(
        ReceiptStatus.QUEUED,
        ReceiptStatus.STARTED,
        ReceiptStatus.TERMINAL,
        ReceiptStatus.UNKNOWN
    )
    private val outcomes = listOf(
        "completed",
        "cancelled",
        "failed",
        "interrupted",
        "steered",
        "rejected",
        "odd_outcome",
        null
    )
    private val errors = listOf(
        "busy", "rate_limited", "temporarily_unavailable", "stale_epoch", "stale_binding",
        "snapshot_required", "not_found", "expired", "conflict", "invalid_request", "unsupported",
        "permission_denied", "profile_changed", "projection_too_large", "future_code"
    )

    private fun rank(status: ReceiptStatus) = when (status) {
        ReceiptStatus.UNKNOWN -> 0
        ReceiptStatus.QUEUED -> 1
        ReceiptStatus.STARTED -> 2
        ReceiptStatus.TERMINAL -> 3
    }

    @Test fun randomLedgerWalksNeverBreakIdentityEvidenceOrReceiptFirstOrder() {
        val coverage = HashMap<String, Int>()
        fun hit(name: String) {
            coverage[name] = (coverage[name] ?: 0) + 1
        }
        for (seed in 1L..40L) {
            val random = Random(seed)
            val h = LedgerHarness()
            val keys = mutableListOf<TurnKey>()
            val frozen = HashMap<TurnKey, List<Any?>>()
            val bestRank = HashMap<TurnKey, Int>()
            val pending = mutableListOf<TurnDispatch>()
            var token: BindingToken? = null
            var prepares = 0
            repeat(400) { step ->
                val label = "seed $seed step $step"
                when (random.nextInt(9)) {
                    0 -> if (keys.size < 6) {
                        val ttl = 30_000L + random.nextInt(60_000)
                        val draft = TurnDraft(h.conversation, "turn $prepares", ttl)
                        // A scope stopped or under review by an earlier random error refuses new turns: fine.
                        val made = h.apply(h.ledger.prepare(draft, h.now(), h.entropy))
                        if (made is PrepareOutcome.Prepared) {
                            prepares++
                            keys.add(made.key)
                            val e = h.entry(made.key)
                            frozen[made.key] =
                                listOf(e.text, e.expiresAtMs, e.fingerprint, e.preparedAtMs)
                        }
                    }
                    1 -> token = h.attach().also { pending.clear() }
                    2, 3 -> if (keys.isNotEmpty() && token != null) {
                        val key = keys[random.nextInt(keys.size)]
                        val before = h.entry(key)
                        val outcome = if (random.nextBoolean()) {
                            h.submit(key, token)
                        } else {
                            h.lookup(key, token)
                        }
                        if (outcome is PlanOutcome.Planned) {
                            hit("planned ${outcome.dispatch.ref.kind}")
                            pending.add(outcome.dispatch)
                            if (outcome.dispatch.ref.kind == CallKind.SUBMIT) {
                                // A submit is only ever planned for a turn no receipt has admitted or closed, and
                                // never straight out of uncertainty.
                                assertTrue(label, before.facts == null && before.closure == null)
                                assertTrue(label, before.phase != TurnPhase.UNCERTAIN)
                            }
                        }
                    }
                    4 -> if (pending.isNotEmpty()) {
                        val dispatch = pending.removeAt(random.nextInt(pending.size))
                        val key = dispatch.key
                        val status = statuses[random.nextInt(statuses.size)]
                        val generation = if (status ==
                            ReceiptStatus.STARTED
                        ) {
                            1L + random.nextInt(3)
                        } else {
                            if (status == ReceiptStatus.TERMINAL &&
                                random.nextBoolean()
                            ) {
                                1L
                            } else {
                                null
                            }
                        }
                        val outcome = if (status ==
                            ReceiptStatus.TERMINAL
                        ) {
                            outcomes[random.nextInt(outcomes.size)]
                        } else {
                            null
                        }
                        val admission = if (random.nextInt(8) ==
                            0
                        ) {
                            LedgerHarness.OTHER_ADMISSION
                        } else {
                            LedgerHarness.ADMISSION
                        }
                        val replied = h.apply(
                            h.ledger.onReply(
                                dispatch.ref,
                                RelayOutcome.Success(
                                    h.receipt(key, status, generation, outcome, admission)
                                )
                            )
                        )
                        hit("receipt ${replied.javaClass.simpleName} $status")
                    }
                    5 -> if (pending.isNotEmpty()) {
                        val dispatch = pending.removeAt(random.nextInt(pending.size))
                        val code = errors[random.nextInt(errors.size)]
                        val replied = h.apply(
                            h.ledger.onReply(
                                dispatch.ref,
                                RelayOutcome.Failed(
                                    RelayError(
                                        RelayErrorCode.fromWire(code),
                                        random.nextInt(6) == 0
                                    )
                                )
                            )
                        )
                        hit("error ${replied.javaClass.simpleName}")
                    }
                    6 -> if (pending.isNotEmpty()) {
                        h.apply(
                            h.ledger.onTimeout(pending.removeAt(random.nextInt(pending.size)).ref)
                        )
                    }
                    7 -> h.advance(random.nextInt(40_000).toLong())
                    else -> if (random.nextInt(4) == 0) {
                        h.apply(h.ledger.bindingLost(h.enrollment))
                        pending.clear()
                    }
                }
                for (key in keys) {
                    val entry = h.entry(key)
                    // Identity, text and deadline never change once prepared.
                    assertEquals(
                        label,
                        frozen[key],
                        listOf(entry.text, entry.expiresAtMs, entry.fingerprint, entry.preparedAtMs)
                    )
                    // Evidence only goes forward.
                    val facts = entry.facts
                    if (facts != null) {
                        val r = rank(facts.status)
                        assertTrue("$label evidence went backwards", r >= (bestRank[key] ?: 0))
                        bestRank[key] = maxOf(bestRank[key] ?: 0, r)
                    }
                    // At most one outstanding call and a closed turn has none.
                    if (entry.closure != null) assertEquals(label, null, entry.outstanding)
                    assertNotNull(label, entry.status)
                }
                assertEquals("$label: only prepare mints", prepares, h.entropy.calls)
                // The durable record always round-trips through a snapshot to itself.
                if (step % 50 == 0) {
                    val saved = h.ledger.snapshot()
                    val restored = (
                        RelayStateRestore.restore(
                            RelayStateSnapshot(1, saved, emptyList(), emptyList()),
                            h.now(),
                            RelayAuthority.fresh()
                        ) as RestoreResult.Restored
                        ).value.ledger
                    assertEquals(label, saved.turns, restored.snapshot().turns)
                    assertEquals(label, saved.scopes.toSet(), restored.snapshot().scopes.toSet())
                    hit("round trip ${saved.turns.size.coerceAtMost(3)}")
                }
            }
        }
        // The walks must actually have exercised the machine, not just run.
        for (name in listOf("planned SUBMIT", "planned LOOKUP", "error Applied")) {
            assertTrue("$name: ${coverage[name]} $coverage", (coverage[name] ?: 0) >= 100)
        }
        assertTrue(coverage.toString(), coverage.keys.any { it.startsWith("receipt Anomalous") })
        assertTrue(
            coverage.toString(),
            coverage.keys.any {
                it.startsWith("receipt Applied TERMINAL")
            }
        )
        assertTrue(coverage.toString(), coverage.keys.any { it.startsWith("round trip 3") })
    }

    @Test fun randomHistoryWalksNeverEmitIncompleteOutOfOrderOrDuplicateMessages() {
        val f = HistoryHarness.Companion
        for (seed in 1L..40L) {
            val random = Random(seed)
            val h = HistoryHarness()
            var nextMessage = 1
            var cursor = 1
            var lastOrdinal = 0L
            val seen = HashSet<Long>()
            repeat(200) { step ->
                val label = "seed $seed step $step"
                when (random.nextInt(6)) {
                    0, 1, 2 -> if (h.assembly.status == ListingStatus.ACTIVE &&
                        !h.assembly.hasOutstandingRequest
                    ) {
                        val plan = h.plan()
                        val items =
                            ArrayList<dev.quantumink.hermesgadget.protocol.relay.HistoryMessage>()
                        repeat(random.nextInt(4)) {
                            val n = nextMessage++
                            val parts = 1 + random.nextInt(3)
                            for (i in 0 until parts) items.add(f.fragment(n, i, parts, "m$n.$i;"))
                            if (random.nextInt(5) == 0 &&
                                items.isNotEmpty()
                            ) {
                                items.add(items.last()) // an identical duplicate
                            }
                        }
                        val next = if (random.nextInt(8) == 0) null else f.cursor(cursor++)
                        val outcome = h.accept(plan, f.page(next, *items.toTypedArray()))
                        if (outcome is PageOutcome.Accepted) {
                            for (m in outcome.emitted) {
                                assertTrue(
                                    "$label ordinals strictly increase",
                                    m.ordinal > lastOrdinal
                                )
                                lastOrdinal = m.ordinal
                                assertTrue("$label no ordinal twice", seen.add(m.ordinal))
                                // A message is only whole: its text carries every one of its segments.
                                assertEquals(
                                    label,
                                    m.segmentCount,
                                    m.text.split(';').count {
                                        it.isNotEmpty()
                                    }
                                )
                            }
                        }
                    }
                    3 -> if (h.assembly.stagedMessages.isNotEmpty()) {
                        val last = h.assembly.stagedMessages.last().ordinal
                        h.apply(
                            h.assembly.commit(
                                h.assembly.stagedMessages[
                                    random.nextInt(
                                        h.assembly.stagedMessages.size
                                    )
                                ].ordinal.coerceAtMost(last)
                            )
                        )
                    }
                    4 -> if (random.nextInt(6) == 0) {
                        val dropped = h.apply(h.assembly.abortUncommitted())
                        assertTrue(label, dropped >= 0)
                        lastOrdinal = h.assembly.committedThrough
                        seen.removeAll { it > lastOrdinal }
                    }
                    else -> if (h.assembly.status != ListingStatus.ACTIVE &&
                        random.nextInt(3) == 0
                    ) {
                        h.assembly = h.assembly.restart(HistoryInvalidation.CONSUMER)
                        lastOrdinal = 0
                        seen.clear()
                    }
                }
                assertTrue(
                    label,
                    h.assembly.committedThrough <= lastOrdinal ||
                        h.assembly.stagedMessages.isEmpty()
                )
                assertFalse(label, h.assembly.toString().contains("m1.0"))
                // The saved record round-trips through restore without inventing anything.
                if (step % 25 == 0) {
                    val saved = h.assembly.snapshot()
                    val again = (
                        HistoryAssembly.restore(
                            saved
                        ) as RestoreResult.Restored
                        ).value.snapshot()
                    assertEquals(label, saved.known, again.known)
                    assertEquals(label, saved.committedOrdinal, again.committedOrdinal)
                }
            }
        }
    }

    @Test fun randomDeliveryWalksKeepTheCursorMonotoneAndNeverActOnStagedContent() {
        val read = DeliveryHarness.events("ok-07")
        for (seed in 1L..30L) {
            val random = Random(seed)
            val h = DeliveryHarness()
            val token = h.attach(h.bindingOf(read))
            var sequence = 0L
            var event = 1L
            var committed = 0L
            val keys = HashSet<String>()
            repeat(150) { step ->
                val label = "seed $seed step $step"
                val session = h.delivery.hasSession
                when (random.nextInt(4)) {
                    0, 1 -> if (session && h.delivery.committedSequence != null) {
                        val plan = (
                            h.apply(
                                h.delivery.planRead(h.authority, h.correlation(), 50)
                            ) as? ReadPlanOutcome.Planned
                            )?.plan
                        if (plan != null) {
                            val count = random.nextInt(4)
                            val events = (0 until count).map {
                                val id = if (keys.isNotEmpty() &&
                                    random.nextInt(4) == 0
                                ) {
                                    keys.elementAt(random.nextInt(keys.size))
                                } else {
                                    "00000000-0000-4000-8000-%012d".format(event++)
                                }
                                sequence = maxOf(sequence, committed) + 1 + it
                                dev.quantumink.hermesgadget.protocol.relay.RelayEvent(
                                    dev.quantumink.hermesgadget.protocol.relay.EventId.of(id)!!,
                                    token.conversationId,
                                    token.bindingId,
                                    committed + 1 + it,
                                    token.replayEpoch,
                                    dev.quantumink.hermesgadget.protocol.relay.EventPayload.Changed
                                )
                            }
                            val page = dev.quantumink.hermesgadget.protocol.relay.EventsPage(
                                events,
                                token.replayEpoch,
                                events.lastOrNull()?.sequence ?: committed,
                                false
                            )
                            val outcome = h.apply(
                                h.delivery.stageEvents(
                                    h.authority,
                                    plan.ref,
                                    RelayOutcome.Success(page)
                                )
                            )
                            if (outcome is StageOutcome.Staged) {
                                // Staging moved nothing.
                                assertEquals(label, committed, h.delivery.committedSequence)
                                if (random.nextInt(5) == 0) {
                                    h.apply(h.delivery.abortEvents(outcome.batch.batchId))
                                } else {
                                    h.apply(h.delivery.commitEvents(outcome.batch.batchId, h.now()))
                                    assertTrue(label, outcome.batch.highSequence >= committed)
                                    committed = outcome.batch.highSequence
                                    outcome.batch.events.forEach { keys.add(it.eventId.value) }
                                }
                            }
                        }
                    }
                    2 -> h.nowMs += random.nextInt(1000).toLong()
                    else -> if (random.nextInt(10) ==
                        0
                    ) {
                        h.apply(h.delivery.retireOlderThan(h.now().serverMs - 500))
                    }
                }
                val cursor = h.delivery.committedSequence
                if (cursor != null) assertEquals(label, committed, cursor)
                assertTrue(label, h.delivery.seenCount <= StateLimits.MAX_SEEN_KEYS)
                if (step % 30 == 0) {
                    val saved = h.delivery.snapshot()
                    val again = (
                        EnrollmentDelivery.restore(
                            saved
                        ) as RestoreResult.Restored
                        ).value.snapshot()
                    assertEquals(label, saved.seen, again.seen)
                }
            }
        }
    }
}
