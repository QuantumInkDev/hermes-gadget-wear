package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryAssembly
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryScope
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger

/** Why a runtime could not be turned into a storable document. Nothing was written or sent. */
sealed interface DocumentBuild {
    class Built(val document: RelayDocument) : DocumentBuild

    /** A component is above its policy limit (the aggregate builder's own typed refusal). */
    class TooMany(val path: String, val limit: Int) : DocumentBuild

    /** The state layer itself would refuse to restore this aggregate: it is never handed to the store. */
    class Unrestorable(val reason: String, val path: String) : DocumentBuild
}

/**
 * The complete in-memory state of the typed controller, as ONE immutable value. Every transition derives the next
 * value, builds the full aggregate from it (always with the captures, explicitly), proves that the state layer can
 * restore it, and only then stores it. Nothing transient (binding, offset, correlation, call, staged content, intent)
 * is part of it.
 */
internal class RelayRuntime(
    val section: ControllerSection,
    val ledger: TurnLedger,
    val captures: VoiceCaptureLedger,
    val histories: List<HistoryAssembly>,
    val deliveries: List<EnrollmentDelivery>,
    val authority: RelayAuthority
) {
    fun copy(
        section: ControllerSection = this.section,
        ledger: TurnLedger = this.ledger,
        captures: VoiceCaptureLedger = this.captures,
        histories: List<HistoryAssembly> = this.histories,
        deliveries: List<EnrollmentDelivery> = this.deliveries,
        authority: RelayAuthority = this.authority
    ) = RelayRuntime(section, ledger, captures, histories, deliveries, authority)

    fun delivery(scope: EnrollmentScope): EnrollmentDelivery? =
        deliveries.firstOrNull { it.enrollment == scope }

    fun withDelivery(delivery: EnrollmentDelivery): RelayRuntime = copy(
        deliveries = deliveries.filter { it.enrollment != delivery.enrollment } + delivery
    )

    fun withoutDeliveries(keep: (EnrollmentScope) -> Boolean): RelayRuntime =
        copy(deliveries = deliveries.filter { keep(it.enrollment) })

    fun history(scope: HistoryScope): HistoryAssembly? = histories.firstOrNull { it.scope == scope }

    /** Newest last; beyond [limit] the OLDEST resume point goes (it only means "read that listing from its top"). */
    fun withHistory(history: HistoryAssembly, limit: Int = MAX_HISTORIES): RelayRuntime {
        val rest = histories.filter { it.scope != history.scope } + history
        return copy(histories = rest.takeLast(limit))
    }

    /**
     * Builds the complete aggregate for [now]. The captures are passed EXPLICITLY on every write: the builder's default
     * (an empty section) is never relied on. A snapshot the state layer would refuse to restore is not returned.
     */
    fun build(now: ServerInstant, listSection: ControllerSection = section): DocumentBuild {
        val built = RelayStateSnapshot.of(ledger, histories, deliveries, captures)
        val snapshot = when (built) {
            is SnapshotBuild.Built -> built.snapshot
            is SnapshotBuild.TooMany -> return DocumentBuild.TooMany(built.path, built.limit)
        }
        // The restore must accept exactly what is about to be stored (fresh authority, the same server instant).
        when (val check = RelayStateRestore.restore(snapshot, now, RelayAuthority.fresh())) {
            is RestoreResult.Refused ->
                return DocumentBuild.Unrestorable(check.reason.name, check.path)
            is RestoreResult.Restored -> Unit
        }
        return DocumentBuild.Built(RelayDocument(listSection, snapshot))
    }

    companion object {
        const val MAX_HISTORIES = 8

        fun empty(section: ControllerSection): RelayRuntime = RelayRuntime(
            section,
            TurnLedger.empty(),
            VoiceCaptureLedger.empty(),
            emptyList(),
            emptyList(),
            RelayAuthority.fresh()
        )
    }
}
