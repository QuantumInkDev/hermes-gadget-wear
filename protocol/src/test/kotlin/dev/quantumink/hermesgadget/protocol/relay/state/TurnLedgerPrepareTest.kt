package dev.quantumink.hermesgadget.protocol.relay.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preparing a turn: the only place a request id is minted, and its immutable identity. */
class TurnLedgerPrepareTest {
    private fun prepareOutcome(
        h: LedgerHarness,
        draft: TurnDraft,
        entropy: RequestIdEntropy = h.entropy
    ) = h.ledger.prepare(draft, h.now(), entropy)

    @Test fun aDeliberateNewTurnMintsOneIdFromTheServerEstimateAndFixesItsDeadlineAndText() {
        val h = LedgerHarness()
        val key = h.prepare("Hello Hermes", ttlMs = 60_000)
        val entry = h.entry(key)
        assertEquals(1, h.entropy.calls)
        assertEquals(h.serverMs, RequestIds.timestampMs(key.requestId))
        assertEquals(h.serverMs, entry.preparedAtMs)
        assertEquals(entry.preparedAtMs + 60_000, entry.expiresAtMs)
        assertEquals("Hello Hermes", entry.text)
        assertEquals(TurnPhase.PREPARED, entry.phase)
        assertEquals(TurnStatus.NOT_SENT, entry.status)
        assertFalse(entry.sent)
        assertFalse(entry.mayHaveReachedServer)
        assertEquals(listOf<TurnEffect>(TurnEffect.PersistLedger), h.effects)
        assertEquals(IdAge.FRESH, RequestIds.checkNew(key.requestId, h.serverMs))
        assertEquals(
            ExpiryCheck.VALID,
            RequestIds.checkNewTurnExpiry(h.serverMs, entry.expiresAtMs)
        )
    }

    @Test fun theSameTextPreparedTwiceIsTwoDifferentExplicitTurnsWithDifferentIds() {
        val h = LedgerHarness()
        val first = h.prepare("again")
        val second = h.prepare("again")
        assertTrue(first.requestId != second.requestId)
        assertEquals(2, h.ledger.size)
        assertTrue(h.entry(first).fingerprint != h.entry(second).fingerprint)
        assertEquals(2, h.entropy.calls)
    }

    @Test fun deadlineLifetimesAreCheckedAtTheirExactBounds() {
        val h = LedgerHarness()
        assertEquals(1, h.entry(h.prepare(ttlMs = 1)).expiresAtMs - h.serverMs)
        assertEquals(300_000, h.entry(h.prepare(ttlMs = 300_000)).expiresAtMs - h.serverMs)
        val before = h.ledger
        for (bad in listOf(0L, -1L, 300_001L, Long.MAX_VALUE)) {
            val step = prepareOutcome(h, TurnDraft(h.conversation, "x", bad))
            assertEquals(
                PrepareRefusal.INVALID_DEADLINE,
                (step.result as PrepareOutcome.Refused).reason
            )
            assertSame(before, step.ledger)
        }
    }

    @Test fun textRulesFollowTheSubmitCallAndARefusalChangesNothing() {
        val h = LedgerHarness()
        val before = h.ledger
        val cases = mapOf(
            "empty" to "",
            "4001 code points" to "a".repeat(4_001),
            "lone surrogate" to "\uD800 broken"
        )
        for ((name, text) in cases) {
            val step = prepareOutcome(h, TurnDraft(h.conversation, text))
            assertEquals(
                name,
                PrepareRefusal.INVALID_TEXT,
                (step.result as PrepareOutcome.Refused).reason
            )
            assertSame(name, before, step.ledger)
            assertTrue(step.effects.isEmpty())
        }
        h.prepare("a".repeat(4_000)) // exactly the code point limit
        h.prepare("😀".repeat(4_000)) // 16,000 UTF-8 bytes of four-byte code points
        assertEquals(2, h.ledger.size)
    }

    @Test fun anIdCollisionIsRefusedNeverOverwritten() {
        val h = LedgerHarness()
        val fixed = StateTestSupport.entropy(7)
        val first = h.ledger.prepare(TurnDraft(h.conversation, "one"), h.now(), fixed)
        h.apply(first)
        val key = (first.result as PrepareOutcome.Prepared).key
        val second = h.ledger.prepare(TurnDraft(h.conversation, "two"), h.now(), fixed)
        assertEquals(PrepareRefusal.ID_COLLISION, (second.result as PrepareOutcome.Refused).reason)
        assertSame(h.ledger, second.ledger)
        assertEquals("one", h.entry(key).text)
    }

    @Test fun brokenEntropyAndAClockRollbackAreRefusedWithoutStateChange() {
        val h = LedgerHarness()
        val zero = RequestIdEntropy { }
        val degenerate = h.ledger.prepare(TurnDraft(h.conversation, "x"), h.now(), zero)
        val refused = degenerate.result as PrepareOutcome.Refused
        assertEquals(PrepareRefusal.MINT_FAILED, refused.reason)
        assertEquals(MintRefusal.ENTROPY_DEGENERATE, refused.mint)
        assertTrue(h.ledger.size == 0 && degenerate.ledger === h.ledger)
        h.prepare("ok")
        h.advance(5_000)
        h.prepare("later")
        val older = h.instantAtNanos(h.nanos - 1_000_000)
        val rolled = h.ledger.prepare(TurnDraft(h.conversation, "x"), older, h.entropy)
        assertEquals(
            PrepareRefusal.CLOCK_ROLLBACK,
            (rolled.result as PrepareOutcome.Refused).reason
        )
        assertSame(h.ledger, rolled.ledger)
        assertEquals(2, h.ledger.size)
    }

    @Test fun theLedgerIsFullAtTheTurnLimitAndNothingIsDroppedOrMinted() {
        val h = LedgerHarness()
        val keys = (1..StateLimits.MAX_RETAINED_TURNS).map { h.prepare("turn $it") }
        assertEquals(StateLimits.MAX_RETAINED_TURNS, h.ledger.size)
        val calls = h.entropy.calls
        val step = prepareOutcome(h, TurnDraft(h.conversation, "one more"))
        assertEquals(LedgerLimit.TURNS, (step.result as PrepareOutcome.Backpressure).limit)
        assertSame(h.ledger, step.ledger)
        assertEquals(calls, h.entropy.calls) // refused before an id exists
        assertEquals(keys, h.ledger.entries.map { it.key })
        // Releasing one settled turn makes room again.
        h.apply(h.ledger.discard(keys.first()))
        h.prepare("fits now")
        assertEquals(StateLimits.MAX_RETAINED_TURNS, h.ledger.size)
    }

    @Test fun theTextByteBudgetIsExactToTheByte() {
        val h = LedgerHarness()
        val big = "😀".repeat(4_000) // 16,000 bytes
        repeat(65) { h.prepare(big) }
        val used = 65 * 16_000
        val remaining = StateLimits.MAX_RETAINED_TURN_TEXT_BYTES - used
        assertEquals(8_576, remaining)
        val tooBig = "あ".repeat(2_859) // 8,577 bytes
        val over = prepareOutcome(h, TurnDraft(h.conversation, tooBig))
        assertEquals(LedgerLimit.TEXT_BYTES, (over.result as PrepareOutcome.Backpressure).limit)
        val exact = "あ".repeat(2_858) + "é" // 3 * 2,858 + 2 = 8,576 bytes
        h.prepare(exact)
        val full = prepareOutcome(h, TurnDraft(h.conversation, "x"))
        assertEquals(LedgerLimit.TEXT_BYTES, (full.result as PrepareOutcome.Backpressure).limit)
        // Discarding a settled turn returns its bytes.
        h.apply(h.ledger.discard(h.ledger.entries.first().key))
        h.prepare("x")
    }

    @Test fun reconcilingAnIntentDetectsChangedTextOrDeadlineUnderTheSameId() {
        val h = LedgerHarness()
        val key = h.prepare("original", ttlMs = 90_000)
        val entry = h.entry(key)
        assertEquals(IntentMatch.SAME, h.ledger.reconcileIntent(key, "original", entry.expiresAtMs))
        assertEquals(
            IntentMatch.CHANGED,
            h.ledger.reconcileIntent(key, "edited", entry.expiresAtMs)
        )
        assertEquals(
            IntentMatch.CHANGED,
            h.ledger.reconcileIntent(
                key,
                "original",
                entry.expiresAtMs + 1
            )
        )
        assertEquals(
            IntentMatch.CHANGED,
            h.ledger.reconcileIntent(
                key,
                "original",
                entry.expiresAtMs - 1
            )
        )
        val other = key.copy(requestId = h.prepare("another").requestId)
        assertEquals(
            IntentMatch.CHANGED,
            h.ledger.reconcileIntent(other, "original", entry.expiresAtMs)
        )
        val unknown = key.copy(
            conversation = key.conversation.copy(
                enrollment = key.conversation.enrollment.copy(reviewEpoch = 9)
            )
        )
        assertEquals(
            IntentMatch.UNKNOWN_TURN,
            h.ledger.reconcileIntent(unknown, "original", entry.expiresAtMs)
        )
        assertNull(h.ledger.entry(unknown))
        // The stored identity is untouched by the comparisons.
        assertEquals("original", h.entry(key).text)
        assertEquals(entry.expiresAtMs, h.entry(key).expiresAtMs)
    }
}
