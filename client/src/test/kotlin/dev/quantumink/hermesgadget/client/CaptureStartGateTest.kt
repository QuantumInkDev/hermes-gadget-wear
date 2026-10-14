package dev.quantumink.hermesgadget.client

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartGateTest {
    @Test fun releaseDuringInitializationPreventsTheRecorderFromStartingLater() {
        val gate = CaptureStartGate()
        val initialized = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val starts = AtomicInteger()
        val worker = CompletableFuture.supplyAsync {
            initialized.countDown()
            assertTrue(resume.await(2, TimeUnit.SECONDS))
            gate.start { starts.incrementAndGet() }
        }
        assertTrue(initialized.await(2, TimeUnit.SECONDS))
        assertFalse(gate.finish())
        resume.countDown()
        assertFalse(worker.get(2, TimeUnit.SECONDS))
        assertEquals(0, starts.get())
    }

    @Test fun cancellationWinsBeforeStartupAndFinishingIsIdempotent() {
        val cancelled = CaptureStartGate()
        cancelled.cancel()
        assertFalse(cancelled.start { error("A cancelled recorder cannot start") })
        val active = CaptureStartGate()
        var starts = 0
        assertTrue(active.start { starts++ })
        assertFalse(active.start { starts++ })
        assertTrue(active.finish())
        assertFalse(active.finish())
        assertEquals(1, starts)
    }
}
