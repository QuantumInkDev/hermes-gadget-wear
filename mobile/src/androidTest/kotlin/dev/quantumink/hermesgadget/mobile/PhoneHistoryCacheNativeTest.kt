package dev.quantumink.hermesgadget.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run only on an explicitly owned, backed-up synthetic fixture; never a personal enrollment. */
class PhoneHistoryCacheNativeTest {
    private class ExposingList<T>(private val items: Array<Any?>) : java.util.AbstractList<T>() {
        override val size: Int get() = items.size

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): T = items[index] as T
        override fun toArray(): Array<Any?> = items
    }

    @Test fun nativeSelectionsAndCacheDtosOwnExposedArrays() {
        val scope = HistoryScope(
            "fixture",
            "wss://example.invalid/gadget",
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "a".repeat(64)
        )
        val first = "00000000-0000-4000-8000-000000000003"
        val second = "00000000-0000-4000-8000-000000000004"
        val ids = arrayOf<Any?>(first, second)
        val review = HistoryExportReview(scope, 1, ExposingList(ids), "Fixture")
        ids[0] = second
        assertEquals(listOf(first, second), review.conversationIds)
        val original = HistoryMessage("m1", HistoryRole.USER, "Fixture", 123)
        val messages = arrayOf<Any?>(original, original.copy(id = "m2"))
        val entry = HistoryThread(first, 1, "Fixture", false, ExposingList(messages))
        messages[0] = original.copy(id = "replaced")
        assertEquals("m1", entry.messages.first().id)
        val threads = arrayOf<Any?>(entry, entry)
        val snapshot = HistorySnapshot(1, threads = ExposingList(threads))
        threads[0] = HistoryThread(second, 1, "Other", false, emptyList())
        assertEquals(first, snapshot.threads.first().id)
    }

    @Test fun nativeKeySurvivesReopenAndMissingKeyNeverRecreatesOverHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "history-fixture-$identity")
        val alias = "history.fixture.$identity"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!directory.exists() && !keys.containsAlias(alias))
        try {
            val scope = HistoryScope(
                "synthetic:profile",
                "wss://example.invalid/gadget",
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
                "a".repeat(64)
            )
            val cache = PhoneHistoryCache(context, directory, alias)
            val entry = HistoryThread(
                "00000000-0000-4000-8000-000000000003",
                1,
                "Synthetic notes",
                false,
                listOf(HistoryMessage("m1", HistoryRole.USER, "Fixture-only text", 123))
            )
            cache.put(scope, entry, 0)
            assertTrue(keys.containsAlias(alias))
            assertEquals(
                "Fixture-only text",
                PhoneHistoryCache(context, directory, alias)
                    .read(scope).threads.single().messages.single().text
            )
            val record = File(directory, "history.v1")
            val before = record.readBytes()
            keys.deleteEntry(alias)
            assertTrue(runCatching { cache.read(scope) }.isFailure)
            assertFalse(keys.containsAlias(alias))
            assertTrue(before.contentEquals(record.readBytes()))
        } finally {
            keys.deleteEntry(alias)
            check(directory.deleteRecursively())
        }
    }
}
