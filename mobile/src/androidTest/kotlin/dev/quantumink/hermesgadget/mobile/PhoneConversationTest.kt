package dev.quantumink.hermesgadget.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires two isolated stock TLS servers and owned-emulator reverse ports. */
class PhoneConversationTest {
    @Test fun independentlyPairsChatsRecordsAndClosesWithoutChangingRelay() = fixture {
            model,
            profiles,
            vault,
            directory
        ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val relay = RelaySettings.enabled(context)
        model.open(profiles[0])
        assertFalse(directory.exists()) // Review alone cannot generate enrollment.
        model.draft("retained draft one")
        model.connect()
        await { model.state.value.connection.status == ConnectionStatus.PAIRED }
        val one = vault.identity(profiles[0].endpoint).deviceId
        model.draft("phone first unique marker")
        model.send()
        model.send()
        await {
            model.state.value.connection.conversation.reply.contains(
                "phone first unique marker"
            )
        }
        assertEquals("", model.state.value.draft)
        model.draft("keep first draft")
        model.open(profiles[1])
        assertEquals("", model.state.value.draft)
        assertEquals("", model.state.value.connection.conversation.reply)
        model.connect()
        await { model.state.value.connection.status == ConnectionStatus.PAIRED }
        assertNotEquals(one, vault.identity(profiles[1].endpoint).deviceId)
        model.draft("phone second unique marker")
        model.send()
        await {
            model.state.value.connection.conversation.reply.contains(
                "phone second unique marker"
            )
        }
        assertFalse(
            model.state.value.connection.conversation.reply.contains(
                "phone first unique marker"
            )
        )
        model.startRecording()
        await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
        Thread.sleep(900)
        model.finishRecording()
        await { model.state.value.connection.conversation.mode != ConversationMode.LISTENING }
        await { model.state.value.connection.conversation.reply.startsWith("I heard ") }
        await { model.state.value.speaking }
        model.stopPlayback()
        assertFalse(model.state.value.speaking)
        model.hide()
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.speaking)
        assertEquals(ConnectionStatus.OFFLINE, model.state.value.connection.status)
        assertEquals(relay, RelaySettings.enabled(context))
        model.open(profiles[0])
        assertEquals("keep first draft", model.state.value.draft)
        assertTrue(
            model.state.value.connection.conversation.reply.contains(
                "phone first unique marker"
            )
        )
        model.connect()
        await { model.state.value.connection.status == ConnectionStatus.PAIRED }
        assertEquals(one, vault.identity(profiles[0].endpoint).deviceId)
        model.startRecording()
        await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
        model.hide()
        Thread.sleep(250)
        assertEquals(ConnectionStatus.OFFLINE, model.state.value.connection.status)
        assertEquals(relay, RelaySettings.enabled(context))
    }

    @Test fun completionCanOverlapHideWithoutLockInversion() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fixture(factory = { context, interrupted, level, finished ->
            dev.quantumink.hermesgadget.client.PcmPlayback(
                context,
                onInterrupted = interrupted,
                onLevel = level,
                onFinished = {
                    if (Thread.currentThread().name == "gadget-speaker" &&
                        once.compareAndSet(false, true)
                    ) {
                        entered.countDown()
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    finished()
                }
            )
        }, skipHide = { blocked.get() }) { model, profiles, _, _ ->
            model.open(profiles[0])
            model.connect()
            await { model.state.value.connection.status == ConnectionStatus.PAIRED }
            model.startRecording()
            await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
            Thread.sleep(900)
            model.finishRecording()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val hide = Thread({ model.hide() }, "fixture-phone-hide").apply {
                isDaemon = true
                start()
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (hide.state != Thread.State.BLOCKED && hide.isAlive &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(10)
            }
            try {
                assertEquals(Thread.State.BLOCKED, hide.state)
                release.countDown()
                hide.join(1500)
                blocked.set(hide.isAlive)
                assertFalse("Playback completion and hide deadlocked", hide.isAlive)
                assertFalse(model.state.value.visible)
            } finally {
                release.countDown()
                blocked.set(hide.isAlive)
            }
        }
    }

    @Test fun realPcmLevelsAreBoundedAndOldStreamsCannotAnimateANewSession() {
        val callbacks = java.util.concurrent.CopyOnWriteArrayList<(Float) -> Unit>()
        val completions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        val peaks = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val displayed = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val reference = java.util.concurrent.atomic.AtomicReference<PhoneConversation>()
        fixture(factory = { context, interrupted, level, finished ->
            callbacks.add(level)
            completions.add(finished)
            dev.quantumink.hermesgadget.client.PcmPlayback(
                context,
                onInterrupted = interrupted,
                onLevel = { value ->
                    peaks.add(value)
                    level(value)
                    reference.get()?.let { displayed.add(it.state.value.playbackLevel) }
                },
                onFinished = finished
            )
        }) { model, profiles, _, _ ->
            reference.set(model)
            model.open(profiles[0])
            model.connect()
            await { model.state.value.connection.status == ConnectionStatus.PAIRED }
            model.draft("Synthetic nonzero PCM tone")
            model.send()
            await { peaks.any { it > 0f } }
            assertTrue(peaks.all { it in 0f..1f })
            assertTrue("Real PCM must reach presentation state", displayed.any { it > 0f })
            await { !model.state.value.speaking }
            assertEquals(0f, model.state.value.playbackLevel)
            val old = callbacks.first()
            model.open(profiles[1])
            old(.9f)
            completions[0]()
            assertEquals(0f, model.state.value.playbackLevel)
            assertFalse(model.state.value.speaking)
            model.connect()
            await { model.state.value.connection.status == ConnectionStatus.PAIRED }
            model.draft("Second synthetic tone")
            model.send()
            await { model.state.value.speaking }
            val version = model.state.value.playbackVersion
            old(.9f)
            assertTrue(model.state.value.playbackLevel < .9f)
            completions[0]()
            assertEquals(version, model.state.value.playbackVersion)
            assertTrue(model.state.value.speaking)
            model.stopPlayback()
            callbacks.last()(1f)
            assertFalse(model.state.value.speaking)
            assertEquals(0f, model.state.value.playbackLevel)
            model.hide()
            callbacks.last()(1f)
            assertEquals(0f, model.state.value.playbackLevel)
            assertEquals(0f, model.state.value.connection.conversation.level)
            val manager = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(android.media.AudioManager::class.java)
            model.open(profiles[0])
            assertEquals(
                manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0 ||
                    manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC),
                model.state.value.mediaMuted
            )
        }
    }

    @Test fun nativeComposerTalkCancelStopAndBackUseTheForegroundSession() = fixture {
            model,
            profiles,
            _,
            _
        ->
        NativePhoneFixture().use { ui ->
            model.open(profiles[0])
            model.connect()
            await { model.state.value.connection.status == ConnectionStatus.PAIRED }
            ui.show(back = model::hide) { modifier ->
                ConversationPage(
                    model,
                    null,
                    model::connect,
                    model::startRecording,
                    model::hide,
                    modifier
                )
            }
            ui.enter("Native composer marker")
            ui.click("Send message", description = true)
            await {
                model.state.value.connection.conversation.reply.contains("Native composer marker")
            }
            await { model.state.value.draft.isEmpty() }
            ui.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { !model.state.value.speaking }
            ui.await { ui.find("Hold to record", description = true) != null }
            val talkBounds = android.graphics.Rect().also {
                requireNotNull(ui.find("Hold to record", description = true)).getBoundsInScreen(it)
            }
            val dragDistance = 80f * ui.context.resources.displayMetrics.density
            val downAt = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float) {
                val event = android.view.MotionEvent.obtain(
                    downAt,
                    android.os.SystemClock.uptimeMillis(),
                    action,
                    x,
                    talkBounds.exactCenterY(),
                    0
                ).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    assertTrue(ui.instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, talkBounds.exactCenterX())
            await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
            touch(android.view.MotionEvent.ACTION_MOVE, talkBounds.exactCenterX() - dragDistance)
            touch(android.view.MotionEvent.ACTION_UP, talkBounds.exactCenterX() - dragDistance)
            await { model.state.value.connection.conversation.mode != ConversationMode.LISTENING }
            ui.click("Hold to record", description = true)
            await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
            ui.click("Cancel recording", description = true)
            await { model.state.value.connection.conversation.mode != ConversationMode.LISTENING }
            assertEquals(0f, model.state.value.connection.conversation.level)
            ui.click("Hold to record", description = true)
            await { model.state.value.connection.conversation.mode == ConversationMode.LISTENING }
            Thread.sleep(1500)
            ui.click("Finish and send recording", description = true)
            await { model.state.value.speaking }
            ui.click("Stop voice")
            assertFalse(model.state.value.speaking)
            assertEquals(0f, model.state.value.playbackLevel)
            model.draft("Back retains this draft")
            ui.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { !model.state.value.visible }
            model.open(profiles[0])
            assertEquals("Back retains this draft", model.state.value.draft)
        }
    }

    @Test fun permissionCompletionReturnsAccessibleRecordingToIdleWithoutCapture() = fixture {
            model,
            profiles,
            _,
            _
        ->
        NativePhoneFixture().use { ui ->
            model.open(profiles[0])
            model.connect()
            await { model.state.value.connection.status == ConnectionStatus.PAIRED }
            var result = "Microphone ready. Hold to talk again."
            ui.show { modifier ->
                ConversationPage(
                    model,
                    null,
                    model::connect,
                    { model.recordingUnavailable(result) },
                    model::hide,
                    modifier
                )
            }
            for (message in listOf(
                "Microphone ready. Hold to talk again.",
                "Microphone permission was declined."
            )) {
                result = message
                val before = model.state.value.recordingEndVersion
                ui.click("Hold to record", true)
                await { model.state.value.recordingEndVersion > before }
                ui.await { ui.find(message) != null && ui.find("Hold to record", true) != null }
                assertEquals(ConversationMode.READY, model.state.value.connection.conversation.mode)
                assertEquals(null, model.state.value.recordingStartedAt)
                assertEquals(null, ui.find("Finish and send recording", true))
            }
            ui.enter("Draft after permission completion")
            assertEquals("Draft after permission completion", model.state.value.draft)
            ui.click("Send message", true)
            await {
                model.state.value.connection.conversation.reply.contains(
                    "Draft after permission completion"
                )
            }
        }
    }

    private fun fixture(
        factory: (
            (
                android.content.Context,
                () -> Unit,
                (Float) -> Unit,
                () -> Unit
            ) -> dev.quantumink.hermesgadget.client.PcmPlayback
        )? = null,
        skipHide: () -> Boolean = { false },
        body: (PhoneConversation, List<EndpointProfile>, ConversationVault, File) -> Unit
    ) {
        assumeTrue(Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.containsKey("phonePort1") && arguments.containsKey("phoneCertificate"))
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        val certificate = CertificateFactory.getInstance(
            "X.509"
        ).generateCertificate(
            ByteArrayInputStream(
                Base64.getDecoder().decode(arguments.getString("phoneCertificate"))
            )
        ) as X509Certificate
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(
            trust.sslSocketFactory(),
            trust.trustManager
        ).build()
        val directory = File(context.cacheDir, "phone-session-" + UUID.randomUUID())
        val alias = "test.phone.session." + UUID.randomUUID()
        val vault = ConversationVault(context, directory, alias)
        val model = if (factory == null) {
            PhoneConversation(context.applicationContext as Application, vault, client)
        } else {
            PhoneConversation(context.applicationContext as Application, vault, client, factory)
        }
        val profiles = (1..2).map { index ->
            EndpointProfile(
                Endpoint.parse(
                    "wss://localhost:" + arguments.getString("phonePort$index") + "/gadget",
                    true
                ),
                "Synthetic phone $index",
                ""
            )
        }
        try {
            body(model, profiles, vault, directory)
        } finally {
            if (!skipHide()) model.hide()
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < end && !condition()) Thread.sleep(20)
        assertTrue("Expected phone session state was not reached", condition())
    }
}
