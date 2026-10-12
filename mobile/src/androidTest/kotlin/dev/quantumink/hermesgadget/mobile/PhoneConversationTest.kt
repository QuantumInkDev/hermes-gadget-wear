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
        fixture(factory = { context, interrupted, finished ->
            dev.quantumink.hermesgadget.client.PcmPlayback(
                context,
                onInterrupted = interrupted,
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

    private fun fixture(
        factory: (
            (
                android.content.Context,
                () -> Unit,
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
