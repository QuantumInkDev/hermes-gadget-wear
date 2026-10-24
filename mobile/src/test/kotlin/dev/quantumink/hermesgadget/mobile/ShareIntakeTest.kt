package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeTest {
    private val link = "https://example.invalid/a"
    private val unsupported = SharedContent.Rejected(ShareRejection.UNSUPPORTED)
    private val tooLong = SharedContent.Rejected(ShareRejection.TOO_LONG)

    private fun text(subject: CharSequence?, body: CharSequence?): SharedContent =
        ShareIntake.read(ShareIntake.ACTION_SEND, "text/plain", subject, body, false)

    private fun share(
        type: String?,
        body: String?,
        stream: Boolean = false,
        action: String? = ShareIntake.ACTION_SEND
    ) = ShareIntake.read(action, type, null, body, stream)

    private fun accepted(value: SharedContent): String = (value as SharedContent.Text).text

    @Test fun textAndLinksBecomeTrimmedDraftsWithSubjectFirst() {
        assertEquals(link, accepted(text(null, "  $link\n")))
        assertEquals("Title\n\n$link", accepted(text("Title", link)))
        assertEquals("Only subject", accepted(text(" Only subject ", "")))
        // Apps that already include the subject in the text are not duplicated.
        assertEquals("Title $link", accepted(text("Title", "Title $link")))
        assertEquals("plain", accepted(share("TEXT/PLAIN; charset=utf-8", "plain")))
    }

    @Test fun emptyInputIsRejected() {
        for ((subject, body) in listOf(null to null, "" to "", " \n" to "\t")) {
            assertEquals(SharedContent.Rejected(ShareRejection.EMPTY), text(subject, body))
        }
    }

    @Test fun filesOtherTypesAndOtherActionsAreUnsupportedNotDropped() {
        assertEquals(unsupported, share("image/png", "caption"))
        assertEquals(unsupported, share("application/pdf", null, stream = true))
        assertEquals(unsupported, share("*/*", "x"))
        assertEquals(unsupported, share(null, "x"))
        // A text/plain file stream is a document, not shared text.
        assertEquals(unsupported, share("text/plain", "x", stream = true))
        val multiple = "android.intent.action.SEND_MULTIPLE"
        assertEquals(unsupported, share("text/plain", "x", action = multiple))
        assertEquals(unsupported, share("text/plain", "x", action = null))
    }

    @Test fun boundIsUtf8BytesOfTheCombinedDraft() {
        val max = ShareIntake.MAX_BYTES
        assertEquals(max, accepted(text(null, "a".repeat(max))).length)
        assertEquals(tooLong, text(null, "a".repeat(max + 1)))
        // Two-byte characters halve the character budget.
        assertTrue(text(null, "é".repeat(max / 2)) is SharedContent.Text)
        assertEquals(tooLong, text(null, "é".repeat(max / 2 + 1)))
        // Subject plus separator can push an in-bound body over.
        assertEquals(tooLong, text("Title", "a".repeat(max - 4)))
        // Huge input is refused without being copied.
        assertEquals(tooLong, text(null, StringBuilder("a".repeat(max * 64))))
        assertEquals(tooLong, text("a".repeat(max + 1), "b"))
    }

    @Test fun editedDraftsMustStayNonblankAndBounded() {
        assertTrue(ShareIntake.sendable("hello"))
        assertFalse(ShareIntake.sendable("   "))
        assertFalse(ShareIntake.sendable("a".repeat(ShareIntake.MAX_BYTES + 1)))
        assertFalse(ShareIntake.sendable("€".repeat(ShareIntake.MAX_BYTES / 3 + 1)))
    }

    @Test fun sharedTextIsRedactedFromLogs() {
        assertFalse(text(null, "secret link").toString().contains("secret"))
    }

    @Test fun finalGateRequiresTheExactSelectedTlsProfileAndAnIdleConversation() {
        val page = page("one", CatalogStatus.AVAILABLE)
        val profile = endpoint("one")
        val idle = PhoneConversationState()
        assertSame(page, ShareIntake.accept(page.key, "hi", page to profile, idle)?.first)
        assertNull(ShareIntake.accept(page.key, "hi", null, idle))
        assertNull(ShareIntake.accept("box:other", "hi", page to profile, idle))
        assertNull(ShareIntake.accept(page.key, " ", page to profile, idle))
        val long = "a".repeat(ShareIntake.MAX_BYTES + 1)
        assertNull(ShareIntake.accept(page.key, long, page to profile, idle))
        val withdrawn = page("one", CatalogStatus.WITHDRAWN)
        assertNull(ShareIntake.accept(page.key, "hi", withdrawn to profile, idle))
        assertNull(ShareIntake.accept(page.key, "hi", page to endpoint("two"), idle))
        val busy = idle.copy(visible = true, sending = true)
        assertNull(ShareIntake.accept(page.key, "hi", page to profile, busy))
        val pairing = idle.copy(
            visible = true,
            connection = idle.connection.copy(status = ConnectionStatus.PAIRING)
        )
        assertNull(ShareIntake.accept(page.key, "hi", page to profile, pairing))
    }

    @Test fun handoffTokensAreRandomBoundedAndRemovable() {
        val store = ShareHandoffStore(capacity = 2)
        val first = store.put(SharedContent.Rejected(ShareRejection.EMPTY))
        val second = store.put(SharedContent.Text("two"))
        assertNotEquals(first, second)
        assertEquals("two", accepted(requireNotNull(store.get(second))))
        // Reads are repeatable (rotation) until the share is used or discarded.
        assertEquals("two", accepted(requireNotNull(store.get(second))))
        val third = store.put(SharedContent.Text("three"))
        assertNull(store.get(first))
        assertTrue(store.get(third) is SharedContent.Text)
        store.remove(second)
        assertNull(store.get(second))
        assertNull(store.get(null))
        assertNull(store.get("unknown"))
        store.remove(null)
    }

    private fun endpoint(id: String) =
        EndpointProfile(Endpoint.parse("wss://$id.example.invalid/gadget"), id, "")

    private fun page(id: String, status: CatalogStatus) = CatalogPage(
        "box",
        "Box",
        CatalogProfile(id, "One", "wss://$id.example.invalid/gadget", null),
        status,
        0
    )
}
