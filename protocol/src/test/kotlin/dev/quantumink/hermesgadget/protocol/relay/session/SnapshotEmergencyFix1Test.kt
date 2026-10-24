package dev.quantumink.hermesgadget.protocol.relay.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotEmergencyFix1Test {
    private val record = Seeds.record(ControllerFixture().identity)
    private val namespace = HistoryNamespace.from(record)!!
    private val old = (1..64).map {
        HistoryEviction(
            namespace,
            "00000000-0000-4000-8000-%012d".format(it),
            EvictionKind.CACHE_ONLY
        )
    }
    private val a = HistoryEviction(namespace, Dto.conversation.value, EvictionKind.DELETED)
    private fun encoded(section: ControllerSection) =
        (SnapshotCodec.encode(Seeds.empty(section)) as EncodeResult.Encoded).bytes
    private fun capacity(action: () -> Unit) {
        var failure: HistoryFailure? = null
        try {
            action()
        } catch (caught: HistoryFailure) {
            failure = caught
        }
        assertEquals(HistoryProblem.CAPACITY, failure?.problem)
    }

    @Test fun exactDeletionHasOneReservedRoundTrippableSlotWithoutWideningOrdinaryCaps() {
        val section = ControllerSection(listOf(record), evictions = old).owing(a)
        assertEquals(old, section.evictions)
        assertEquals(a, section.emergencyDeletion)
        assertEquals(Standing.ACTIVE, section.enrollments.single().standing)
        assertEquals(section, section.owing(a))
        val bytes = encoded(section)
        val decoded = (SnapshotCodec.decode(bytes) as DecodeResult.Decoded).document.section
        assertEquals(section, decoded)
        assertArrayEquals(bytes, encoded(decoded))
        capacity { section.owing(a.copy(conversationId = Dto.otherConversation.value)) }
        capacity { section.owing(a.copy(kind = EvictionKind.CACHE_ONLY)) }
        capacity { section.owing(a.copy(conversationId = null, kind = EvictionKind.REVOKED)) }
        assertEquals(old, section.cleared(a).evictions)
        assertEquals(null, section.cleared(a).emergencyDeletion)
    }

    @Test fun legacyDocumentTwoDecodesAllOriginalFeaturesWithoutAnEmergency() {
        val section = ControllerSection(listOf(record), evictions = old)
        val current = encoded(section)
        val legacy3 =
            current.copyOfRange(0, current.size - 4 - section.enrollments.size) +
                current.copyOfRange(current.size - 4, current.size)
        val oldBytes = (
            legacy3.copyOfRange(0, legacy3.size - 5) +
                legacy3.copyOfRange(legacy3.size - 4, legacy3.size)
            ).also { it[5] = 2 }
        val restored = (SnapshotCodec.decode(oldBytes) as DecodeResult.Decoded).document.section
        assertEquals(section, restored)
        assertArrayEquals(current, encoded(restored))
    }

    @Test fun reservedSlotCannotCarryCacheConsentOrWholeScopeAuthority() {
        for (bad in listOf(
            a.copy(conversationId = null),
            a.copy(kind = EvictionKind.CACHE_ONLY),
            a.copy(kind = EvictionKind.REVOKED)
        )) {
            var refused = false
            try {
                ControllerSection(listOf(record), evictions = old, emergencyDeletion = bad)
            } catch (
                _: IllegalArgumentException
            ) {
                refused =
                    true
            }
            assertTrue(refused)
        }
    }
}
