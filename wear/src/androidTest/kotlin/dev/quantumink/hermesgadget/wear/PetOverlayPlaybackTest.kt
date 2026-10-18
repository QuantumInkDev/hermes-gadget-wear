package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.PcmPlayback
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.PetStates
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real AudioTrack drain/stop callbacks drive the production PetView; no server or microphone. */
class PetOverlayPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val captures get() = File(context.filesDir, "o2-overlay-captures").apply { mkdirs() }

    @Test fun actualPcmPlaybackSelectsThreeDerivedMouthsAndClosesOnDrainAndStop() {
        val activity = instrumentation.startActivitySync(
            Intent(context, AppearanceTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val (manifest, _, bytes) = PetOverlayTest().fixture()
        val rows = PetCache(context).decode(manifest.sheets[1], bytes)
        val atlas = PetAtlas(
            "Overlay fixture",
            1100,
            rows + ("idle" to listOf(rows.getValue("talking")[0]))
        )
        val watch =
            mutableStateOf(
                WatchState(
                    pet = atlas,
                    connection = ConnectionState(status = ConnectionStatus.PAIRED)
                )
            )
        val animated = mutableStateOf(true)
        val ambient = mutableStateOf(false)
        val finished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val playback = PcmPlayback(
            context,
            onInterrupted = { interrupted.set(true) },
            onLevel = { level ->
                watch.value = watch.value.copy(playbackLevel = level)
                events.add("${SystemClock.elapsedRealtime()},$level")
            },
            onFinished = {
                watch.value = watch.value.copy(speaking = false, playbackLevel = 0f)
                finished.countDown()
            }
        )
        val originalMotion = Settings.Global.getString(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE
        )
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val originalPower = power.isPowerSaveMode
        fun shell(command: String): String =
            automation.executeShellCommand(command).let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    descriptor
                ).bufferedReader().use {
                    it.readText()
                }
            }
        fun motion(value: String) {
            shell("settings put global animator_duration_scale $value")
            SystemClock.sleep(150)
        }
        try {
            assertTrue(
                "Fixture requires motion enabled initially",
                originalMotion == null || originalMotion.toFloat() > 0f
            )
            File(captures, "manifest.json").writeText(manifest.encoded)
            File(captures, "source-companion.png").writeBytes(bytes)
            rows.getValue("talking").forEachIndexed { index, frame ->
                File(captures, "derived-$index.png").outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            instrumentation.runOnMainSync {
                activity.setTurnScreenOn(true)
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
                activity.setContent {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ambient.value) {
                            AmbientPet(atlas, 123456, false, false)
                        } else {
                            PetView(watch.value, animated = animated.value, portraitSurface = true)
                        }
                    }
                }
                watch.value = watch.value.copy(speaking = true)
            }
            playback.start(16000)
            val producer = Thread {
                for (amplitude in listOf(0, 3500, 12000, 0)) {
                    repeat(16) {
                        val pcm = ByteArray(3200)
                        for (i in 0 until 1600) {
                            val sample = if (i % 16 < 8) amplitude else -amplitude
                            pcm[i * 2] = sample.toByte()
                            pcm[i * 2 + 1] = (sample shr 8).toByte()
                        }
                        playback.offer(pcm)
                        SystemClock.sleep(100)
                    }
                }
                playback.finish()
            }.apply { start() }
            for (mouth in 0..2) {
                await {
                    PetStates.mouth(watch.value.playbackLevel, 0) == mouth &&
                        events.size > mouth * 3
                }
                SystemClock.sleep(150)
                capture("playback-mouth-$mouth")
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            producer.join(2000)
            assertTrue(!producer.isAlive)
            assertTrue(!interrupted.get())
            assertEquals(0f, watch.value.playbackLevel)
            assertTrue(!watch.value.speaking)
            capture("drained-closed")
            // Existing reduced-motion/offscreen policy must freeze an otherwise open mouth.
            instrumentation.runOnMainSync {
                watch.value =
                    watch.value.copy(speaking = true, playbackLevel = .4f)
            }
            SystemClock.sleep(200)
            capture("open-reference")
            motion("0")
            capture("reduced-motion-closed")
            motion(originalMotion ?: "1")
            instrumentation.runOnMainSync { animated.value = false }
            SystemClock.sleep(200)
            capture("offscreen-static-closed")
            instrumentation.runOnMainSync { ambient.value = true }
            SystemClock.sleep(150)
            capture("ambient-static")
            instrumentation.runOnMainSync { ambient.value = false }
            val batteryResult = shell("cmd power set-mode 1")
            SystemClock.sleep(200)
            File(
                captures,
                "battery-saver.txt"
            ).writeText(
                "requested=true; actual=${power.isPowerSaveMode}; original=$originalPower; " +
                    "result=$batteryResult; physical gate remains open"
            )
            if (power.isPowerSaveMode) capture("battery-saver-static")
            shell("cmd power set-mode ${if (originalPower) 1 else 0}")
            instrumentation.runOnMainSync {
                animated.value = true
                watch.value =
                    watch.value.copy(speaking = true)
            }
            playback.start(16000)
            playback.offer(ByteArray(3200) { 32 })
            playback.stop()
            await { !watch.value.speaking && watch.value.playbackLevel == 0f }
            capture("stopped-closed")
            File(
                captures,
                "playback-levels.csv"
            ).writeText("elapsed_ms,rms\n" + events.joinToString("\n"))
        } finally {
            playback.stop()
            shell("cmd power set-mode ${if (originalPower) 1 else 0}")
            if (originalMotion == null) {
                shell("settings delete global animator_duration_scale")
            } else {
                motion(originalMotion)
            }
            instrumentation.runOnMainSync {
                activity.setContent {}
                activity.finish()
            }
            rows.values.flatten().forEach(Bitmap::recycle)
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    private fun await(condition: () -> Boolean) {
        repeat(160) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Actual playback callback did not reach expected amplitude")
    }
}
