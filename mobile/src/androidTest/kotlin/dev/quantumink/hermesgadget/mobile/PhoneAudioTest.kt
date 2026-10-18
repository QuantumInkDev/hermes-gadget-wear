package dev.quantumink.hermesgadget.mobile

import android.app.Application
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAudioTest {
    @Test fun pendingVoiceStopKeepsTextAndDraftWithoutLatePlayback() = fixture { f ->
        val voice = "pendingUiVoice"
        val message = "Synthetic pending UI speech"
        val reply = "You said: " + message
        val draft = "Unsent synthetic draft"
        try {
            NativePhoneFixture().use { ui ->
                f.settings.set(SpeechSettings(SpeechMode.PROFILE, "synthetic-key", voice))
                f.model.open(f.profiles[1])
                f.model.connect()
                await {
                    f.model.state.value.connection.status ==
                        dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
                }
                ui.show(back = f.model::hide) { modifier ->
                    ConversationPage(
                        f.model,
                        null,
                        f.model::connect,
                        f.model::startRecording,
                        f.model::hide,
                        modifier
                    )
                }
                assertEquals(0, f.count(voice))
                f.model.draft(message)
                f.model.send()
                await {
                    f.count(voice) == 1 && f.model.state.value.preparingSpeech &&
                        !f.model.state.value.connection.conversation.busy &&
                        !f.model.state.value.sending
                }
                f.model.draft(draft)
                ui.await { ui.find(reply) != null && ui.find(draft) != null }
                assertTrue(
                    "Stop voice must remain available after the server turn ends",
                    ui.find("Stop voice") != null
                )
                assertTrue(
                    "Pending synthesis must be labelled honestly",
                    ui.find("Preparing voice") != null
                )
                assertEquals(null, ui.find("Speaking"))
                assertFalse(f.model.state.value.speaking)
                assertTrue(f.peaks.isEmpty())
                assertEquals(0, f.playbackStarts.get())
                ui.click("Stop voice")
                assertFalse(f.model.state.value.preparingSpeech)
                assertFalse(f.model.state.value.speaking)
                f.provider("release", voice)
                await { f.provider("completed", voice) == 1 }
                Thread.sleep(350)
                ui.await { ui.find("Preparing voice") == null && ui.find("Stop voice") == null }
                assertTrue(f.peaks.isEmpty())
                assertEquals(0, f.playbackStarts.get())
                assertEquals(0f, f.model.state.value.playbackLevel)
                assertFalse(f.model.state.value.speaking)
                assertFalse(f.model.state.value.preparingSpeech)
                assertEquals(draft, f.model.state.value.draft)
                assertEquals(reply, f.model.state.value.connection.conversation.reply)
                assertTrue(ui.find(draft) != null && ui.find(reply) != null)
                assertEquals(1, f.count(voice))
            }
        } finally {
            f.provider("release", voice)
        }
    }

    @Test fun stockServerIgnoresClientOfferAndStillPlaysNativePcm() = fixture { f ->
        f.settings.set(SpeechSettings(SpeechMode.SHARED, "synthetic-key", "stockVoice"))
        f.model.open(f.profiles[0])
        f.model.connect()
        await {
            f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        assertFalse(f.model.state.value.connection.clientSpeech)
        assertEquals("pcm16", f.model.state.value.connection.conversation.microphoneFormat)
        assertEquals("pcm16", f.model.state.value.connection.conversation.speakerFormat)
        f.model.draft("Synthetic stock audio")
        f.model.send()
        await { f.peaks.any { it > 0f } }
        await { !f.model.state.value.speaking }
        assertEquals(0, f.count("stockVoice"))
    }

    @Test fun optedInSpeechUsesNativeWaveformDrainsOnceAndNeverReplaysOnReconnect() = fixture { f ->
        f.settings.set(SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "nativeVoice"))
        f.model.open(f.profiles[1])
        f.model.connect()
        await {
            f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        assertTrue(f.model.state.value.connection.clientSpeech)
        assertEquals(0, f.count("nativeVoice"))
        f.model.draft("Synthetic foreground speech")
        f.model.send()
        await { f.peaks.any { it > 0f } }
        assertTrue(f.model.state.value.speaking)
        assertFalse(f.model.state.value.preparingSpeech)
        await { !f.model.state.value.speaking }
        assertEquals(0f, f.model.state.value.playbackLevel)
        assertEquals(1, f.count("nativeVoice"))
        f.model.draft("Preserved draft")
        f.model.hide()
        f.model.open(f.profiles[1])
        f.model.connect()
        await {
            f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        Thread.sleep(250)
        assertEquals(1, f.count("nativeVoice"))
        assertEquals("Preserved draft", f.model.state.value.draft)
        assertTrue(
            f.model.state.value.connection.conversation.reply.contains(
                "Synthetic foreground speech"
            )
        )
    }

    @Test fun stopPendingSpeechAndOldProfileResponseCannotStartNativeAudio() = fixture { f ->
        f.settings.set(SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "delayedVoice"))
        f.model.open(f.profiles[1])
        f.model.connect()
        await {
            f.model.state.value.connection.clientSpeech &&
                f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        f.model.draft("Synthetic delayed response")
        f.model.send()
        await { f.count("delayedVoice") == 1 }
        assertTrue(f.model.state.value.preparingSpeech)
        assertFalse(f.model.state.value.speaking)
        f.model.stopPlayback()
        assertFalse(f.model.state.value.preparingSpeech)
        f.model.open(f.profiles[0])
        Thread.sleep(2300)
        assertTrue(f.peaks.isEmpty())
        assertFalse(f.model.state.value.speaking)
        assertEquals(1, f.count("delayedVoice"))
    }

    @Test fun changedOptInAndProviderErrorRetainDraftAndFullAnswer() = fixture { f ->
        f.settings.set(SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "rejectVoice"))
        f.model.open(f.profiles[1])
        f.model.connect()
        await {
            f.model.state.value.connection.clientSpeech &&
                f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        f.model.draft("Synthetic rejected speech")
        f.model.send()
        await { f.model.state.value.error.contains("Key rejected") }
        assertEquals(1, f.count("rejectVoice"))
        assertTrue(
            f.model.state.value.connection.conversation.reply.contains("Synthetic rejected speech")
        )
        f.model.draft("Keep this unsent draft")
        assertEquals("Keep this unsent draft", f.model.state.value.draft)
        f.settings.set(SpeechSettings(SpeechMode.SERVER, "", ""))
        await { !f.model.state.value.connection.conversation.busy }
        f.model.draft("Synthetic removed opt in")
        f.model.send()
        await { f.model.state.value.error.contains("no longer enabled") }
        assertEquals(1, f.count("rejectVoice"))
        assertTrue(f.peaks.isEmpty())
    }

    @Test fun actualCodecProbeAndNegotiatedCaptureUseRealPhoneAdapters() = fixture { f ->
        val supported = dev.quantumink.hermesgadget.client.OpusSupport.probe()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("phoneOpusRuntimeSupported", supported.toString())
            }
        )
        // Actual MediaCodec encode/decode; absence means PCM coverage, never Opus acceptance.
        f.probe.set(supported)
        f.model.open(f.profiles[1])
        f.model.connect()
        await {
            f.model.state.value.connection.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
        }
        assertEquals(
            if (supported) "opus" else "pcm16",
            f.model.state.value.connection.conversation.microphoneFormat
        )
        f.model.startRecording()
        await { f.model.state.value.recordingStartedAt != null }
        Thread.sleep(650)
        f.model.finishRecording()
        await { f.model.state.value.speaking }
        await { f.model.state.value.connection.conversation.reply.startsWith("I heard ") }
        await { !f.model.state.value.connection.conversation.busy && !f.model.state.value.speaking }
        assertEquals("", f.model.state.value.error)
        assertEquals(null, f.model.state.value.recordingStartedAt)
        assertTrue(f.model.state.value.recordingEndVersion > 0)
    }

    @Test fun actualVaultSharedProfileAndCorruptionStayLocalAndNonDestructive() = fixture { f ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(
            context.cacheDir,
            "phone-speech-" + java.util.UUID.randomUUID()
        )
        val alias = "test.phone.speech." + java.util.UUID.randomUUID()
        val tts = TtsVault(context, directory, alias)
        val endpoint = dev.quantumink.hermesgadget.protocol.ClientSpeech.endpointId(
            f.profiles[1].endpoint
        )
        f.reader.set(tts::selected)
        try {
            tts.save(
                "shared",
                SpeechSettings(SpeechMode.SHARED, "synthetic-shared-key", "sharedNativeVoice")
            )
            tts.save(endpoint, SpeechSettings(SpeechMode.SHARED, "", ""))
            f.model.open(f.profiles[1])
            f.model.connect()
            await {
                f.model.state.value.connection.status ==
                    dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
            }
            assertTrue(f.model.state.value.connection.clientSpeech)
            f.model.draft("Synthetic shared selection")
            f.model.send()
            await {
                f.count("sharedNativeVoice") == 1 && !f.model.state.value.preparingSpeech &&
                    !f.model.state.value.speaking &&
                    !f.model.state.value.connection.conversation.busy
            }
            tts.save(
                endpoint,
                SpeechSettings(SpeechMode.PROFILE, "synthetic-profile-key", "profileNativeVoice")
            )
            assertEquals(0, f.count("profileNativeVoice"))
            f.model.draft("Synthetic profile override")
            f.model.send()
            await {
                f.count("profileNativeVoice") == 1 && !f.model.state.value.preparingSpeech &&
                    !f.model.state.value.speaking &&
                    !f.model.state.value.connection.conversation.busy
            }
            f.model.hide()
            val record = java.io.File(directory, endpoint + ".sealed")
            record.writeBytes(
                record.readBytes().apply {
                    this[lastIndex] =
                        (this[lastIndex].toInt() xor 1).toByte()
                }
            )
            val corrupt = record.readBytes()
            f.model.open(f.profiles[1])
            f.model.connect()
            await {
                f.model.state.value.connection.status ==
                    dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
            }
            assertFalse(f.model.state.value.connection.clientSpeech)
            assertTrue(f.model.state.value.error.contains("saved settings preserved"))
            assertArrayEquals(corrupt, record.readBytes())
            assertEquals(1, f.count("sharedNativeVoice"))
            assertEquals(1, f.count("profileNativeVoice"))
        } finally {
            f.model.hide()
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test fun replacementConnectDiscardsOldProbeCapabilities() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        f.prober.set {
            if (first.getAndSet(false)) {
                entered.countDown()
                while (release.count >
                    0
                ) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) { }
                }
                true
            } else {
                false
            }
        }
        try {
            f.model.open(f.profiles[1])
            f.model.connect()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            f.model.connect()
            release.countDown()
            await {
                f.model.state.value.connection.status ==
                    dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
            }
            assertEquals("pcm16", f.model.state.value.connection.conversation.microphoneFormat)
            assertEquals("pcm16", f.model.state.value.connection.conversation.speakerFormat)
            f.model.startRecording()
            await { f.model.state.value.recordingStartedAt != null }
            Thread.sleep(650)
            f.model.finishRecording()
            await { f.model.state.value.connection.conversation.reply.startsWith("I heard ") }
            await { !f.model.state.value.connection.conversation.busy }
            assertEquals(null, f.model.state.value.recordingStartedAt)
            assertEquals("", f.model.state.value.error)
        } finally {
            release.countDown()
        }
    }

    private class Fixture(
        val model: PhoneConversation,
        val profiles: List<EndpointProfile>,
        val settings: java.util.concurrent.atomic.AtomicReference<SpeechSettings>,
        val probe: java.util.concurrent.atomic.AtomicBoolean,
        val peaks: java.util.concurrent.CopyOnWriteArrayList<Float>,
        val count: (String) -> Int,
        val reader: java.util.concurrent.atomic.AtomicReference<((String) -> SpeechSettings)?>,
        val prober: java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>,
        val provider: (String, String) -> Int,
        val playbackStarts: java.util.concurrent.atomic.AtomicInteger
    )
    private fun fixture(body: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(
            "Requires isolated TLS fixture",
            args.containsKey("providerPort")
        )
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >=
            37
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        context.startActivity(
            android.content.Intent(
                context,
                MainActivity::class.java
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        val cert = java.security.cert.CertificateFactory.getInstance(
            "X.509"
        ).generateCertificate(
            java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(args.getString("phoneCertificate"))
            )
        ) as java.security.cert.X509Certificate
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
        val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(
            trust.sslSocketFactory(),
            trust.trustManager
        ).build()
        val directory = java.io.File(context.cacheDir, "phone-audio-" + java.util.UUID.randomUUID())
        val alias = "test.phone.audio." + java.util.UUID.randomUUID()
        val vault = ConversationVault(context, directory, alias)
        val settings = java.util.concurrent.atomic.AtomicReference(
            SpeechSettings(SpeechMode.SERVER, "", "")
        )
        val probe = java.util.concurrent.atomic.AtomicBoolean(false)
        val peaks = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val playbackStarts = java.util.concurrent.atomic.AtomicInteger()
        val provider = okhttp3.HttpUrl.Builder().scheme(
            "https"
        ).host("localhost").port(requireNotNull(args.getString("providerPort")).toInt()).build()
        val reader = java.util.concurrent.atomic.AtomicReference<
            (
                (
                    String
                ) -> SpeechSettings
            )?
            >(null)
        val prober = java.util.concurrent.atomic.AtomicReference<(() -> Boolean)?>(null)
        val model = PhoneConversation(
            context.applicationContext as Application,
            vault,
            client,
            { ctx, interrupted, level, finished ->
                playbackStarts.incrementAndGet()
                dev.quantumink.hermesgadget.client.PcmPlayback(
                    ctx,
                    interrupted,
                    { value ->
                        if (value > 0f) peaks.add(value)
                        level(value)
                    },
                    finished
                )
            },
            opusProbe = { prober.get()?.invoke() ?: probe.get() },
            selectedSpeech = {
                reader.get()?.invoke(it)
                    ?: settings.get()
            },
            speechApi = { ElevenLabsApi(provider, client) }
        )
        val profiles = (1..2).map {
            EndpointProfile(
                Endpoint.parse(
                    "wss://localhost:" + args.getString("phonePort$it") + "/gadget",
                    true
                ),
                "Synthetic profile $it",
                ""
            )
        }
        try {
            body(
                Fixture(model, profiles, settings, probe, peaks, { voice ->
                    client.newCall(
                        okhttp3.Request.Builder().url(
                            provider.newBuilder().addPathSegment(
                                "count"
                            ).addPathSegment(voice).build()
                        ).build()
                    ).execute().use {
                        requireNotNull(it.body).string().toInt()
                    }
                }, reader, prober, { route, voice ->
                    client.newCall(
                        okhttp3.Request.Builder().url(
                            provider.newBuilder()
                                .addPathSegment(route).addPathSegment(voice).build()
                        ).build()
                    ).execute().use {
                        requireNotNull(it.body).string().toInt()
                    }
                }, playbackStarts)
            )
        } finally {
            model.hide()
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline && !condition()) Thread.sleep(20)
        assertTrue("Expected audio state did not arrive", condition())
    }

    @Test fun connectPreparationIsOffMainAndCannotPublishAfterHideOrReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val offMain = java.util.concurrent.atomic.AtomicBoolean(false)
        val model = PhoneConversation(
            context.applicationContext as Application,
            opusProbe = {
                offMain.set(Looper.myLooper() != Looper.getMainLooper())
                entered.countDown()
                while (release.count >
                    0
                ) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) { }
                }
                finished.countDown()
                true
            },
            selectedSpeech = { SpeechSettings(SpeechMode.SERVER, "", "") }
        )
        try {
            model.open(
                EndpointProfile(
                    Endpoint.parse("wss://example.invalid/gadget"),
                    "Synthetic first",
                    ""
                )
            )
            val returned = CountDownLatch(1)
            android.os.Handler(Looper.getMainLooper()).post {
                model.connect()
                returned.countDown()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val prompt = returned.await(300, TimeUnit.MILLISECONDS)
            // Always release a broken synchronous implementation so the test cannot wedge the UI.
            if (!prompt) release.countDown()
            assertTrue("Connect must return while codec preparation is pending", prompt)
            model.hide()
            model.open(
                EndpointProfile(
                    Endpoint.parse("wss://example.invalid/other"),
                    "Synthetic replacement",
                    ""
                )
            )
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertTrue(offMain.get())
            assertEquals("Synthetic replacement", model.state.value.profile?.label)
            assertEquals(
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.OFFLINE,
                model.state.value.connection.status
            )
        } finally {
            release.countDown()
            model.hide()
        }
    }
}
