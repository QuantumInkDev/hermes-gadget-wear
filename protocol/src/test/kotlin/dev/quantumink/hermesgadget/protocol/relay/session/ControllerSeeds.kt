package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryAssembly
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryScope
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerHarness
import dev.quantumink.hermesgadget.protocol.relay.state.ConversationScope
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.TurnKey
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceHarness

/** Stored states a previous process could have left behind, built from the real transitions of the state layer. */
internal object Seeds {
    fun scope(
        identity: EnrollmentIdentity,
        profile: ProfileId = Dto.profile,
        epoch: Long = 0
    ) = EnrollmentScope(RelayScope(identity.endpointKey, profile), identity.enrollmentLabel, epoch)

    fun record(
        identity: EnrollmentIdentity,
        standing: Standing = Standing.ACTIVE,
        epoch: Long = 0,
        selected: Boolean = false,
        pinned: ProfileId? = Dto.profile
    ): EnrollmentRecord = EnrollmentRecord.fresh(identity.endpointUrl, identity.enrollmentLabel).copy(
        enabled = true,
        pinnedProfileId = pinned,
        reviewEpoch = epoch,
        standing = standing,
        selectedConversationId = if (selected) Dto.conversation else null
    )

    class Stored(val document: RelayDocument, val key: TurnKey?)

    fun empty(section: ControllerSection): RelayDocument = RelayDocument(
        section,
        (RelayStateSnapshot.of(TurnLedger.empty(), emptyList(), emptyList(), VoiceCaptureLedger.empty()) as SnapshotBuild.Built).snapshot
    )

    /** A document with one turn that WAS written (sent = true) and never answered: process loss during a submit. */
    fun sentTurn(
        identity: EnrollmentIdentity,
        text: String = "an unanswered question",
        record: EnrollmentRecord = record(identity, selected = true)
    ): Stored {
        val h = LedgerHarness(scope(identity, record.pinnedProfileId!!, record.reviewEpoch), Dto.binding(1))
        val key = h.prepare(text)
        h.token1(key)
        return Stored(
            RelayDocument(
                ControllerSection(listOf(record)),
                (RelayStateSnapshot.of(h.ledger, emptyList(), emptyList(), VoiceCaptureLedger.empty()) as SnapshotBuild.Built).snapshot
            ),
            key
        )
    }

    /** A document that also holds a real capture record, a history resume point and a foreign enrollment's delivery. */
    fun withCaptureHistoryAndDelivery(identity: EnrollmentIdentity): RelayDocument {
        val own = scope(identity)
        val voice = VoiceHarness(own)
        val token = voice.h.attach()
        voice.begin(token)
        val history = HistoryAssembly.begin(HistoryScope(ConversationScope(own, Dto.otherConversation)))
        val foreign = EnrollmentScope(RelayScope("ep1:" + "cd".repeat(32), Dto.otherProfile), "phone:hg-ffffffffffffffff", 0)
        val snapshot = (
            RelayStateSnapshot.of(
                voice.h.ledger,
                listOf(history),
                listOf(EnrollmentDelivery.begin(foreign)),
                voice.captures
            ) as SnapshotBuild.Built
            ).snapshot
        check(snapshot.captures.records.size == 1)
        return RelayDocument(ControllerSection(listOf(record(identity))), snapshot)
    }

    fun captureRecords(snapshot: RelayStateSnapshot): List<String> =
        snapshot.captures.records.map { listOf(it.scope.toString(), it.conversationId, it.captureId, it.state).joinToString("|") }

    val none: CaptureLedgerSnapshot = CaptureLedgerSnapshot.EMPTY
}
