package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AdmissionFactsSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.HistorySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.KnownMessageSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ObligationSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RefusedMessageSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.TurnSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureRecordSnapshot
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What is stored: the controller section plus the COMPLETE aggregate snapshot. The snapshot always carries its capture
 * section explicitly (version 2), or is a version 1 value with none: nothing here ever defaults the captures.
 */
class RelayDocument(val section: ControllerSection, val snapshot: RelayStateSnapshot) {
    override fun toString(): String = "RelayDocument($section, $snapshot)"
}

enum class EncodeRefusal {
    /** A collection is above its policy limit (path names it); nothing was shortened. */
    TOO_MANY,

    /** A string is above its field cap (path names it); nothing was shortened. */
    FIELD_TOO_LONG,

    /** A value the format cannot carry (unencodable text, unsupported snapshot or section version). */
    INVALID_VALUE,

    /** The plaintext would exceed [StoreBounds.PLAINTEXT_CAP_BYTES]: LOCAL backpressure, not full capacity. */
    STORAGE_CAP
}

sealed interface EncodeResult {
    class Encoded(val bytes: ByteArray) : EncodeResult {
        override fun toString(): String = "Encoded(${bytes.size} bytes)"
    }

    class Refused(val reason: EncodeRefusal, val path: String) : EncodeResult {
        override fun toString(): String = "Refused($reason, $path)"
    }
}

enum class DecodeRefusal {
    /** A magic or format/section version this build does not know. The bytes are kept, never reinterpreted. */
    UNSUPPORTED_VERSION,

    /** Structurally impossible: truncated, over a field cap, bad UTF-8, trailing bytes, bad flag. */
    CORRUPT,

    /** A count above its policy limit. */
    TOO_MANY
}

sealed interface DecodeResult {
    class Decoded(val document: RelayDocument) : DecodeResult {
        override fun toString(): String = "Decoded($document)"
    }

    class Rejected(val reason: DecodeRefusal, val path: String) : DecodeResult {
        override fun toString(): String = "Rejected($reason, $path)"
    }
}

/**
 * The bounded binary format of the stored plaintext (see docs/relay-phone-controller.md for the byte layout and the
 * derivation of every limit). Big-endian scalars, a u16 count per collection, an i32 length per string; no recursion, no
 * dynamic type, no JSON. A decode checks every count against its policy limit and every length against its field cap
 * and the remaining bytes BEFORE it allocates, so a hostile or corrupt input costs a bounded amount of work.
 */
object SnapshotCodec {
    const val MAGIC = 0x52533300
    const val TRAILER = 0x454E4400
    const val FORMAT_VERSION = 2

    fun encode(document: RelayDocument): EncodeResult = try {
        EncodeResult.Encoded(Writer.run(document))
    } catch (refusal: EncodeFailure) {
        EncodeResult.Refused(refusal.reason, refusal.path)
    }

    fun decode(bytes: ByteArray): DecodeResult = try {
        DecodeResult.Decoded(Reader(bytes).document())
    } catch (failure: DecodeFailure) {
        DecodeResult.Rejected(failure.reason, failure.path)
    }

    private class EncodeFailure(val reason: EncodeRefusal, val path: String) :
        RuntimeException(path, null, false, false)

    private class DecodeFailure(val reason: DecodeRefusal, val path: String) :
        RuntimeException(path, null, false, false)

    // -- writer ----------------------------------------------------------------------------------------------

    private class Writer private constructor() {
        private var buffer = ByteArray(INITIAL)
        private var size = 0

        private fun room(extra: Int) {
            if (size.toLong() + extra > StoreBounds.PLAINTEXT_CAP_BYTES) {
                throw EncodeFailure(EncodeRefusal.STORAGE_CAP, "document")
            }
            if (size + extra > buffer.size) {
                var next = buffer.size
                while (next <
                    size + extra
                ) {
                    next = minOf(next.toLong() * 2, Int.MAX_VALUE.toLong()).toInt()
                }
                buffer = buffer.copyOf(minOf(next, StoreBounds.PLAINTEXT_CAP_BYTES))
            }
        }

        fun u8(value: Int) {
            room(1)
            buffer[size++] = value.toByte()
        }

        fun bool(value: Boolean) = u8(if (value) 1 else 0)

        fun u16(value: Int) {
            room(2)
            buffer[size++] = (value ushr 8).toByte()
            buffer[size++] = value.toByte()
        }

        fun i32(value: Int) {
            room(4)
            for (shift in 24 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
        }

        fun i64(value: Long) {
            room(8)
            for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
        }

        fun count(value: Int, limit: Int, path: String) {
            if (value > limit) throw EncodeFailure(EncodeRefusal.TOO_MANY, path)
            u16(value)
        }

        fun string(value: String, max: Int, path: String) {
            val length = RelayJson.utf8Length(value, max)
            if (length == RelayJson.LONE_SURROGATE) {
                throw EncodeFailure(EncodeRefusal.INVALID_VALUE, path)
            }
            if (length > max) throw EncodeFailure(EncodeRefusal.FIELD_TOO_LONG, path)
            val bytes = value.toByteArray(Charsets.UTF_8)
            i32(bytes.size)
            room(bytes.size)
            System.arraycopy(bytes, 0, buffer, size, bytes.size)
            size += bytes.size
        }

        fun nullableString(value: String?, max: Int, path: String) {
            bool(value != null)
            if (value != null) string(value, max, path)
        }

        fun nullableLong(value: Long?) {
            bool(value != null)
            if (value != null) i64(value)
        }

        fun result(): ByteArray = buffer.copyOf(size)

        companion object {
            private const val INITIAL = 4096

            fun run(document: RelayDocument): ByteArray {
                val w = Writer()
                val snapshot = document.snapshot
                if (snapshot.version != RelayStateSnapshot.VERSION &&
                    snapshot.version != RelayStateSnapshot.LEGACY_VERSION
                ) {
                    throw EncodeFailure(EncodeRefusal.INVALID_VALUE, "snapshot.version")
                }
                w.i32(MAGIC)
                w.u16(FORMAT_VERSION)
                w.u16(snapshot.version)
                w.section(document.section)
                w.ledger(snapshot.ledger)
                w.count(snapshot.histories.size, StoreBounds.HISTORIES, "histories")
                snapshot.histories.forEachIndexed { index, item ->
                    w.history(item, "histories[$index]")
                }
                w.count(snapshot.deliveries.size, StoreBounds.DELIVERIES, "deliveries")
                snapshot.deliveries.forEachIndexed { index, item ->
                    w.delivery(item, "deliveries[$index]")
                }
                if (snapshot.version == RelayStateSnapshot.VERSION) {
                    w.captures(snapshot.captures)
                } else if (snapshot.captures.records.isNotEmpty()) {
                    throw EncodeFailure(EncodeRefusal.INVALID_VALUE, "captures")
                }
                w.features(document.section)
                w.i32(TRAILER)
                return w.result()
            }
        }

        private fun scope(item: ScopeRecordSnapshot, path: String) {
            string(item.endpointKey, StoreBounds.SCOPE_KEY_BYTES, "$path.endpointKey")
            string(item.profileId, StoreBounds.UUID_BYTES, "$path.profileId")
            string(item.enrollmentKey, StoreBounds.SCOPE_KEY_BYTES, "$path.enrollmentKey")
            i64(item.reviewEpoch)
        }

        private fun section(section: ControllerSection) {
            count(section.enrollments.size, StoreBounds.ENROLLMENTS, "section.enrollments")
            section.enrollments.forEachIndexed { index, item ->
                val path = "section.enrollments[$index]"
                string(item.endpointKey, StoreBounds.SCOPE_KEY_BYTES, "$path.endpointKey")
                string(item.endpointUrl, StoreBounds.URL_BYTES, "$path.endpointUrl")
                string(item.enrollmentKey, StoreBounds.SCOPE_KEY_BYTES, "$path.enrollmentKey")
                bool(item.enabled)
                nullableString(item.pinnedProfileId?.value, StoreBounds.UUID_BYTES, "$path.pinned")
                i64(item.reviewEpoch)
                string(item.standing.name, StoreBounds.ENUM_BYTES, "$path.standing")
                nullableString(
                    item.observedProfileId?.value,
                    StoreBounds.UUID_BYTES,
                    "$path.observed"
                )
                nullableString(
                    item.selectedConversationId?.value,
                    StoreBounds.UUID_BYTES,
                    "$path.selected"
                )
                val create = item.createIntent
                bool(create != null)
                if (create != null) {
                    string(create.requestId.value, StoreBounds.UUID_BYTES, "$path.create.request")
                    nullableString(create.title, StoreBounds.TITLE_BYTES, "$path.create.title")
                    i64(create.preparedAtMs)
                }
            }
        }

        private fun namespace(value: HistoryNamespace, path: String) {
            string(value.endpointKey, StoreBounds.SCOPE_KEY_BYTES, "$path.endpoint")
            string(value.endpointUrl, StoreBounds.URL_BYTES, "$path.url")
            string(value.enrollmentLabel, StoreBounds.SCOPE_KEY_BYTES, "$path.enrollment")
            string(value.profileId, StoreBounds.UUID_BYTES, "$path.profile")
            i64(value.reviewEpoch)
        }

        private fun features(section: ControllerSection) {
            count(section.intents.size, 16, "metadata.intents")
            section.intents.forEachIndexed { index, item ->
                val path = "metadata.intents[$index]"
                namespace(item.namespace, path)
                string(item.conversationId.value, StoreBounds.UUID_BYTES, path)
                string(item.requestId.value, StoreBounds.UUID_BYTES, path)
                i64(item.expectedRevision)
                nullableString(item.title, 4096, path)
                bool(item.bookmarked != null)
                item.bookmarked?.let(::bool)
                i64(item.preparedAtMs)
                string(item.status.name, StoreBounds.ENUM_BYTES, path)
                bool(item.coreCommitted != null)
                item.coreCommitted?.let(::bool)
            }
            count(section.evictions.size, 64, "history.evictions")
            section.evictions.forEachIndexed { index, item ->
                val path = "history.evictions[$index]"
                namespace(item.namespace, path)
                nullableString(item.conversationId, StoreBounds.UUID_BYTES, path)
                string(item.kind.name, StoreBounds.ENUM_BYTES, path)
            }
        }

        private fun ledger(ledger: TurnLedgerSnapshot) {
            count(ledger.turns.size, StoreBounds.TURNS, "ledger.turns")
            ledger.turns.forEachIndexed { index, turn -> turn(turn, "ledger.turns[$index]") }
            count(ledger.scopes.size, StoreBounds.SCOPE_DECISIONS, "ledger.scopes")
            ledger.scopes.forEachIndexed { index, item ->
                val path = "ledger.scopes[$index]"
                scope(item.scope, "$path.scope")
                string(item.status, StoreBounds.ENUM_BYTES, "$path.status")
                bool(item.contentEvictionOwed)
            }
        }

        private fun turn(turn: TurnSnapshot, path: String) {
            scope(turn.scope, "$path.scope")
            string(turn.conversationId, StoreBounds.UUID_BYTES, "$path.conversationId")
            string(turn.requestId, StoreBounds.UUID_BYTES, "$path.requestId")
            i64(turn.expiresAtMs)
            i64(turn.preparedAtMs)
            string(turn.fingerprint, StoreBounds.FINGERPRINT_BYTES, "$path.fingerprint")
            nullableString(turn.text, StoreBounds.TURN_TEXT_BYTES, "$path.text")
            bool(turn.sent)
            val facts = turn.facts
            bool(facts != null)
            if (facts != null) {
                string(facts.admissionId, StoreBounds.UUID_BYTES, "$path.facts.admissionId")
                i64(facts.authorityEpoch)
                string(facts.status, StoreBounds.ENUM_BYTES, "$path.facts.status")
                nullableLong(facts.executionGeneration)
                nullableString(facts.outcome, StoreBounds.ENUM_BYTES, "$path.facts.outcome")
                nullableLong(facts.priorStartedGeneration)
            }
            nullableString(turn.closure, StoreBounds.ENUM_BYTES, "$path.closure")
            bool(turn.held)
            nullableString(turn.lastErrorCode, StoreBounds.ENUM_BYTES, "$path.lastErrorCode")
            i32(turn.anomalies)
        }

        private fun history(item: HistorySnapshot, path: String) {
            scope(item.scope, "$path.scope")
            string(item.conversationId, StoreBounds.UUID_BYTES, "$path.conversationId")
            string(item.source, StoreBounds.ENUM_BYTES, "$path.source")
            i64(item.generation)
            string(item.start, StoreBounds.ENUM_BYTES, "$path.start")
            nullableString(item.after, StoreBounds.ENUM_BYTES, "$path.after")
            string(item.status, StoreBounds.ENUM_BYTES, "$path.status")
            nullableString(item.invalidation, StoreBounds.ENUM_BYTES, "$path.invalidation")
            i64(item.committedOrdinal)
            bool(item.hasResume)
            nullableString(item.resumeCursor, StoreBounds.CURSOR_BYTES, "$path.resumeCursor")
            bool(item.resumeAtEnd)
            count(item.known.size, StoreBounds.KNOWN_MESSAGES, "$path.known")
            item.known.forEachIndexed { index, known ->
                string(known.messageId, StoreBounds.UUID_BYTES, "$path.known[$index].id")
                string(known.role, StoreBounds.ENUM_BYTES, "$path.known[$index].role")
                i64(known.createdAtMs)
                i32(known.segmentCount)
            }
            count(item.refused.size, StoreBounds.REFUSED_MESSAGES, "$path.refused")
            item.refused.forEachIndexed { index, refused ->
                string(refused.messageId, StoreBounds.UUID_BYTES, "$path.refused[$index].id")
                string(refused.reason, StoreBounds.ENUM_BYTES, "$path.refused[$index].reason")
                i32(refused.declaredSegments)
            }
        }

        private fun delivery(item: DeliverySnapshot, path: String) {
            scope(item.scope, "$path.scope")
            nullableLong(item.gapBaseline)
            count(item.seen.size, StoreBounds.SEEN_KEYS, "$path.seen")
            item.seen.forEachIndexed { index, seen ->
                val at = "$path.seen[$index]"
                string(seen.kind, StoreBounds.ENUM_BYTES, "$at.kind")
                string(seen.conversation, StoreBounds.OPAQUE_CONVERSATION_BYTES, "$at.conversation")
                string(seen.event, StoreBounds.OPAQUE_EVENT_BYTES, "$at.event")
                i64(seen.committedAtMs)
            }
            count(item.obligations.size, StoreBounds.OBLIGATIONS, "$path.obligations")
            item.obligations.forEachIndexed { index, obligation ->
                val at = "$path.obligations[$index]"
                nullableString(
                    obligation.conversationId,
                    StoreBounds.UUID_BYTES,
                    "$at.conversationId"
                )
                string(obligation.kind, StoreBounds.ENUM_BYTES, "$at.kind")
                string(obligation.cause, StoreBounds.ENUM_BYTES, "$at.cause")
            }
            count(item.pendingAcks.size, StoreBounds.PENDING_ACKS, "$path.pendingAcks")
            item.pendingAcks.forEachIndexed { index, ack ->
                string(ack, StoreBounds.UUID_BYTES, "$path.pendingAcks[$index]")
            }
        }

        private fun captures(section: CaptureLedgerSnapshot) {
            if (section.version != CaptureLedgerSnapshot.VERSION) {
                throw EncodeFailure(EncodeRefusal.INVALID_VALUE, "captures.version")
            }
            i32(section.version)
            count(section.records.size, StoreBounds.CAPTURES, "captures")
            section.records.forEachIndexed { index, record ->
                val path = "captures[$index]"
                scope(record.scope, "$path.scope")
                string(record.conversationId, StoreBounds.UUID_BYTES, "$path.conversationId")
                string(record.captureId, StoreBounds.UUID_BYTES, "$path.captureId")
                i64(record.createdAtMs)
                string(record.state, StoreBounds.ENUM_BYTES, "$path.state")
                nullableString(record.linkedRequest, StoreBounds.UUID_BYTES, "$path.linkedRequest")
                nullableString(record.provider, StoreBounds.ENUM_BYTES, "$path.provider")
                nullableString(record.end, StoreBounds.ENUM_BYTES, "$path.end")
                nullableString(record.failure, StoreBounds.ENUM_BYTES, "$path.failure")
                nullableLong(record.terminalAtMs)
            }
        }
    }

    // -- reader ----------------------------------------------------------------------------------------------

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        private fun corrupt(path: String): Nothing =
            throw DecodeFailure(DecodeRefusal.CORRUPT, path)

        private fun need(count: Int, path: String) {
            if (count < 0 || count > bytes.size - position) corrupt(path)
        }

        fun u8(path: String): Int {
            need(1, path)
            return bytes[position++].toInt() and 0xff
        }

        fun bool(path: String): Boolean = when (u8(path)) {
            0 -> false
            1 -> true
            else -> corrupt(path)
        }

        fun u16(path: String): Int {
            need(2, path)
            val value =
                ((bytes[position].toInt() and 0xff) shl 8) or (bytes[position + 1].toInt() and 0xff)
            position += 2
            return value
        }

        fun i32(path: String): Int {
            need(4, path)
            var value = 0
            repeat(4) { value = (value shl 8) or (bytes[position++].toInt() and 0xff) }
            return value
        }

        fun i64(path: String): Long {
            need(8, path)
            var value = 0L
            repeat(8) { value = (value shl 8) or (bytes[position++].toLong() and 0xff) }
            return value
        }

        fun count(limit: Int, path: String): Int {
            val value = u16(path)
            if (value > limit) throw DecodeFailure(DecodeRefusal.TOO_MANY, path)
            return value
        }

        fun string(max: Int, path: String): String {
            val length = i32(path)
            if (length < 0 || length > max) corrupt(path)
            need(length, path)
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, position, length)).toString()
            } catch (_: CharacterCodingException) {
                corrupt(path)
            }
            position += length
            return decoded
        }

        fun nullableString(max: Int, path: String): String? =
            if (bool(path)) string(max, path) else null

        fun nullableLong(path: String): Long? = if (bool(path)) i64(path) else null

        fun document(): RelayDocument {
            if (bytes.size <
                StoreBounds.HEADER_BYTES + StoreBounds.TRAILER_BYTES
            ) {
                corrupt("document")
            }
            if (bytes.size > StoreBounds.PLAINTEXT_CAP_BYTES) corrupt("document")
            if (i32("magic") !=
                MAGIC
            ) {
                throw DecodeFailure(DecodeRefusal.UNSUPPORTED_VERSION, "magic")
            }
            val format = u16("formatVersion")
            if (format !in 1..FORMAT_VERSION) {
                throw DecodeFailure(DecodeRefusal.UNSUPPORTED_VERSION, "formatVersion")
            }
            val version = u16("snapshotVersion")
            if (version != RelayStateSnapshot.VERSION &&
                version != RelayStateSnapshot.LEGACY_VERSION
            ) {
                throw DecodeFailure(DecodeRefusal.UNSUPPORTED_VERSION, "snapshotVersion")
            }
            val section = section()
            val ledger = ledger()
            val histories =
                List(count(StoreBounds.HISTORIES, "histories")) { history("histories[$it]") }
            val deliveries =
                List(count(StoreBounds.DELIVERIES, "deliveries")) { delivery("deliveries[$it]") }
            val captures = if (version == RelayStateSnapshot.VERSION) {
                captures()
            } else {
                CaptureLedgerSnapshot.EMPTY
            }
            val completeSection = if (format == 2) features(section) else section
            if (i32("trailer") != TRAILER) corrupt("trailer")
            if (position != bytes.size) corrupt("trailing")
            return RelayDocument(
                completeSection,
                RelayStateSnapshot(version, ledger, histories, deliveries, captures)
            )
        }

        private fun scope(path: String) = ScopeRecordSnapshot(
            string(StoreBounds.SCOPE_KEY_BYTES, "$path.endpointKey"),
            string(StoreBounds.UUID_BYTES, "$path.profileId"),
            string(StoreBounds.SCOPE_KEY_BYTES, "$path.enrollmentKey"),
            i64("$path.reviewEpoch")
        )

        private fun section(): ControllerSection {
            val records = List(count(StoreBounds.ENROLLMENTS, "section.enrollments")) { index ->
                val path = "section.enrollments[$index]"
                val endpointKey = string(StoreBounds.SCOPE_KEY_BYTES, "$path.endpointKey")
                val url = string(StoreBounds.URL_BYTES, "$path.endpointUrl")
                val enrollmentKey = string(StoreBounds.SCOPE_KEY_BYTES, "$path.enrollmentKey")
                val enabled = bool("$path.enabled")
                val pinned = nullableString(StoreBounds.UUID_BYTES, "$path.pinned")?.let {
                    ProfileId.of(it) ?: corrupt("$path.pinned")
                }
                val epoch = i64("$path.reviewEpoch")
                val standing = string(StoreBounds.ENUM_BYTES, "$path.standing").let { name ->
                    Standing.entries.firstOrNull { it.name == name } ?: corrupt("$path.standing")
                }
                val observed = nullableString(StoreBounds.UUID_BYTES, "$path.observed")?.let {
                    ProfileId.of(it) ?: corrupt("$path.observed")
                }
                val selected = nullableString(StoreBounds.UUID_BYTES, "$path.selected")?.let {
                    ConversationId.of(it) ?: corrupt("$path.selected")
                }
                val create = if (bool("$path.create")) {
                    val request =
                        RequestId.of(string(StoreBounds.UUID_BYTES, "$path.create.request"))
                            ?: corrupt("$path.create.request")
                    val title = nullableString(StoreBounds.TITLE_BYTES, "$path.create.title")
                    val prepared = i64("$path.create.preparedAt")
                    try {
                        CreateIntent(request, title, prepared)
                    } catch (_: IllegalArgumentException) {
                        corrupt("$path.create")
                    }
                } else {
                    null
                }
                try {
                    EnrollmentRecord(
                        endpointKey,
                        url,
                        enrollmentKey,
                        enabled,
                        pinned,
                        epoch,
                        standing,
                        observed,
                        selected,
                        create
                    )
                } catch (_: IllegalArgumentException) {
                    corrupt(path)
                }
            }
            return try {
                ControllerSection(records)
            } catch (_: IllegalArgumentException) {
                corrupt("section")
            }
        }

        private fun namespace(path: String): HistoryNamespace = try {
            HistoryNamespace(
                string(StoreBounds.SCOPE_KEY_BYTES, path),
                string(StoreBounds.URL_BYTES, path),
                string(StoreBounds.SCOPE_KEY_BYTES, path),
                string(StoreBounds.UUID_BYTES, path),
                i64(path)
            )
        } catch (_: IllegalArgumentException) {
            corrupt(path)
        }

        private fun features(section: ControllerSection): ControllerSection {
            val intents = List(count(16, "metadata.intents")) { index ->
                val path = "metadata.intents[$index]"
                val namespace = namespace(path)
                val conversation =
                    ConversationId.of(string(StoreBounds.UUID_BYTES, path)) ?: corrupt(path)
                val request = RequestId.of(string(StoreBounds.UUID_BYTES, path)) ?: corrupt(path)
                val revision = i64(path)
                val title = nullableString(4096, path)
                val bookmark = if (bool(path)) bool(path) else null
                val prepared = i64(path)
                val status = string(StoreBounds.ENUM_BYTES, path).let { name ->
                    MetadataStatus.entries.firstOrNull { it.name == name } ?: corrupt(path)
                }
                val core = if (bool(path)) bool(path) else null
                try {
                    MetadataIntent(
                        namespace, conversation, request, revision, title, bookmark,
                        prepared, status, core
                    )
                } catch (
                    _: IllegalArgumentException
                ) {
                    corrupt(path)
                }
            }
            val evictions = List(count(64, "history.evictions")) { index ->
                val path = "history.evictions[$index]"
                val namespace = namespace(path)
                val conversation = nullableString(StoreBounds.UUID_BYTES, path)
                if (conversation != null && ConversationId.of(conversation) == null) corrupt(path)
                val kind = string(StoreBounds.ENUM_BYTES, path).let { name ->
                    EvictionKind.entries.firstOrNull { it.name == name } ?: corrupt(path)
                }
                HistoryEviction(namespace, conversation, kind)
            }
            return try {
                ControllerSection(section.enrollments, intents, evictions)
            } catch (
                _: IllegalArgumentException
            ) {
                corrupt("features")
            }
        }

        private fun ledger(): TurnLedgerSnapshot {
            val turns = List(count(StoreBounds.TURNS, "ledger.turns")) { turn("ledger.turns[$it]") }
            val scopes = List(count(StoreBounds.SCOPE_DECISIONS, "ledger.scopes")) { index ->
                val path = "ledger.scopes[$index]"
                ScopeStateSnapshot(
                    scope("$path.scope"),
                    string(StoreBounds.ENUM_BYTES, "$path.status"),
                    bool("$path.contentEvictionOwed")
                )
            }
            return TurnLedgerSnapshot(turns, scopes)
        }

        private fun turn(path: String): TurnSnapshot {
            val scope = scope("$path.scope")
            val conversation = string(StoreBounds.UUID_BYTES, "$path.conversationId")
            val request = string(StoreBounds.UUID_BYTES, "$path.requestId")
            val expires = i64("$path.expiresAtMs")
            val prepared = i64("$path.preparedAtMs")
            val fingerprint = string(StoreBounds.FINGERPRINT_BYTES, "$path.fingerprint")
            val text = nullableString(StoreBounds.TURN_TEXT_BYTES, "$path.text")
            val sent = bool("$path.sent")
            val facts = if (bool("$path.facts")) {
                AdmissionFactsSnapshot(
                    string(StoreBounds.UUID_BYTES, "$path.facts.admissionId"),
                    i64("$path.facts.authorityEpoch"),
                    string(StoreBounds.ENUM_BYTES, "$path.facts.status"),
                    nullableLong("$path.facts.executionGeneration"),
                    nullableString(StoreBounds.ENUM_BYTES, "$path.facts.outcome"),
                    nullableLong("$path.facts.priorStartedGeneration")
                )
            } else {
                null
            }
            return TurnSnapshot(
                scope,
                conversation,
                request,
                expires,
                prepared,
                fingerprint,
                text,
                sent,
                facts,
                nullableString(StoreBounds.ENUM_BYTES, "$path.closure"),
                bool("$path.held"),
                nullableString(StoreBounds.ENUM_BYTES, "$path.lastErrorCode"),
                i32("$path.anomalies")
            )
        }

        private fun history(path: String): HistorySnapshot {
            val scope = scope("$path.scope")
            val conversation = string(StoreBounds.UUID_BYTES, "$path.conversationId")
            val source = string(StoreBounds.ENUM_BYTES, "$path.source")
            val generation = i64("$path.generation")
            val start = string(StoreBounds.ENUM_BYTES, "$path.start")
            val after = nullableString(StoreBounds.ENUM_BYTES, "$path.after")
            val status = string(StoreBounds.ENUM_BYTES, "$path.status")
            val invalidation = nullableString(StoreBounds.ENUM_BYTES, "$path.invalidation")
            val ordinal = i64("$path.committedOrdinal")
            val hasResume = bool("$path.hasResume")
            val cursor = nullableString(StoreBounds.CURSOR_BYTES, "$path.resumeCursor")
            val atEnd = bool("$path.resumeAtEnd")
            val known = List(count(StoreBounds.KNOWN_MESSAGES, "$path.known")) { index ->
                val at = "$path.known[$index]"
                KnownMessageSnapshot(
                    string(StoreBounds.UUID_BYTES, "$at.id"),
                    string(StoreBounds.ENUM_BYTES, "$at.role"),
                    i64("$at.createdAtMs"),
                    i32("$at.segmentCount")
                )
            }
            val refused = List(count(StoreBounds.REFUSED_MESSAGES, "$path.refused")) { index ->
                val at = "$path.refused[$index]"
                RefusedMessageSnapshot(
                    string(StoreBounds.UUID_BYTES, "$at.id"),
                    string(StoreBounds.ENUM_BYTES, "$at.reason"),
                    i32("$at.declaredSegments")
                )
            }
            return HistorySnapshot(
                scope,
                conversation,
                source,
                generation,
                start,
                after,
                status,
                invalidation,
                ordinal,
                hasResume,
                cursor,
                atEnd,
                known,
                refused
            )
        }

        private fun delivery(path: String): DeliverySnapshot {
            val scope = scope("$path.scope")
            val baseline = nullableLong("$path.gapBaseline")
            val seen = List(count(StoreBounds.SEEN_KEYS, "$path.seen")) { index ->
                val at = "$path.seen[$index]"
                SeenKeySnapshot(
                    string(StoreBounds.ENUM_BYTES, "$at.kind"),
                    string(StoreBounds.OPAQUE_CONVERSATION_BYTES, "$at.conversation"),
                    string(StoreBounds.OPAQUE_EVENT_BYTES, "$at.event"),
                    i64("$at.committedAtMs")
                )
            }
            val obligations = List(count(StoreBounds.OBLIGATIONS, "$path.obligations")) { index ->
                val at = "$path.obligations[$index]"
                ObligationSnapshot(
                    nullableString(StoreBounds.UUID_BYTES, "$at.conversationId"),
                    string(StoreBounds.ENUM_BYTES, "$at.kind"),
                    string(StoreBounds.ENUM_BYTES, "$at.cause")
                )
            }
            val acks = List(count(StoreBounds.PENDING_ACKS, "$path.pendingAcks")) {
                string(StoreBounds.UUID_BYTES, "$path.pendingAcks[$it]")
            }
            return DeliverySnapshot(scope, baseline, seen, obligations, acks)
        }

        private fun captures(): CaptureLedgerSnapshot {
            val version = i32("captures.version")
            if (version != CaptureLedgerSnapshot.VERSION) {
                throw DecodeFailure(DecodeRefusal.UNSUPPORTED_VERSION, "captures.version")
            }
            val records = List(count(StoreBounds.CAPTURES, "captures")) { index ->
                val path = "captures[$index]"
                CaptureRecordSnapshot(
                    scope("$path.scope"),
                    string(StoreBounds.UUID_BYTES, "$path.conversationId"),
                    string(StoreBounds.UUID_BYTES, "$path.captureId"),
                    i64("$path.createdAtMs"),
                    string(StoreBounds.ENUM_BYTES, "$path.state"),
                    nullableString(StoreBounds.UUID_BYTES, "$path.linkedRequest"),
                    nullableString(StoreBounds.ENUM_BYTES, "$path.provider"),
                    nullableString(StoreBounds.ENUM_BYTES, "$path.end"),
                    nullableString(StoreBounds.ENUM_BYTES, "$path.failure"),
                    nullableLong("$path.terminalAtMs")
                )
            }
            return CaptureLedgerSnapshot(version, records)
        }
    }
}
