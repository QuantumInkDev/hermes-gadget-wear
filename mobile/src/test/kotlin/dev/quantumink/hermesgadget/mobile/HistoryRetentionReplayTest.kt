package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryRetentionReplayTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val scope =
        HistoryScope(
            EnrollmentRecord.endpointKey("wss://example.invalid/gadget"),
            "wss://example.invalid/gadget",
            "a".repeat(64),
            "00000000-0000-4000-8000-000000000002",
            0L
        )
    private val thread = HistoryThread(
        "00000000-0000-4000-8000-000000000003",
        1,
        "Retained",
        false,
        listOf(HistoryMessage("m1", HistoryRole.USER, "Do not renew this receipt", 123))
    )

    @Test fun expiredReplayAfterPruneNeverReceivesANewRetentionAge() {
        val cache = EncryptedHistoryCache(temporary.newFolder(), { key }, { now })
        cache.put(
            scope,
            thread,
            0,
            HistoryAnchor.Beginning
        )
        cache.setRetention(scope, 1, 1)
        now += 86_400_001L
        val expired = cache.read(scope)
        assertTrue(expired.threads.isEmpty())
        val failure = runCatching {
            cache.put(
                scope,
                thread,
                expired.revision,
                HistoryAnchor.Beginning
            )
        }.exceptionOrNull()
        assertTrue(failure is HistoryFailure)
        assertEquals(HistoryProblem.REBUILD_REQUIRED, (failure as HistoryFailure).problem)
        assertTrue(cache.read(scope).threads.isEmpty())
    }

    @Test fun exactReplayBeforeExpiryDoesNotRenewTheReceipt() {
        val cache = EncryptedHistoryCache(temporary.newFolder(), { key }, { now })
        cache.put(
            scope,
            thread,
            0,
            HistoryAnchor.Beginning
        )
        cache.setRetention(scope, 1, 1)
        now += 43_200_000L
        assertEquals(
            2L,
            cache.put(
                scope,
                thread,
                2,
                HistoryAnchor.Beginning
            ).revision
        )
        now += 43_200_001L
        assertTrue(cache.read(scope).threads.isEmpty())
    }
}
