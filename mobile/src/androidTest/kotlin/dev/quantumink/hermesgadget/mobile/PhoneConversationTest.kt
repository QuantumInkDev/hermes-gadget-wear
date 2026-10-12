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
    @Test fun independentlyPairsChatsRecordsAndClosesWithoutChangingRelay() {
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
        val model = PhoneConversation(context.applicationContext as Application, vault, client)
        val relay = RelaySettings.enabled(context)
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
            Thread.sleep(400)
            model.finishRecording()
            await { model.state.value.connection.conversation.mode != ConversationMode.LISTENING }
            await { model.state.value.connection.conversation.reply.startsWith("I heard ") }
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
        } finally {
            model.hide()
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
