package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import java.util.Collections
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneHistoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachePreparationAndEveryDiskOperationRunOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val operations = Collections.synchronizedList(mutableListOf<Thread>())
        val directory = temporary.newFolder()
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cache = PhoneHistoryCache {
            operations += Thread.currentThread()
            EncryptedHistoryCache(directory, {
                operations += Thread.currentThread()
                secret
            }, {
                operations += Thread.currentThread()
                1_800_000_000_000L
            })
        }
        assertTrue(operations.isEmpty())
        val scope =
            HistoryScope(
                EnrollmentRecord.endpointKey("wss://example.invalid/gadget"),
                "wss://example.invalid/gadget",
                "a".repeat(64),
                "00000000-0000-4000-8000-000000000002",
                0L
            )
        val first = cache.read(scope)
        val entry = HistoryThread(
            "00000000-0000-4000-8000-000000000003",
            1,
            "Notes",
            true,
            listOf(HistoryMessage("m1", HistoryRole.USER, "Private thought", 123))
        )
        val saved = cache.put(
            scope,
            entry,
            first.revision,
            HistoryAnchor.Beginning
        )
        assertEquals(entry.id, cache.search(scope, "thought").single().id)
        val configured = cache.setRetention(scope, 7, saved.revision)
        val purged = cache.purge(scope, null, configured.revision)
        assertTrue(cache.read(scope).threads.isEmpty())
        assertEquals(3L, purged.revision)
        assertFalse(operations.isEmpty())
        assertTrue("Disk/crypto must leave the caller thread", operations.none { it === caller })
    }
}
