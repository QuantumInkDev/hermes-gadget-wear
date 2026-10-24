package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceLimits

/**
 * The finite limits of the stored document, derived from the ACTUAL fields of the complete aggregate snapshot
 * ([dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot], version 2 with the capture section) and the
 * controller section, and from the scalar encodings of [SnapshotCodec].
 *
 * Two different numbers live here and are never mixed:
 *  - [theoreticalMaxPlaintextBytes]: the size of a document in which EVERY collection is at its policy limit and every
 *    string at its field cap. It is a structural upper bound; valid enum spellings and namespaces can be shorter.
 *  - [PLAINTEXT_CAP_BYTES]: the DELIBERATELY SMALLER local storage cap. A document above it is refused with
 *    [EncodeRefusal.STORAGE_CAP]: that is local backpressure (nothing is sent, nothing is truncated or dropped), not a
 *    claim that the cap equals the state layer's full capacity.
 */
object StoreBounds {
    /** Local storage cap for the plaintext of the document. See the class comment: backpressure, not full capacity. */
    const val PLAINTEXT_CAP_BYTES = 8 * 1024 * 1024

    // -- field caps (UTF-8 bytes). A string over its cap is refused, never shortened --------------------------------

    /** `RelayScope.MAX_KEY` characters, each at most three UTF-8 bytes (a surrogate pair is four bytes for two chars). */
    const val SCOPE_KEY_BYTES = RelayScope.MAX_KEY * 3
    const val UUID_BYTES = 36
    const val FINGERPRINT_BYTES = 64
    const val TURN_TEXT_BYTES = RelayLimits.SUBMIT_TEXT_BYTES
    const val ENUM_BYTES = 64
    const val CURSOR_BYTES = RelayLimits.CURSOR_BYTES
    const val OPAQUE_CONVERSATION_BYTES = 128
    const val OPAQUE_EVENT_BYTES = 256
    const val URL_BYTES = EnrollmentRecord.MAX_URL_CHARS
    const val TITLE_BYTES = RelayLimits.CREATE_TITLE_BYTES

    // -- counts (the policy limits of the state layer; a count over its limit is refused as TooMany) ---------------

    const val ENROLLMENTS = StateLimits.MAX_ENROLLMENTS
    const val TURNS = StateLimits.MAX_RETAINED_TURNS
    const val SCOPE_DECISIONS = StateLimits.MAX_SCOPE_DECISIONS
    const val HISTORIES = StateLimits.MAX_HISTORY_SCOPES
    const val KNOWN_MESSAGES = StateLimits.MAX_KNOWN_MESSAGES
    const val REFUSED_MESSAGES = StateLimits.MAX_REFUSED_MESSAGES
    const val DELIVERIES = StateLimits.MAX_ENROLLMENTS
    const val SEEN_KEYS = StateLimits.MAX_SEEN_KEYS
    const val OBLIGATIONS = DeliverySnapshot.MAX_OBLIGATIONS
    const val PENDING_ACKS = StateLimits.MAX_PENDING_ACKS
    const val CAPTURES = VoiceLimits.CAPTURES_TOTAL
    const val TURN_TEXT_TOTAL_BYTES = StateLimits.MAX_RETAINED_TURN_TEXT_BYTES

    // -- per-structure maxima under the codec's encodings (u8 flag, u16 count, i32 length prefix, i64 number) --------

    private fun string(max: Int): Long = 4L + max

    private fun nullableString(max: Int): Long = 1L + string(max)

    val scopeRecordMax: Long =
        string(SCOPE_KEY_BYTES) + string(UUID_BYTES) + string(SCOPE_KEY_BYTES) + 8

    val admissionFactsMax: Long =
        string(UUID_BYTES) + 8 + string(ENUM_BYTES) + 9 + nullableString(ENUM_BYTES) + 9

    /** Fixed part of one turn, without its text. */
    val turnFixedMax: Long = scopeRecordMax + string(UUID_BYTES) + string(UUID_BYTES) + 8 + 8 +
        string(FINGERPRINT_BYTES) + 1 + 1 + 1 + admissionFactsMax + nullableString(ENUM_BYTES) +
        1 + nullableString(ENUM_BYTES) + 4

    val scopeDecisionMax: Long = scopeRecordMax + string(ENUM_BYTES) + 1

    val historyMax: Long = scopeRecordMax + string(UUID_BYTES) + string(ENUM_BYTES) + 8 +
        string(ENUM_BYTES) + nullableString(ENUM_BYTES) + string(ENUM_BYTES) +
        nullableString(ENUM_BYTES) + 8 + 1 + nullableString(CURSOR_BYTES) + 1 + 2 +
        KNOWN_MESSAGES * (string(UUID_BYTES) + string(ENUM_BYTES) + 8 + 4) + 2 +
        REFUSED_MESSAGES * (string(UUID_BYTES) + string(ENUM_BYTES) + 4)

    val deliveryMax: Long = scopeRecordMax + 9 + 2 +
        SEEN_KEYS * (
            string(ENUM_BYTES) + string(OPAQUE_CONVERSATION_BYTES) +
                string(OPAQUE_EVENT_BYTES) + 8
            ) + 2 +
        OBLIGATIONS * (nullableString(UUID_BYTES) + string(ENUM_BYTES) + string(ENUM_BYTES)) + 2 +
        PENDING_ACKS * string(UUID_BYTES)

    val captureMax: Long = scopeRecordMax + string(UUID_BYTES) + string(UUID_BYTES) + 8 +
        string(ENUM_BYTES) + nullableString(UUID_BYTES) + nullableString(ENUM_BYTES) +
        nullableString(ENUM_BYTES) + nullableString(ENUM_BYTES) + 9

    val enrollmentMax: Long =
        string(SCOPE_KEY_BYTES) + string(URL_BYTES) + string(SCOPE_KEY_BYTES) +
            1 + nullableString(UUID_BYTES) + 8 + string(ENUM_BYTES) + nullableString(UUID_BYTES) +
            nullableString(UUID_BYTES) + 1 + string(UUID_BYTES) + nullableString(TITLE_BYTES) + 8

    const val HEADER_BYTES = 4L + 2 + 2
    const val TRAILER_BYTES = 4L

    /** Five aggregate counts, capture version/count and two feature counts; the emergency flag is separate. */
    const val COUNTS_BYTES = 2L * 5 + 4 + 2 + 2 + 2

    val historyNamespaceMax: Long =
        string(SCOPE_KEY_BYTES) * 2 + string(URL_BYTES) + string(UUID_BYTES) + 8
    val metadataIntentMax: Long =
        historyNamespaceMax + string(UUID_BYTES) * 2 + 8 + nullableString(4096) + 2 + 8 +
            string(ENUM_BYTES) +
            2
    val historyEvictionMax: Long =
        historyNamespaceMax + nullableString(UUID_BYTES) + string(ENUM_BYTES)

    /** The size of a format 3 / aggregate version 2 document with every collection empty. */
    fun emptyDocumentBytes(): Long = HEADER_BYTES + TRAILER_BYTES + COUNTS_BYTES + 1

    /** Every collection at its limit and every string at its cap; turn text bounded by the ledger-wide total. */
    fun theoreticalMaxPlaintextBytes(): Long = emptyDocumentBytes() +
        ENROLLMENTS * enrollmentMax +
        TURNS * turnFixedMax + TURN_TEXT_TOTAL_BYTES + TURNS * 4L +
        SCOPE_DECISIONS * scopeDecisionMax +
        HISTORIES * historyMax +
        DELIVERIES * deliveryMax +
        CAPTURES * captureMax + 16 * metadataIntentMax + 64 * historyEvictionMax +
        historyEvictionMax

    /** True: the deliberate cap is below the full capacity the state layer itself would accept. */
    val capIsBelowFullCapacity: Boolean get() = theoreticalMaxPlaintextBytes() > PLAINTEXT_CAP_BYTES

    /** Container overhead around the plaintext: version, iv length, iv, commit sequence, and the GCM tag. */
    const val CONTAINER_OVERHEAD_BYTES = 1 + 1 + 12 + 8 + 16

    /** The largest legal file on disk. */
    const val MAX_FILE_BYTES = PLAINTEXT_CAP_BYTES + CONTAINER_OVERHEAD_BYTES
}
