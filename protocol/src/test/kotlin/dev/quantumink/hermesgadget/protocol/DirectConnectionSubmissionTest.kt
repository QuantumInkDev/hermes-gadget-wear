package dev.quantumink.hermesgadget.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectConnectionSubmissionTest {
    @Test(timeout = 10000)
    fun closeRejectsQueuedDraftWithoutLeavingSubmissionPending() {
        val observer = PausedObserver()
        DirectConnection(
            Endpoint.parse("wss://example.invalid/gadget"),
            DeviceIdentity.generate(),
            observer
        ).use { connection ->
            try {
                assertTrue(observer.entered.await(5, TimeUnit.SECONDS))
                val submission = connection.text("Synthetic queued draft")
                assertFalse(submission.isDone)
                connection.close()
                assertFalse(submission.get(5, TimeUnit.SECONDS))
                assertFalse(connection.text("Synthetic closed draft").get(5, TimeUnit.SECONDS))
            } finally {
                observer.resume.countDown()
            }
        }
    }

    @Test(timeout = 10000)
    fun fullQueueRejectsDraftsAndEverySubmissionEventuallyCompletes() {
        val observer = PausedObserver()
        DirectConnection(
            Endpoint.parse("wss://example.invalid/gadget"),
            DeviceIdentity.generate(),
            observer
        ).use { connection ->
            try {
                assertTrue(observer.entered.await(5, TimeUnit.SECONDS))
                val submissions = (1..40).map { connection.text("Synthetic draft $it") }
                assertTrue(submissions.any { it.isDone })
                submissions.filter { it.isDone }.forEach {
                    assertFalse(it.get(5, TimeUnit.SECONDS))
                }
                observer.resume.countDown()
                submissions.forEach { assertFalse(it.get(5, TimeUnit.SECONDS)) }
            } finally {
                observer.resume.countDown()
            }
        }
    }

    private class PausedObserver : ConnectionObserver {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private val paused = AtomicBoolean(false)

        override fun stateChanged(state: ConnectionState) {
            if (paused.compareAndSet(false, true)) {
                entered.countDown()
                try {
                    check(resume.await(5, TimeUnit.SECONDS))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        override fun effect(effect: ConversationEffect, generation: Long) = Unit
    }
}
