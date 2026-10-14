package dev.quantumink.hermesgadget.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDraftsTest {
    @Test fun switchingEndpointsRestoresOnlyTheirUnsentDraftAndResetClearsMemory() {
        val drafts = ProfileDrafts()
        assertEquals("", drafts.select("wss://a.example.invalid/gadget"))
        drafts.update("unsent A")
        assertEquals("", drafts.select("wss://b.example.invalid/gadget"))
        drafts.update("unsent B")
        assertEquals("unsent A", drafts.select("wss://a.example.invalid/gadget"))
        drafts.update("x".repeat(5000))
        assertEquals(4096, drafts.select("wss://a.example.invalid/gadget").length)
        drafts.remove("wss://a.example.invalid/gadget")
        assertEquals("", drafts.select("wss://a.example.invalid/gadget"))
        assertEquals("unsent B", drafts.select("wss://b.example.invalid/gadget"))
        drafts.clear()
        assertEquals("", drafts.select("wss://b.example.invalid/gadget"))
    }
}
