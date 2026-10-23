package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayNoticeTruthTest {
    private fun selected() = ControllerFixture().also {
        it.enable()
        it.connect()
        it.listAnd(Dto.summary())
        it.controller.select(Dto.conversation.value)
        it.run()
        it.attachTo(Dto.binding(1))
    }

    private fun notices(uncertain: Boolean) {
        val before = selected()
        before.store.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, uncertain)
        before.controller.send("refused before dispatch")
        before.run()
        assertTrue(before.frames("relay.turn.submit").isEmpty())
        assertFalse(before.ui.unsaved)
        val after = selected()
        after.controller.send("already sent")
        after.run()
        val sent = after.frames("relay.turn.submit").single()
        after.store.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, uncertain)
        val id = RequestId.of(sent.params["request_id"]!!.jsonPrimitive.content)!!
        after.transport.reply(sent, Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        after.run()
        assertTrue(after.ui.unsaved)
        assertEquals(1, after.frames("relay.turn.submit").size)
        assertNotEquals(
            "a server observation must not use the pre-send refusal notice",
            before.ui.notice,
            after.ui.notice
        )
    }

    @Test fun postSendWriteFailureDiffersFromPreSendRefusal() = notices(false)

    @Test fun postSendUncertainCommitDiffersFromPreSendRefusal() = notices(true)
}
