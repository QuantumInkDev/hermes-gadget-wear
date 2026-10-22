package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UUIDv7 generation and the exact source age/expiry rules (`relay_store.uuid7`, `reserve_turn`). */
class RequestIdsTest {
    private val now = 1_792_300_000_000L

    private fun idAt(timestampMs: Long, seed: Int = 1): RequestId {
        val minted = RequestIds.mint(
            StateTestSupport.raw(timestampMs),
            StateTestSupport.entropy(seed)
        )
        return (minted as MintResult.Minted).id
    }

    private fun entropyOf(vararg bytes: Int) = RequestIdEntropy { out ->
        for (i in out.indices) out[i] = bytes[i].toByte()
    }

    @Test fun mintedIdsMatchAnIndependentlyDerivedLayoutForFixedEntropy() {
        // Expected strings were derived by a separate Python implementation and parsed by uuid.UUID: version 7,
        // RFC 4122 variant, 48-bit timestamp in the top bits (the source validator's `int >> 80`).
        fun mint(timestamp: Long, entropy: RequestIdEntropy): String = (
            RequestIds.mint(
                StateTestSupport.raw(timestamp),
                entropy
            ) as MintResult.Minted
            ).id.value
        val sequence = entropyOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val ones = entropyOf(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
        val lowest = entropyOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assertEquals("01a14d67-a300-7102-8304-05060708090a", mint(now, sequence))
        assertEquals("01a14d67-a300-7fff-bfff-ffffffffffff", mint(now, ones))
        assertEquals("01a14d67-a300-7000-8000-000000000001", mint(now, lowest))
        assertEquals("00000000-0000-7102-8304-05060708090a", mint(0, sequence))
        assertEquals(
            "ffffffff-ffff-7fff-bfff-ffffffffffff",
            mint(StateLimits.UUID_TIMESTAMP_MAX_MS, ones)
        )
        // The captured submit vectors were produced at the same server time: same timestamp prefix.
        val captured = RelayVectors.forMethod(RelayMethod.TURN_SUBMIT).first { it.isSuccess }
            .result["request_id"].toString().trim('"')
        assertTrue(captured.startsWith("01a14d67-a300-7"))
    }

    @Test fun everyMintedIdIsACanonicalUuidV7WhoseTimestampIsTheServerEstimate() {
        for (seed in 1..200) {
            val id = idAt(now + seed, seed)
            assertNotNull(RequestId.of(id.value))
            assertEquals('7', id.value[14])
            assertTrue(id.value[19] in "89ab")
            assertEquals(id.value, id.value.lowercase())
            assertEquals(now + seed, RequestIds.timestampMs(id))
        }
    }

    @Test fun timestampBoundsAndBrokenEntropyAreRefusedNotRepaired() {
        val seed = StateTestSupport.entropy(3)
        assertTrue(
            RequestIds.mint(
                StateTestSupport.raw(StateLimits.UUID_TIMESTAMP_MAX_MS),
                seed
            ) is MintResult.Minted
        )
        assertEquals(
            MintRefusal.TIMESTAMP_OUT_OF_RANGE,
            (
                RequestIds.mint(
                    StateTestSupport.raw(StateLimits.UUID_TIMESTAMP_MAX_MS + 1),
                    seed
                ) as MintResult.Refused
                )
                .reason
        )
        assertEquals(
            MintRefusal.TIMESTAMP_OUT_OF_RANGE,
            (RequestIds.mint(StateTestSupport.raw(-1), seed) as MintResult.Refused).reason
        )
        val failing = RequestIdEntropy { throw IllegalStateException("no entropy") }
        assertEquals(
            MintRefusal.ENTROPY_FAILED,
            (RequestIds.mint(StateTestSupport.raw(now), failing) as MintResult.Refused).reason
        )
        val zero = RequestIdEntropy { }
        assertEquals(
            MintRefusal.ENTROPY_DEGENERATE,
            (RequestIds.mint(StateTestSupport.raw(now), zero) as MintResult.Refused).reason
        )
    }

    @Test fun theSeamIsAskedForExactlyTheDocumentedBytesOncePerId() {
        var sizes = emptyList<Int>()
        RequestIds.mint(StateTestSupport.raw(now)) { bytes ->
            sizes = sizes + bytes.size
            bytes.fill(9)
        }
        assertEquals(listOf(StateLimits.REQUEST_ID_ENTROPY_BYTES), sizes)
    }

    @Test fun newIdAgeBoundariesAreInclusiveExactlyAsInTheSource() {
        // expired iff timestamp < now - 604800000 or timestamp > now + 300000
        val oldest = now - StateLimits.ID_RETENTION_MS
        assertEquals(IdAge.FRESH, RequestIds.checkNew(idAt(oldest), now))
        assertEquals(IdAge.TOO_OLD, RequestIds.checkNew(idAt(oldest - 1), now))
        assertEquals(IdAge.FRESH, RequestIds.checkNew(idAt(now), now))
        val newest = now + StateLimits.ID_FUTURE_MS
        assertEquals(IdAge.FRESH, RequestIds.checkNew(idAt(newest), now))
        assertEquals(IdAge.TOO_FAR_AHEAD, RequestIds.checkNew(idAt(newest + 1), now))
        assertEquals(604_800_000L, StateLimits.ID_RETENTION_MS)
        assertEquals(300_000L, StateLimits.ID_FUTURE_MS)
    }

    @Test fun ageChecksNeverWrapAndRefuseATimeOutsideTheServersRange() {
        val id = idAt(1)
        assertEquals(IdAge.TIME_OUT_OF_RANGE, RequestIds.checkNew(id, -1))
        assertEquals(
            IdAge.TIME_OUT_OF_RANGE,
            RequestIds.checkNew(
                id,
                StateLimits.SERVER_TIME_MAX_MS + 1
            )
        )
        assertEquals(IdAge.TIME_OUT_OF_RANGE, RequestIds.checkNew(id, Long.MAX_VALUE))
        // At the top of the range the sums stay inside int64.
        assertEquals(IdAge.TOO_OLD, RequestIds.checkNew(id, StateLimits.SERVER_TIME_MAX_MS))
        // A tiny `now` makes `now - retention` negative; the zero timestamp is still fresh.
        assertEquals(IdAge.FRESH, RequestIds.checkNew(idAt(0), 0))
    }

    @Test fun aRetainedReceiptLookupChecksFormWithoutAnyFreshness() {
        val ancient = idAt(0)
        assertEquals(IdAge.TOO_OLD, RequestIds.checkNew(ancient, now)) // unusable for a NEW turn
        assertEquals(ancient, RequestIds.retainedLookupId(ancient.value)) // still a valid lookup id
        val future = idAt(StateLimits.UUID_TIMESTAMP_MAX_MS)
        assertEquals(future, RequestIds.retainedLookupId(future.value))
        val valid = ancient.value
        for (bad in listOf(
            valid.uppercase(),
            valid.replace("-", ""),
            valid.substring(0, 14) + "4" + valid.substring(15), // version 4
            valid.substring(0, 19) + "c" + valid.substring(20), // wrong variant
            "",
            valid + "0"
        )) {
            assertNull(bad, RequestIds.retainedLookupId(bad))
        }
    }

    @Test fun turnExpiryIsStrictlyAfterNowAndAtMostFiveMinutesAhead() {
        // now < e <= now + 300000
        assertEquals(ExpiryCheck.NOT_AFTER_NOW, RequestIds.checkNewTurnExpiry(now, now - 1))
        assertEquals(ExpiryCheck.NOT_AFTER_NOW, RequestIds.checkNewTurnExpiry(now, now))
        assertEquals(ExpiryCheck.VALID, RequestIds.checkNewTurnExpiry(now, now + 1))
        assertEquals(ExpiryCheck.VALID, RequestIds.checkNewTurnExpiry(now, now + 300_000))
        assertEquals(ExpiryCheck.TOO_FAR_AHEAD, RequestIds.checkNewTurnExpiry(now, now + 300_001))
        assertEquals(ExpiryCheck.TOO_FAR_AHEAD, RequestIds.checkNewTurnExpiry(now, Long.MAX_VALUE))
        assertEquals(ExpiryCheck.TIME_OUT_OF_RANGE, RequestIds.checkNewTurnExpiry(-1, 5))
        assertEquals(
            ExpiryCheck.TIME_OUT_OF_RANGE,
            RequestIds.checkNewTurnExpiry(StateLimits.SERVER_TIME_MAX_MS + 1, Long.MAX_VALUE)
        )
        assertEquals(
            ExpiryCheck.VALID,
            RequestIds.checkNewTurnExpiry(
                StateLimits.SERVER_TIME_MAX_MS,
                StateLimits.SERVER_TIME_MAX_MS + 300_000
            )
        )
    }

    @Test fun proposedExpiryUsesTheCheckedLifetimeRangeAndRefusesOverflow() {
        assertNull(RequestIds.proposeExpiry(now, 0))
        assertEquals(now + 1, RequestIds.proposeExpiry(now, 1))
        assertEquals(now + 300_000, RequestIds.proposeExpiry(now, 300_000))
        assertNull(RequestIds.proposeExpiry(now, 300_001))
        assertNull(RequestIds.proposeExpiry(now, -5))
        assertNull(RequestIds.proposeExpiry(-1, 10))
        assertNull(RequestIds.proposeExpiry(StateLimits.SERVER_TIME_MAX_MS + 1, 10))
        assertNotNull(RequestIds.proposeExpiry(StateLimits.SERVER_TIME_MAX_MS, 300_000))
    }

    @Test fun theSecureSeamProducesDistinctNonDegenerateIds() {
        val entropy = SecureRequestIdEntropy()
        val ids = (1..64).map {
            (RequestIds.mint(StateTestSupport.raw(now), entropy) as MintResult.Minted).id.value
        }.toSet()
        assertEquals(64, ids.size)
        assertFalse(entropy.toString().contains("Random"))
    }
}
