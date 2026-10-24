package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.MetadataState
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySearchMetadataTest {
    private fun ready(store: MemoryStore = MemoryStore()): ControllerFixture =
        ControllerFixture(store = store).also {
            it.enable()
            it.connect(Dto.info(methods = Dto.ALL_METHODS + "relay.conversation.update"))
        }

    @Test fun replacementQueryRejectsOldRepliesAndTimersWhileEmptyCursorPagesMakeProgress() {
        val f = ready()
        val old = f.transport.next("relay.conversations.list")
        f.controller.searchOnline("needle", true)
        f.run()
        val query = f.transport.open("relay.conversations.list").last()
        f.transport.reply(old, Dto.page(Dto.summary(title = "Old")).toWire())
        f.run()
        assertTrue(f.ui.conversations.isEmpty())
        assertEquals("needle", query.params["query"]!!.jsonPrimitive.content)
        assertEquals("true", query.params["bookmarked"]!!.jsonPrimitive.content)
        f.transport.reply(query, Dto.page(next = "progress-1").toWire())
        f.run()
        val progress = f.transport.next("relay.conversations.list")
        assertEquals("progress-1", progress.params["cursor"]!!.jsonPrimitive.content)
        assertEquals(query.params["query"], progress.params["query"])
        f.transport.timeout(progress)
        f.run()
        f.controller.searchOnline("replacement", false)
        f.run()
        val before = f.frames("relay.conversations.list").size
        f.advanceTime(2_000)
        assertEquals(before, f.frames("relay.conversations.list").size)
        f.transport.reply(progress, Dto.page(Dto.summary(title = "Late")).toWire())
        f.run()
        assertTrue(f.ui.conversations.isEmpty())
        assertEquals("replacement", f.ui.onlineQuery)
    }

    @Test fun repeatedProgressCursorIsPartialAndCannotLoopForever() {
        val f = ready()
        f.answer("relay.conversations.list", Dto.page(next = "same").toWire())
        f.answer("relay.conversations.list", Dto.page(next = "same").toWire())
        assertFalse(f.ui.hasMore)
        assertEquals(RelayNotice.HISTORY_INCOMPLETE, f.ui.notice)
        assertEquals(2, f.frames("relay.conversations.list").size)
    }

    @Test fun restoredLostEditRequiresDeliberateSameIdAndNeverRebasesItsPublicRevision() {
        val f = ready()
        f.listAnd(Dto.summary())
        f.controller.editMetadata(Dto.conversation.value, "Original raw name", null)
        f.run()
        val first = f.transport.next("relay.conversation.update")
        f.transport.timeout(first)
        f.run()
        val intent = f.store.reread().section.intents.single()
        val restarted = ready(f.store)
        restarted.listAnd(
            ConversationSummary(Dto.conversation, "Other device", 7, false, Dto.SERVER_TIME, true)
        )
        assertTrue(restarted.frames("relay.conversation.update").isEmpty())
        restarted.controller.retryMetadata(intent.requestId.value)
        restarted.run()
        val retry = restarted.transport.next("relay.conversation.update")
        assertEquals(first.params, retry.params)
        assertEquals(1L, restarted.store.reread().section.intents.single().expectedRevision)
        restarted.transport.fail(retry, "conflict", snapshotRequired = true)
        restarted.run()
        assertEquals(MetadataStatus.STALE_SNAPSHOT, restarted.ui.metadataIntents.single().status)
        assertEquals("Other device", restarted.ui.conversations.single().title)
        restarted.advanceTime(5_000)
        assertEquals(1, restarted.frames("relay.conversation.update").size)
    }

    @Test fun readOnlyRowsAndWithdrawnOptionalMethodCannotEditOrReleaseAnOldIntent() {
        val f = ready()
        f.listAnd(
            ConversationSummary(
                Dto.conversation,
                "Read only",
                4,
                true,
                Dto.SERVER_TIME,
                false,
                MetadataState.UNAVAILABLE
            )
        )
        f.controller.editMetadata(Dto.conversation.value, "Never", false)
        f.run()
        assertTrue(f.frames("relay.conversation.update").isEmpty())
        f.controller.refreshConversations()
        f.run()
        f.listAnd(Dto.summary())
        f.controller.editMetadata(Dto.conversation.value, "Pending", null)
        f.run()
        val pending = f.transport.next("relay.conversation.update")
        f.transport.timeout(pending)
        f.run()
        val intent = f.ui.metadataIntents.single()
        f.ready(Dto.info())
        f.controller.retryMetadata(intent.requestId.value)
        f.run()
        assertEquals(1, f.frames("relay.conversation.update").size)
    }

    @Test fun actualCapturedOutcomesRemainDistinctAndNoTransientOrLostReplyAutoResubmits() {
        val expected = mapOf(
            "stale" to MetadataStatus.STALE_SNAPSHOT,
            "unavailable" to MetadataStatus.UNAVAILABLE,
            "superseded" to MetadataStatus.SUPERSEDED,
            "retired" to MetadataStatus.RETIRED,
            "expired" to MetadataStatus.EXPIRED,
            "method-withdrawn" to MetadataStatus.REFUSED
        )
        for ((scenario, status) in expected) {
            val root = Json.parseToJsonElement(
                javaClass.getResourceAsStream("/relay-task4-v1/$scenario.json")!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            ).jsonObject
            val id = ConversationId.of(root["conversation_id"]!!.jsonPrimitive.content)!!
            val f = ready()
            f.listAnd(Dto.summary(id))
            f.controller.editMetadata(id.value, "Requested", null)
            f.run()
            val response = root["steps"]!!.jsonArray.last().jsonObject["response"]!!.jsonObject
            val pending = f.transport.next("relay.conversation.update")
            if ("result" in response) {
                f.transport.reply(pending, response["result"]!!.jsonObject)
            } else {
                val error = response["error"]!!.jsonObject
                f.transport.fail(
                    pending,
                    error["code"]!!.jsonPrimitive.content,
                    error["snapshot_required"]?.jsonPrimitive?.content == "true"
                )
            }
            f.run()
            assertEquals(scenario, status, f.ui.metadataIntents.single().status)
            f.advanceTime(1_000)
            assertEquals(1, f.frames("relay.conversation.update").size)
        }
    }

    @Test fun metadataWritesPreserveTheExactPendingTurnVoiceAndOtherAggregateSections() {
        val store = MemoryStore()
        val identity = ControllerFixture().identity
        val voice = Seeds.withCaptureHistoryAndDelivery(identity)
        val turn = Seeds.sentTurn(identity)
        val original =
            RelayDocument(
                voice.section,
                RelayStateSnapshot(
                    2,
                    turn.document.snapshot.ledger,
                    voice.snapshot.histories,
                    voice.snapshot.deliveries,
                    voice.snapshot.captures
                )
            )
        assertTrue(store.commit(original, 0) is StoreCommit.Committed)
        val f = ready(store)
        f.listAnd(Dto.summary())
        val before = store.reread().snapshot
        f.controller.editMetadata(Dto.conversation.value, "Keep sections", true)
        f.run()
        f.transport.fail(f.transport.next("relay.conversation.update"), "busy")
        f.run()
        val after = store.reread().snapshot
        fun bytes(snapshot: RelayStateSnapshot) = (
            SnapshotCodec.encode(
                RelayDocument(ControllerSection.EMPTY, snapshot)
            ) as EncodeResult.Encoded
            ).bytes
        val unaffected = RelayStateSnapshot(
            before.version,
            before.ledger,
            before.histories.filterNot { it.conversationId == Dto.conversation.value },
            before.deliveries,
            before.captures
        )
        assertArrayEquals(bytes(unaffected), bytes(after))
        assertEquals(1, after.captures.records.size)
        assertEquals(1, after.ledger.turns.size)
        assertEquals(MetadataStatus.TRANSIENT, f.ui.metadataIntents.single().status)
    }
}
