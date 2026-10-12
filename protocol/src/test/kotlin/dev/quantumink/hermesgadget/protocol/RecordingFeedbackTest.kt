package dev.quantumink.hermesgadget.protocol

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFeedbackTest {
    @Test
    fun eitherArrivalOrderWaitsForRecorderReleaseAndReportsOneActualOutcome() {
        for (finished in listOf(false, true)) {
            for (stoppedFirst in listOf(false, true)) {
                val results = mutableListOf<Boolean>()
                val feedback = RecordingFeedback(results::add)
                feedback.begin("current")
                if (stoppedFirst) {
                    feedback.stopped(
                        "current"
                    )
                } else {
                    feedback.outcome("current", finished)
                }
                assertTrue(results.isEmpty())
                if (stoppedFirst) {
                    feedback.outcome(
                        "current",
                        finished
                    )
                } else {
                    feedback.stopped("current")
                }
                assertEquals(listOf(finished), results)
                feedback.stopped("current")
                feedback.outcome("current", !finished)
                feedback.begin("current")
                feedback.stopped("current")
                assertEquals(listOf(finished), results)
            }
        }
    }

    @Test
    fun lateOldTokensCannotCompleteOrChangeTheNextRecordingInEitherOrder() {
        for (oldStoppedFirst in listOf(false, true)) {
            val results = mutableListOf<Boolean>()
            val feedback = RecordingFeedback(results::add)
            feedback.begin("old")
            if (oldStoppedFirst) feedback.stopped("old") else feedback.outcome("old", true)
            feedback.begin("new")
            feedback.stopped("old")
            feedback.outcome("old", true)
            feedback.outcome("new", false)
            assertTrue(results.isEmpty())
            feedback.stopped("new")
            assertEquals(listOf(false), results)
            feedback.stopped("old")
            feedback.outcome("old", false)
            assertEquals(listOf(false), results)
        }
    }

    @Test
    fun invalidateDiscardsEitherPendingHalfAndIgnoresAllLaterSignals() {
        for (stoppedFirst in listOf(false, true)) {
            val results = mutableListOf<Boolean>()
            val feedback = RecordingFeedback(results::add)
            feedback.begin("hidden")
            if (stoppedFirst) feedback.stopped("hidden") else feedback.outcome("hidden", true)
            feedback.invalidate()
            feedback.stopped("hidden")
            feedback.outcome("hidden", true)
            assertTrue(results.isEmpty())
            feedback.begin("visible")
            feedback.outcome("visible", true)
            feedback.stopped("visible")
            assertEquals(listOf(true), results)
        }
    }

    @Test(timeout = 10000)
    fun concurrentStopAndOutcomeStillDeliverExactlyOnce() {
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val feedback = RecordingFeedback(results::add)
        feedback.begin("current")
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val workers = listOf<() -> Unit>(
            { feedback.stopped("current") },
            { feedback.outcome("current", true) }
        ).map { signal ->
            Thread {
                ready.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    repeat(10) { signal() }
                } finally {
                    done.countDown()
                }
            }.apply { start() }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(true), results)
        } finally {
            release.countDown()
            workers.forEach { it.join(1000) }
        }
    }

    @Test(timeout = 10000)
    fun resultCallbackRunsOutsideTheFeedbackMonitor() {
        val callbackEntered = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val otherCallerDone = CountDownLatch(1)
        val feedback = RecordingFeedback {
            callbackEntered.countDown()
            try {
                check(otherCallerDone.await(5, TimeUnit.SECONDS))
            } finally {
                callbackDone.countDown()
            }
        }
        feedback.begin("current")
        feedback.outcome("current", true)
        val worker = Thread { feedback.stopped("current") }.apply { start() }
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            feedback.invalidate()
            otherCallerDone.countDown()
            assertTrue(callbackDone.await(5, TimeUnit.SECONDS))
        } finally {
            otherCallerDone.countDown()
            worker.join(1000)
        }
    }

    @Test
    fun shortManualReleaseAndAcceptedAutomaticLimitUseReducerOutcomesAfterShutdown() {
        for (automatic in listOf(false, true)) {
            val c = Conversation().also { it.setPaired(true) }
            val token = c.startRecording(0)
                .filterIsInstance<ConversationEffect.CaptureStart>().single().token
            val results = mutableListOf<Boolean>()
            val feedback = RecordingFeedback(results::add)
            feedback.begin(token)
            if (automatic) repeat(13) { c.capture(token, ByteArray(640), it * 20L) }
            if (!automatic) feedback.stopped(token)
            val effects = if (automatic) c.tick(60000) else c.finishRecording(100, token)
            val outcome = effects.filterIsInstance<ConversationEffect.RecordingOutcome>().single()
            feedback.outcome(outcome.token, outcome.finished)
            if (automatic) {
                assertTrue(results.isEmpty())
                feedback.stopped(token)
            }
            assertEquals(listOf(automatic), results)
        }
    }
}
