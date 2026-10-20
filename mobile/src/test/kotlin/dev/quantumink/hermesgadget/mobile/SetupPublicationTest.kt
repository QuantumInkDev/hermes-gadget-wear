package dev.quantumink.hermesgadget.mobile

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupPublicationTest {
    @Test fun publicationTimeoutReturnsExactPendingChannelsWithoutReportingSuccess() {
        val pending = CompletableFuture<Unit>()
        val report = SetupPublication(
            pending,
            CompletableFuture.completedFuture(Unit),
            CompletableFuture.completedFuture(Unit)
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Set<String>> { report.await() }
            assertEquals(setOf("watch profiles and pictures"), result.get(15, TimeUnit.SECONDS))
            assertFalse(pending.isDone)
            pending.complete(Unit)
            assertEquals(emptySet<String>(), report.await())
        } finally {
            pending.complete(Unit)
            worker.shutdownNow()
        }
    }
}
