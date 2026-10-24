package dev.quantumink.hermesgadget.protocol.relay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Current registered producer frames through actual controller/DirectConnection; in-memory socket only. */
class RelayMetadataReplayTest {
    private fun setup(h: OwnReplayHarness) {
        h.prepareAndEnable()
        h.connect()
        h.controller.create("Planning")
        h.settle()
        assertEquals(RelayPhase.SELECTED, h.ui.phase)
    }

    @Test fun sanitizedRenameBookmarkQueryAndLostResponseRetryKeepOneImmutableIntent() {
        OwnReplayHarness("integrated", resourceRoot = "/relay-task4-v1").use { h ->
            setup(h)
            val id = h.session.conversationId
            h.controller.editMetadata(id, "  Trip \u200b plans  ", null)
            h.settle()
            assertEquals("Trip plans", h.ui.selected?.title)
            assertEquals(MetadataStatus.ACCEPTED, h.ui.metadataIntents.single().status)
            h.controller.abandonMetadata(h.ui.metadataIntents.single().requestId.value)
            h.settle()
            h.controller.editMetadata(id, null, true)
            h.settle()
            assertTrue(requireNotNull(h.ui.selected).bookmarked)
            assertEquals(false, h.ui.metadataIntents.single().coreCommitted)
            h.controller.abandonMetadata(h.ui.metadataIntents.single().requestId.value)
            h.settle()
            h.controller.searchOnline("Trip", true)
            h.settle()
            assertEquals(listOf(id), h.ui.conversations.map { it.id })
            h.server.holdNextResponse("relay.conversation.update")
            h.controller.editMetadata(id, "Kept", null)
            h.settle()
            val original = h.store.reread().section.intents.single()
            assertEquals("Trip plans", h.ui.selected?.title)
            h.advanceConnection(20_000)
            assertEquals(MetadataStatus.UNKNOWN, h.ui.metadataIntents.single().status)
            assertEquals(3, h.requests("relay.conversation.update").size)
            h.controller.retryMetadata(original.requestId.value)
            h.settle()
            val after = h.store.reread().section.intents.single()
            assertEquals(original.requestId, after.requestId)
            assertEquals(original.expectedRevision, after.expectedRevision)
            assertEquals(original.title, after.title)
            assertEquals("Kept", h.ui.selected?.title)
            assertEquals(4, h.durableMetadata)
            val title = h.ui.selected?.title
            h.server.release(h.server.heldIndexes().single())
            h.settle()
            assertEquals(title, h.ui.selected?.title)
            assertTrue(h.server.mismatches.toString(), h.server.mismatches.isEmpty())
            assertTrue(h.server.extra.toString(), h.server.extra.isEmpty())
            assertEquals(h.session.steps.size, h.server.consumedCount)
        }
    }

    private fun refused(failure: StoreCommit) {
        OwnReplayHarness("integrated", resourceRoot = "/relay-task4-v1").use { h ->
            setup(h)
            h.store.failAll = failure
            h.controller.editMetadata(h.session.conversationId, "  Trip \u200b plans  ", null)
            h.settle()
            assertEquals(0, h.entropyExhaustions)
            assertEquals(0, h.durableMetadata)
            assertTrue(h.requests("relay.conversation.update").isEmpty())
            assertFalse(h.ui.metadataIntents.isNotEmpty())
        }
    }

    @Test fun failedMetadataCommitRefusesActualSocketAcceptance() =
        refused(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun uncertainMetadataCommitRefusesActualSocketAcceptance() =
        refused(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true))

    @Test fun refusedMetadataCommitRefusesActualSocketAcceptance() =
        refused(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun staleMetadataCommitRefusesActualSocketAcceptance() = refused(StoreCommit.Stale)
}
