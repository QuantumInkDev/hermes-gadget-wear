package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AdmissionFactsSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.HistorySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.KnownMessageSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerHarness
import dev.quantumink.hermesgadget.protocol.relay.state.ObligationSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RefusedMessageSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.state.TurnSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded binary codec of the stored document: exact round trips of the COMPLETE aggregate, the derivation of its
 * finite limits from the actual fields, typed refusals before any excessive allocation, and hostile input.
 */
class SnapshotCodecTest {
    private val section = ControllerSection(
        listOf(
            EnrollmentRecord.fresh("wss://relay.example.invalid/gadget", "phone:hg-0011223344556677").copy(
                enabled = true,
                pinnedProfileId = Dto.profile,
                reviewEpoch = 3,
                standing = Standing.REVIEW_REQUIRED,
                observedProfileId = Dto.otherProfile,
                selectedConversationId = Dto.conversation,
                createIntent = CreateIntent(
                    RequestId.of("01939c8e-7a00-7000-8000-00000000abcd")!!,
                    "Quarterly plan",
                    1_792_300_000_000L
                )
            )
        )
    )

    private fun scope(index: Int = 0) = ScopeRecordSnapshot(
        "ep1:" + "ab".repeat(32),
        Dto.profile.value,
        "phone:hg-%016d".format(index),
        index.toLong()
    )

    /** A ledger with a queued turn, an UNCERTAIN one, an EXPIRED_REPORTED one and a refused one, from real transitions. */
    private fun richLedger(): TurnLedger {
        val h = LedgerHarness()
        val queued = h.prepare("queued text")
        val (token, dispatch) = h.token1(queued)
        h.reply(dispatch, h.receipt(queued, ReceiptStatus.QUEUED))
        val uncertain = h.prepare("uncertain text")
        h.timeout(h.planned(h.submit(uncertain, token)))
        val expired = h.prepare("expired text")
        h.fail(h.planned(h.submit(expired, token)), "expired")
        val refused = h.prepare("refused text")
        h.fail(h.planned(h.submit(refused, token)), "invalid_request")
        assertEquals(TurnPhase.QUEUED, h.phase(queued))
        assertEquals(TurnPhase.UNCERTAIN, h.phase(uncertain))
        return h.ledger
    }

    private fun full(ledger: TurnLedger = richLedger()): RelayDocument {
        val captures = CaptureLedgerSnapshot(
            CaptureLedgerSnapshot.VERSION,
            listOf(
                CaptureRecordSnapshot(
                    scope(),
                    Dto.conversation.value,
                    "01939c8e-7a00-7000-8000-000000001111",
                    1_792_300_000_000L,
                    "AWAITING_RESULT",
                    null,
                    "IN_FLIGHT",
                    null,
                    null,
                    null
                )
            )
        )
        val history = HistorySnapshot(
            scope(), Dto.conversation.value, "RELAY", 2, "REBUILD", "CONSUMER", "ACTIVE", null, 5, true,
            "opaque-cursor", false,
            listOf(KnownMessageSnapshot("00000000-0000-4000-9000-000000000001", "assistant", 77, 2)),
            listOf(RefusedMessageSnapshot("00000000-0000-4000-9000-000000000002", "TOO_MANY_SEGMENTS", 500))
        )
        val delivery = DeliverySnapshot(
            scope(),
            9,
            listOf(
                SeenKeySnapshot("canonical", Dto.conversation.value, "00000000-0000-4000-a000-000000000001", 5),
                SeenKeySnapshot("opaque", "stock-chat", "stock-final", 6)
            ),
            listOf(ObligationSnapshot(Dto.conversation.value, "HISTORY", "ATTACHED")),
            listOf("00000000-0000-4000-b000-000000000001")
        )
        val ledgerSnapshot = ledger.snapshot()
        return RelayDocument(
            section,
            RelayStateSnapshot(2, ledgerSnapshot, listOf(history), listOf(delivery), captures)
        )
    }

    private fun encoded(document: RelayDocument): ByteArray =
        (SnapshotCodec.encode(document) as EncodeResult.Encoded).bytes

    @Test fun theCompleteAggregateRoundTripsExactly() {
        val document = full()
        val bytes = encoded(document)
        val decoded = (SnapshotCodec.decode(bytes) as DecodeResult.Decoded).document
        assertEquals(document.section, decoded.section)
        assertEquals(2, decoded.snapshot.version)
        // Deterministic and idempotent: encoding what was decoded gives the very same bytes.
        assertArrayEquals(bytes, encoded(decoded))
        val turns = decoded.snapshot.ledger.turns
        assertEquals(document.snapshot.ledger.turns.size, turns.size)
        assertEquals("queued text", turns.first().text)
        assertEquals(1, decoded.snapshot.captures.records.size)
        assertEquals("AWAITING_RESULT", decoded.snapshot.captures.records.single().state)
        assertEquals(1, decoded.snapshot.histories.single().known.size)
        assertEquals(2, decoded.snapshot.deliveries.single().seen.size)
        // Every version-1 turn state survives the round trip: UNCERTAIN, EXPIRED_REPORTED and the refusal.
        assertEquals(document.snapshot.ledger.turns.map { it.closure }, turns.map { it.closure })
        assertEquals(document.snapshot.ledger.turns.map { it.lastErrorCode }, turns.map { it.lastErrorCode })
    }

    @Test fun aVersionOneAggregateRoundTripsAndRestoresWithoutCapturesAndKeepsEveryTurnState() {
        val ledger = richLedger()
        val v1 = RelayStateSnapshot(1, ledger.snapshot(), emptyList(), emptyList())
        val bytes = encoded(RelayDocument(section, v1))
        val decoded = (SnapshotCodec.decode(bytes) as DecodeResult.Decoded).document
        assertEquals(1, decoded.snapshot.version)
        assertTrue(decoded.snapshot.captures.records.isEmpty())
        val restored = RelayStateRestore.restore(decoded.snapshot, StateTestSupport.at(60_000), RelayAuthority.fresh())
        assertTrue(restored.toString(), restored is RestoreResult.Restored)
        val state = (restored as RestoreResult.Restored).value
        // A restored turn that may have reached the server is UNCERTAIN (process loss); none was lost or converted.
        assertEquals(ledger.entries.size, state.ledger.entries.size)
        assertTrue(state.ledger.entries.none { it.phase == TurnPhase.PREPARED && it.sent })
        // Writing it again as a version 2 aggregate keeps the same turns and adds the explicit empty capture section.
        val rebuilt = (
            RelayStateSnapshot.of(state.ledger, state.histories, state.deliveries, VoiceCaptureLedger.empty())
                as SnapshotBuild.Built
            ).snapshot
        assertEquals(2, rebuilt.version)
        assertEquals(
            ledger.snapshot().turns.map { it.requestId to it.text },
            rebuilt.ledger.turns.map { it.requestId to it.text }
        )
    }

    @Test fun anUnsupportedVersionOrMagicIsReportedAndNeverReinterpreted() {
        val bytes = encoded(full())
        val wrongMagic = bytes.copyOf().also { it[0] = 0x00 }
        assertEquals(DecodeRefusal.UNSUPPORTED_VERSION, (SnapshotCodec.decode(wrongMagic) as DecodeResult.Rejected).reason)
        val futureFormat = bytes.copyOf().also { it[5] = 9 }
        assertEquals(DecodeRefusal.UNSUPPORTED_VERSION, (SnapshotCodec.decode(futureFormat) as DecodeResult.Rejected).reason)
        val futureSnapshot = bytes.copyOf().also { it[7] = 9 }
        assertEquals(DecodeRefusal.UNSUPPORTED_VERSION, (SnapshotCodec.decode(futureSnapshot) as DecodeResult.Rejected).reason)
    }

    @Test fun everyTruncationAndSingleByteCorruptionIsATypedRejectionNeverAnExceptionOrAShortenedDocument() {
        val bytes = encoded(full())
        for (length in 0 until bytes.size) {
            val result = SnapshotCodec.decode(bytes.copyOf(length))
            assertTrue("truncated to $length", result is DecodeResult.Rejected)
        }
        assertTrue(SnapshotCodec.decode(bytes + byteArrayOf(0)) is DecodeResult.Rejected)
        // Any single flipped byte either decodes to a DIFFERENT valid document or is rejected; it never throws.
        var rejected = 0
        for (index in bytes.indices step 7) {
            val copy = bytes.copyOf()
            copy[index] = (copy[index].toInt() xor 0x5a).toByte()
            if (SnapshotCodec.decode(copy) is DecodeResult.Rejected) rejected++
        }
        assertTrue(rejected > 0)
    }

    @Test fun aHostileLengthOrCountCostsNothingAndNeverAllocatesItsClaim() {
        val bytes = encoded(RelayDocument(section, emptySnapshot()))
        // A string claiming 2 GiB inside a 100-byte buffer: rejected before any allocation of the claim.
        val hostileString = bytes.copyOf()
        val at = 8 + 2 + 4 // section count, then the first string length (endpointKey)
        hostileString[at] = 0x7f
        hostileString[at + 1] = 0xff.toByte()
        hostileString[at + 2] = 0xff.toByte()
        hostileString[at + 3] = 0xff.toByte()
        val rejected = SnapshotCodec.decode(hostileString) as DecodeResult.Rejected
        assertEquals(DecodeRefusal.CORRUPT, rejected.reason)
        // A count above its policy limit is TOO_MANY, not a prefix.
        val hostileCount = bytes.copyOf()
        hostileCount[8] = 0xff.toByte()
        hostileCount[9] = 0xff.toByte()
        val many = SnapshotCodec.decode(hostileCount) as DecodeResult.Rejected
        assertEquals(DecodeRefusal.TOO_MANY, many.reason)
        assertEquals("section.enrollments", many.path)
        // Invalid UTF-8 in a string is corruption.
        val badUtf8 = bytes.copyOf()
        badUtf8[at + 4] = 0xc3.toByte()
        badUtf8[at + 5] = 0x28
        assertTrue(SnapshotCodec.decode(badUtf8) is DecodeResult.Rejected)
    }

    private fun emptySnapshot(): RelayStateSnapshot = (
        RelayStateSnapshot.of(TurnLedger.empty(), emptyList(), emptyList(), VoiceCaptureLedger.empty())
            as SnapshotBuild.Built
        ).snapshot

    @Test fun fieldsAndCountsOverTheirLimitAreTypedRefusalsNothingIsShortenedOrDropped() {
        val base = full()
        val turn = base.snapshot.ledger.turns.first()
        fun withTurn(changed: TurnSnapshot, copies: Int = 1) = RelayDocument(
            base.section,
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(List(copies) { changed }, base.snapshot.ledger.scopes),
                emptyList(),
                emptyList(),
                CaptureLedgerSnapshot.EMPTY
            )
        )
        val tooLongText = withTurn(
            TurnSnapshot(
                turn.scope, turn.conversationId, turn.requestId, turn.expiresAtMs, turn.preparedAtMs,
                turn.fingerprint, "x".repeat(StoreBounds.TURN_TEXT_BYTES + 1), turn.sent, turn.facts, turn.closure,
                turn.held, turn.lastErrorCode, turn.anomalies
            )
        )
        val refused = SnapshotCodec.encode(tooLongText) as EncodeResult.Refused
        assertEquals(EncodeRefusal.FIELD_TOO_LONG, refused.reason)
        assertEquals("ledger.turns[0].text", refused.path)
        val unencodable = withTurn(
            TurnSnapshot(
                turn.scope, turn.conversationId, turn.requestId, turn.expiresAtMs, turn.preparedAtMs,
                turn.fingerprint, "lone \ud800 surrogate", turn.sent, turn.facts, turn.closure,
                turn.held, turn.lastErrorCode, turn.anomalies
            )
        )
        assertEquals(EncodeRefusal.INVALID_VALUE, (SnapshotCodec.encode(unencodable) as EncodeResult.Refused).reason)
        // One more turn than the ledger holds: the public constructor keeps one past the limit and the codec refuses it.
        val tooMany = withTurn(turn, StateLimits.MAX_RETAINED_TURNS + 1)
        val many = SnapshotCodec.encode(tooMany) as EncodeResult.Refused
        assertEquals(EncodeRefusal.TOO_MANY, many.reason)
        assertEquals("ledger.turns", many.path)
        // Seventeen enrollments.
        val enrollments = ControllerSection(
            (0..StateLimits.MAX_ENROLLMENTS).map {
                EnrollmentRecord.fresh("wss://h$it.example.invalid/g", "phone:hg-%016d".format(it))
            }
        )
        val tooManyEnrollments = SnapshotCodec.encode(RelayDocument(enrollments, emptySnapshot())) as EncodeResult.Refused
        assertEquals(EncodeRefusal.TOO_MANY, tooManyEnrollments.reason)
        assertEquals("section.enrollments", tooManyEnrollments.path)
    }

    @Test fun theDeliberateStorageCapIsNamedBackpressureBelowTheFullCapacity() {
        // The state layer would accept far more than the cap: the derivation of the full capacity is exact and larger.
        assertTrue(StoreBounds.capIsBelowFullCapacity)
        assertTrue(StoreBounds.theoreticalMaxPlaintextBytes() > 3L * StoreBounds.PLAINTEXT_CAP_BYTES)
        // Sixteen delivery tables with opaque keys at their field caps exceed the cap: refused as local backpressure.
        val seen = List(StateLimits.MAX_SEEN_KEYS) {
            SeenKeySnapshot("opaque", "c".repeat(128), "e".repeat(200) + "%056d".format(it), 1)
        }
        val deliveries = (0 until StateLimits.MAX_ENROLLMENTS).map {
            DeliverySnapshot(scope(it), null, seen, emptyList(), emptyList())
        }
        val big = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(2, TurnLedgerSnapshot(emptyList(), emptyList()), emptyList(), deliveries, CaptureLedgerSnapshot.EMPTY)
        )
        val result = SnapshotCodec.encode(big) as EncodeResult.Refused
        assertEquals(EncodeRefusal.STORAGE_CAP, result.reason)
        // ... while a realistic table of canonical keys fits comfortably and is NOT refused.
        val canonical = List(StateLimits.MAX_SEEN_KEYS) {
            SeenKeySnapshot("canonical", Dto.conversation.value, "00000000-0000-4000-a000-%012d".format(it), 1)
        }
        val fits = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(emptyList(), emptyList()),
                emptyList(),
                (0 until 8).map { DeliverySnapshot(scope(it), null, canonical, emptyList(), emptyList()) },
                CaptureLedgerSnapshot.EMPTY
            )
        )
        val size = encoded(fits).size
        assertTrue(size < StoreBounds.PLAINTEXT_CAP_BYTES)
        assertTrue(size > 3_000_000)
    }

    @Test fun theDerivedMaximaAreExactForEveryComponentAtItsFieldCaps() {
        val empty = StoreBounds.emptyDocumentBytes()
        assertEquals(empty, encoded(RelayDocument(ControllerSection.EMPTY, emptySnapshot())).size.toLong())
        val wideScope = ScopeRecordSnapshot(
            "€".repeat(128),
            Dto.profile.value,
            "€".repeat(128),
            Long.MAX_VALUE
        )
        val uuid = Dto.conversation.value
        val enum = "E".repeat(StoreBounds.ENUM_BYTES)
        // One turn with every field at its cap.
        val facts = AdmissionFactsSnapshot(uuid, 1, enum, 1L, enum, 1L)
        val turn = TurnSnapshot(
            wideScope, uuid, uuid, 1, 1, "f".repeat(64), "t".repeat(StoreBounds.TURN_TEXT_BYTES), true, facts,
            enum, true, enum, 1
        )
        val one = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(2, TurnLedgerSnapshot(listOf(turn), emptyList()), emptyList(), emptyList(), CaptureLedgerSnapshot.EMPTY)
        )
        assertEquals(
            empty + StoreBounds.turnFixedMax + 4 + StoreBounds.TURN_TEXT_BYTES,
            encoded(one).size.toLong()
        )
        // One scope decision.
        val decision = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(emptyList(), listOf(ScopeStateSnapshot(wideScope, enum, true))),
                emptyList(),
                emptyList(),
                CaptureLedgerSnapshot.EMPTY
            )
        )
        assertEquals(empty + StoreBounds.scopeDecisionMax, encoded(decision).size.toLong())
        // One history with every list full.
        val history = HistorySnapshot(
            wideScope, uuid, enum, 1, enum, enum, enum, enum, 1, true, "c".repeat(StoreBounds.CURSOR_BYTES), true,
            List(StoreBounds.KNOWN_MESSAGES) { KnownMessageSnapshot(uuid, enum, 1, 1) },
            List(StoreBounds.REFUSED_MESSAGES) { RefusedMessageSnapshot(uuid, enum, 1) }
        )
        val histories = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(2, TurnLedgerSnapshot(emptyList(), emptyList()), listOf(history), emptyList(), CaptureLedgerSnapshot.EMPTY)
        )
        assertEquals(empty + StoreBounds.historyMax, encoded(histories).size.toLong())
        // One delivery with every list full.
        val delivery = DeliverySnapshot(
            wideScope,
            1,
            List(StoreBounds.SEEN_KEYS) { SeenKeySnapshot(enum, "c".repeat(128), "e".repeat(256), 1) },
            List(StoreBounds.OBLIGATIONS) { ObligationSnapshot(uuid, enum, enum) },
            List(StoreBounds.PENDING_ACKS) { uuid }
        )
        val deliveries = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(2, TurnLedgerSnapshot(emptyList(), emptyList()), emptyList(), listOf(delivery), CaptureLedgerSnapshot.EMPTY)
        )
        assertEquals(empty + StoreBounds.deliveryMax, encoded(deliveries).size.toLong())
        // One capture record with every optional field present.
        val capture = CaptureRecordSnapshot(wideScope, uuid, uuid, 1, enum, uuid, enum, enum, enum, 1L)
        val captures = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(emptyList(), emptyList()),
                emptyList(),
                emptyList(),
                CaptureLedgerSnapshot(CaptureLedgerSnapshot.VERSION, listOf(capture))
            )
        )
        assertEquals(empty + StoreBounds.captureMax, encoded(captures).size.toLong())
        // One enrollment with every optional field present.
        val record = EnrollmentRecord(
            "€".repeat(128), "w".repeat(StoreBounds.URL_BYTES), "€".repeat(128), true, Dto.profile, 1,
            Standing.STOPPED, Dto.otherProfile, Dto.conversation,
            CreateIntent(RequestId.of("01939c8e-7a00-7000-8000-00000000abcd")!!, "t".repeat(StoreBounds.TITLE_BYTES), 1)
        )
        val enrollment = RelayDocument(ControllerSection(listOf(record)), emptySnapshot())
        // `standing` is written by its name, which is shorter than the cap: the derivation is an upper bound, not equal.
        val measured = encoded(enrollment).size.toLong()
        assertTrue(measured <= empty + StoreBounds.enrollmentMax)
        assertTrue(measured > empty + StoreBounds.enrollmentMax - 64)
        assertNotNull(ConversationId.of(uuid))
        assertEquals(StateLimits.MAX_SCOPE_DECISIONS, StoreBounds.SCOPE_DECISIONS)
        assertEquals(VoiceLimits.CAPTURES_TOTAL, StoreBounds.CAPTURES)
        assertFalse(StoreBounds.MAX_FILE_BYTES <= StoreBounds.PLAINTEXT_CAP_BYTES)
    }

    @Test fun theDocumentedMaximaAreTheComputedOnes() {
        // These numbers are quoted in docs/relay-phone-controller.md: a change to a limit or a field cap must change both.
        assertEquals(824L, StoreBounds.scopeRecordMax)
        assertEquals(203L, StoreBounds.admissionFactsMax)
        assertEquals(1_337L, StoreBounds.turnFixedMax)
        assertEquals(893L, StoreBounds.scopeDecisionMax)
        assertEquals(254_673L, StoreBounds.historyMax)
        assertEquals(1_950_663L, StoreBounds.deliveryMax)
        assertEquals(1_237L, StoreBounds.captureMax)
        assertEquals(3_594L, StoreBounds.enrollmentMax)
        assertEquals(28L, StoreBounds.emptyDocumentBytes())
        assertEquals(36_699_452L, StoreBounds.theoreticalMaxPlaintextBytes())
        assertEquals(8L * 1024 * 1024, StoreBounds.PLAINTEXT_CAP_BYTES.toLong())
        assertEquals(8L * 1024 * 1024 + 38, StoreBounds.MAX_FILE_BYTES.toLong())
    }
}
