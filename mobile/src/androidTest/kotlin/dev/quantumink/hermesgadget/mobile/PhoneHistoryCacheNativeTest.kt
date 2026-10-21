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
