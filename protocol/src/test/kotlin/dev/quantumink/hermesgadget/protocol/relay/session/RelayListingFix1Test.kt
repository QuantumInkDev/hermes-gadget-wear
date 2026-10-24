package dev.quantumink.hermesgadget.protocol.relay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayListingFix1Test {
    private fun ready(timing: RelayTiming) = ControllerFixture(timing = timing).also {
        it.enable()
        it.connect()
    }

    @Test fun requestBudgetTerminatesPartialAndDeliberateRefreshKeepsQueryFacts() {
        val f = ready(RelayTiming(maxHistoryRequests = 2, backoffBaseMs = 1))
        f.listAnd()
        f.controller.searchOnline("needle", true)
        f.run()
        val query = f.transport.open("relay.conversations.list").last()
        f.transport.reply(query, Dto.page(Dto.summary(), next = "tail").toWire())
        f.run()
        f.controller.loadMoreConversations()
        f.run()
        f.answerError("relay.conversations.list", "busy")
        f.advanceTime(10)
        assertFalse("dead cursor must be withdrawn", f.ui.hasMore)
        assertFalse(f.ui.listing)
        assertEquals(RelayNotice.HISTORY_INCOMPLETE, f.ui.notice)
        val count = f.frames("relay.conversations.list").size
        f.advanceTime(60_000)
        assertEquals(count, f.frames("relay.conversations.list").size)
        f.controller.refreshConversations()
        f.run()
        val fresh = f.transport.open("relay.conversations.list").last()
        assertEquals(query.params, fresh.params)
        f.transport.reply(fresh, Dto.page(Dto.summary(title = "Fresh")).toWire())
        f.run()
        assertEquals("Fresh", f.ui.conversations.single().title)
        assertFalse(f.ui.hasMore)
    }

    @Test fun retryBudgetTerminatesAsPartialInsteadOfAdvertisingRetrying() {
        val f = ready(RelayTiming(maxRetries = 0))
        f.listAnd(Dto.summary())
        f.controller.refreshConversations()
        f.run()
        f.answerError("relay.conversations.list", "busy")
        assertEquals(RelayNotice.HISTORY_INCOMPLETE, f.ui.notice)
        assertFalse(f.ui.listing)
        assertFalse(f.ui.hasMore)
    }

    @Test fun defaultsAndSuccessfulEmptyProgressRemainValidControls() {
        assertEquals(64, RelayTiming().maxHistoryRequests)
        assertEquals(24, RelayTiming().maxHistoryPages)
        assertEquals(6, RelayTiming().maxRetries)
        val f = ready(RelayTiming(maxHistoryRequests = 3))
        f.answer("relay.conversations.list", Dto.page(next = "progress").toWire())
        f.answer("relay.conversations.list", Dto.page(Dto.summary()).toWire())
        assertEquals(2, f.frames("relay.conversations.list").size)
        assertTrue(f.ui.conversations.isNotEmpty())
        assertFalse(f.ui.hasMore)
    }

    @Test fun defaultSixtyFourthRequestStopsWithoutADeadMoreOrRetryTimer() {
        val f = ready(RelayTiming())
        repeat(9) { page ->
            repeat(6) { attempt ->
                f.answerError("relay.conversations.list", "busy")
                f.advanceTime(minOf(1_000L shl attempt, 30_000L))
            }
            f.answer(
                "relay.conversations.list",
                Dto.page(Dto.summary(title = "page-$page"), next = "cursor-$page").toWire()
            )
            f.controller.loadMoreConversations()
            f.run()
        }
        assertEquals(64, f.frames("relay.conversations.list").size)
        f.answerError("relay.conversations.list", "busy")
        assertFalse(f.ui.hasMore)
        assertFalse(f.ui.listing)
        assertEquals(RelayNotice.HISTORY_INCOMPLETE, f.ui.notice)
        f.advanceTime(60_000)
        assertEquals(64, f.frames("relay.conversations.list").size)
        f.controller.refreshConversations()
        f.run()
        assertEquals(65, f.frames("relay.conversations.list").size)
    }
}
