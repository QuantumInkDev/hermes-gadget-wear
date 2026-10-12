package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.PcmCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun framesFollowStartCueAndFinishFollowsRecorderShutdown() = capture(true)

    @Test fun cancelledCaptureStopsWithoutFinishingUpload() = capture(false)

    @Test fun replacementDoesNotPublishThePreviousCaptureStopCue() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        context.startActivity(
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        val recorder = PcmCapture(context)
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newFrame = CountDownLatch(1)
        val newStopped = CountDownLatch(1)
        val oldStops = AtomicInteger()
        val oldThread = AtomicReference<Thread>()
        try {
            recorder.start(onFrame = {}, onFailure = {}, onStarted = {
                oldThread.set(Thread.currentThread())
                oldStarted.countDown()
                assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
            }, onStopped = { oldStops.incrementAndGet() })
            assertTrue(oldStarted.await(3, TimeUnit.SECONDS))
            recorder.start(
                onFrame = { newFrame.countDown() },
                onFailure = {},
                onStopped = { newStopped.countDown() }
            )
            assertTrue(newFrame.await(3, TimeUnit.SECONDS))
            releaseOld.countDown()
            oldThread.get().join(2000)
            assertFalse(oldThread.get().isAlive)
            assertEquals(0, oldStops.get())
            recorder.stop()
            assertTrue(newStopped.await(2, TimeUnit.SECONDS))
        } finally {
            releaseOld.countDown()
            recorder.stop()
        }
    }

    private fun capture(finish: Boolean) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        context.startActivity(
            android.content.Intent(
                context,
                MainActivity::class.java
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        val recorder = PcmCapture(context)
        val ready = AtomicBoolean()
        val stopped = AtomicBoolean()
        val badFrame = AtomicBoolean()
        val failed = AtomicBoolean()
        val frames = AtomicInteger()
        val starts = AtomicInteger()
        val firstFrame = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val endedNormally = AtomicBoolean()
        try {
            recorder.start(
                onFrame = {
                    if (!ready.get() || stopped.get()) badFrame.set(true)
                    frames.incrementAndGet()
                    firstFrame.countDown()
                },
                onFailure = {
                    failed.set(true)
                    firstFrame.countDown()
                },
                onStarted = {
                    starts.incrementAndGet()
                    SystemClock.sleep(75)
                    ready.set(true)
                },
                onStopped = {
                    endedNormally.set(it)
                    stopped.set(true)
                    stop.countDown()
                },
                onFinished = {
                    if (!stopped.get()) badFrame.set(true)
                    completed.countDown()
                }
            )
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            assertFalse(failed.get())
            assertTrue(frames.get() > 0)
            if (finish) assertTrue(recorder.finish()) else recorder.stop()
            assertTrue(stop.await(5, TimeUnit.SECONDS))
            if (finish) {
                assertTrue(completed.await(2, TimeUnit.SECONDS))
            } else {
                assertFalse(completed.await(150, TimeUnit.MILLISECONDS))
            }
            val finalFrames = frames.get()
            SystemClock.sleep(100)
            assertEquals(finalFrames, frames.get())
            assertEquals(1, starts.get())
            assertEquals(finish, endedNormally.get())
            assertFalse(badFrame.get())
        } finally {
            recorder.stop()
        }
    }

    @Test fun deniedPermissionCannotEmitRecordingSuccessCues() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) {
                    PackageManager.PERMISSION_DENIED
                } else {
                    super.checkSelfPermission(permission)
                }
        }
        val failure = CountDownLatch(1)
        val success = AtomicInteger()
        val recorder = PcmCapture(denied)
        try {
            recorder.start(
                onFrame = { success.incrementAndGet() },
                onFailure = { failure.countDown() },
                onStarted = { success.incrementAndGet() },
                onStopped = { success.incrementAndGet() },
                onFinished = { success.incrementAndGet() }
            )
            assertTrue(failure.await(2, TimeUnit.SECONDS))
            assertEquals(0, success.get())
        } finally {
            recorder.stop()
        }
    }
}
