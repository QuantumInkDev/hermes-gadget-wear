package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReplyRevisionTest {
    private fun event(type: String, turn: String? = null, text: String? = null) = Message.create(
        type,
        buildJsonObject {
            turn?.let { put("turn", it) }
            text?.let { put("text", it) }
        }
    )

    @Test fun identicalTextAcrossTurnsAndPublicationsGetsNewLocalRevision() {
        val c = Conversation().apply { setPaired(true) }
        c.receive(event("turn.start", "first"), 1)
        c.receive(event("reply", "first", "- [ ] Same text"), 2)
        val first = c.state.replyRevision
        c.receive(event("turn.end", "first"), 3)
        assertSame(first, c.state.replyRevision)
        c.receive(event("turn.start", "second"), 4)
        assertNotSame(first, c.state.replyRevision)
        assertTrue(c.state.replyChanging)
        c.receive(event("reply", "second", "- [ ] Same text"), 5)
        val second = c.state.replyRevision
        assertNotSame(first, second)
        c.receive(event("reply", "second", "- [ ] Same text"), 6)
        assertNotSame(second, c.state.replyRevision)
    }

    @Test fun deltasLockChecksUntilFinalOrTurnEndAndStaleEventsDoNotReplaceRevision() {
        val c = Conversation().apply { setPaired(true) }
        c.receive(event("turn.start", "active"), 1)
        c.receive(event("reply.delta", "active", "- [ ] Streaming"), 2)
        assertTrue(c.state.replyChanging)
        val revision = c.state.replyRevision
        c.receive(event("reply", "old", "old"), 3)
        c.receive(event("turn.start", "active"), 4)
        assertSame(revision, c.state.replyRevision)
        c.receive(event("turn.end", "active"), 5)
        assertFalse(c.state.replyChanging)
        c.receive(event("reply", "active", "late"), 6)
        assertSame(revision, c.state.replyRevision)
        assertEquals("- [ ] Streaming", c.state.reply)
    }

    @Test fun reconnectAndIndependentProfilesNeverReusePresentationRevision() {
        val c = Conversation(previousReply = "- [ ] Saved").apply { setPaired(true) }
        val original = c.state.replyRevision
        c.disconnected()
        assertNotSame(original, c.state.replyRevision)
        val disconnected = c.state.replyRevision
        c.setPaired(true)
        c.receive(event("reply", text = "- [ ] Saved"), 1)
        assertNotSame(disconnected, c.state.replyRevision)
        assertNotSame(
            c.state.replyRevision,
            Conversation(previousReply = c.state.reply).state.replyRevision
        )
        assertNotSame(
            c.state.replyRevision,
            Conversation(previousReply = c.state.reply).state.replyRevision
        )
    }

    @Test fun localRequestsAndCancellationInvalidateOldChecksWithoutChangingWire() {
        val c = Conversation(previousReply = "- [ ] Saved").apply { setPaired(true) }
        val old = c.state.replyRevision
        val sent = c.sendText("hello", 0).filterIsInstance<ConversationEffect.Send>()
        assertEquals(listOf("cancel", "text"), sent.map { it.message.type })
        assertNotSame(old, c.state.replyRevision)
        assertTrue(c.state.replyChanging)
        val pending = c.state.replyRevision
        c.cancel(1, true)
        assertNotSame(pending, c.state.replyRevision)
        assertFalse(c.state.replyChanging)
        val cancelled = c.state.replyRevision
        c.startRecording(2)
        assertNotSame(cancelled, c.state.replyRevision)
        assertTrue(c.state.replyChanging)
    }

    @Test fun untaggedStockRepliesAndMalformedMessagesRespectPublicationBoundary() {
        val c = Conversation().apply { setPaired(true) }
        val before = c.state.replyRevision
        c.receive(event("reply"), 0)
        assertSame(before, c.state.replyRevision)
        c.receive(event("reply.delta", text = "delta"), 1)
        assertTrue(c.state.replyChanging)
        val delta = c.state.replyRevision
        c.receive(event("status", text = "Working"), 2)
        assertSame(delta, c.state.replyRevision)
        c.receive(event("reply", text = "delta"), 3)
        assertFalse(c.state.replyChanging)
        assertNotSame(delta, c.state.replyRevision)
    }
}
