package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ownedBoundedForSnapshot

/**
 * One saved capture record in plain values (design §13: the capture section, version 1, inside the same atomic encrypted record as the turn ledger).
 *
 * What it never holds, by construction: audio, a stream, a socket generation, a binding, a waveform level, a playback flag, a transcript or a draft.
 * [provider] is the last billing evidence; [end] and [failure] are the truthful history of an ended capture.
 */
data class CaptureRecordSnapshot(
    val scope: ScopeRecordSnapshot,
    val conversationId: String,
    val captureId: String,
    val createdAtMs: Long,
    val state: String,
    val linkedRequest: String?,
    val provider: String?,
    val end: String?,
    val failure: String?,
    val terminalAtMs: Long?
) {
    override fun toString(): String = "CaptureRecordSnapshot($state, redacted)"
}

/**
 * The capture section of the aggregate snapshot. A caller's list is read element by element and only up to one past the 64-record bound, so an arbitrarily
 * large collection costs a bounded amount of work and a restore refuses (never shortens) an over-limit section.
 */
class CaptureLedgerSnapshot(val version: Int, records: List<CaptureRecordSnapshot>) {
    val records: List<CaptureRecordSnapshot> = ownedBoundedForSnapshot(
        records,
        VoiceLimits.CAPTURES_TOTAL + 1
    )

    override fun toString(): String =
        "CaptureLedgerSnapshot(v$version, records=${this.records.size})"

    companion object {
        const val VERSION = VoiceLimits.SNAPSHOT_SECTION_VERSION

        val EMPTY = CaptureLedgerSnapshot(VERSION, emptyList())
    }
}
