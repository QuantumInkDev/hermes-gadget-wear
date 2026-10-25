package dev.quantumink.hermesgadget.wear

import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.OutboxReference
import dev.quantumink.hermesgadget.protocol.relay.RunId
import dev.quantumink.hermesgadget.protocol.relay.Task
import dev.quantumink.hermesgadget.protocol.relay.TaskId
import dev.quantumink.hermesgadget.protocol.relay.TaskOutcome
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
import dev.quantumink.hermesgadget.protocol.relay.session.InboxCodec
import dev.quantumink.hermesgadget.protocol.relay.session.InboxFile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProfile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRepository
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.InboxWrite
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRelayModelTest {
    private val url = "wss://hermes.example.test/gadget"
    private val device = DeviceIdentity(ByteArray(32) { 7 })
    private val owner = HistoryNamespace(
        EnrollmentRecord.endpointKey(url),
        url,
        "watch:" + device.deviceId,
        uuid(1),
        0
    )

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private class Disk : InboxFile {
        var state = InboxSnapshot()
        override fun load() = state
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }

    private fun repository(alerts: Boolean = false) = InboxRepository(Disk()).also {
        it.configure(
            InboxProfile(
                owner,
                url,
                "Watch Hermes",
                "Saved on watch",
                completionAlerts = alerts,
                inputAlerts = alerts
            )
        )
    }

    private fun task(state: TaskState, at: Long) = Task(
        TaskId.of(uuid(10))!!,
        ConversationId.of(uuid(11))!!,
        AdmissionId.of(uuid(12))!!,
        RunId.of(uuid(13)),
        state,
        if (state == TaskState.FINISHED) TaskOutcome.COMPLETED else null,
        at
    )

    private fun change(n: Int, state: TaskState) = OutboxItem.Change(
        DeliveryId.of(uuid(100 + n))!!,
        ConversationId.of(uuid(11))!!,
        EventId.of(uuid(200 + n))!!,
        1000,
        Long.MAX_VALUE / 2,
        OutboxPayload.TaskChange.Relay(task(state, 1000L + n), OutboxReference.CURRENT)
    )

    private fun final(n: Int, text: String) = OutboxItem.Final(
        DeliveryId.of(uuid(300 + n))!!,
        OpaqueConversationRef.of("opaque-conversation")!!,
        OpaqueEventRef.of("opaque-event-$n")!!,
        1000,
        Long.MAX_VALUE / 2,
        OutboxPayload.FinalText(text, null)
    )

    @Test fun ownEnrollmentOnlyForTlsAndNeverThePhoneLabel() {
        val who = WatchRelayModel.identity(Endpoint.parse(url), device)!!
        assertEquals(url, who.endpointUrl)
        assertEquals("watch:" + device.deviceId, who.enrollmentLabel)
        assertNull(
            WatchRelayModel.identity(
                Endpoint.parse("ws://192.168.1.20:8765/gadget", true, true),
                device
            )
        )
        val phone = EnrollmentIdentity(url, "phone:" + device.deviceId)
        assertEquals(
            "mine",
            WatchRelayModel.owner(
                owner,
                listOf(
                    "phone" to phone,
                    "mine" to who,
                    "ws" to null
                )
            )
        )
        assertNull(WatchRelayModel.owner(owner, listOf("phone" to phone)))
    }

    @Test fun linesKeepOneNewestRowPerTaskAndFinalsNewestFirst() {
        val repo = repository()
        repo.project(owner, relay = listOf(task(TaskState.WORKING, 900)), at = 1_000)
        repo.accept(owner, listOf(change(1, TaskState.NEEDS_INPUT)), 2_000)
        repo.accept(owner, listOf(final(1, "a private reply")), 3_000)
        val lines = WatchRelayModel.lines(repo.snapshot())
        assertEquals(2, lines.size)
        assertNull(lines[0].state)
        assertEquals(TaskState.NEEDS_INPUT, lines[1].state)
        assertTrue(lines[1].unread)
        assertTrue(lines[1].canOpen)
        assertFalse(lines[0].canOpen)
        assertEquals("Watch Hermes", lines[1].profile)
        assertTrue(WatchRelayModel.lines(null).isEmpty())
    }

    @Test fun alertNamesProfileAndStateButNeverReplyText() {
        val repo = repository(alerts = true)
        val reply = repo.accept(owner, listOf(final(2, "secret reply words")), 2_000).single()
        val finished = repo.accept(owner, listOf(change(3, TaskState.FINISHED)), 3_000).single()
        assertTrue(reply.alert && finished.alert)
        for ((copy, state) in listOf(reply to "Reply ready", finished to "Finished")) {
            val text = WatchRelayModel.alert(copy, "Watch Hermes")
            assertEquals("Watch Hermes", text.title)
            assertTrue(text.text.startsWith(state))
            listOf(text.title, text.text, text.publicTitle, text.publicText).forEach {
                assertFalse(it.contains("secret"))
            }
            assertFalse(
                text.publicTitle.contains("Watch Hermes") ||
                    text.publicText.contains("Watch Hermes")
            )
        }
        assertEquals("Hermes", WatchRelayModel.alert(reply, null).title)
    }

    @Test fun onlyUnsettledTurnsGetTheirOwnLine() {
        assertFalse(WatchRelayModel.settled(TurnStatus.SENDING))
        assertFalse(WatchRelayModel.settled(TurnStatus.RUNNING))
        assertFalse(WatchRelayModel.settled(TurnStatus.UNCERTAIN))
        assertTrue(WatchRelayModel.settled(TurnStatus.FINISHED_COMPLETED))
        assertTrue(WatchRelayModel.settled(TurnStatus.REFUSED))
        assertEquals("Needs input", WatchRelayModel.stateText(TaskState.NEEDS_INPUT))
        assertEquals("Unknown", WatchRelayModel.stateText(TaskState.UNKNOWN))
    }
}
