package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.SpeechRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneClientSpeechTest {
    private fun fixture(body: (MockWebServer, ElevenLabsApi) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("Synthetic speech")
            .addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(
            certificate.certificate
        ).build()
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(
                    certificate
                ).build().sslSocketFactory(),
                false
            )
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(
                trust.sslSocketFactory(),
                trust.trustManager
            ).build()
            ElevenLabsApi(server.url("/"), client).use { body(server, it) }
        }
    }
    private fun request(id: String = "one", format: String = "pcm16") = SpeechRequest(
        "a".repeat(64),
        id,
        "Synthetic foreground reply",
        "serverVoice",
        format
    )
    private class Sink : SpeechSink {
        val done = CountDownLatch(1)
        var starts = 0
        var packets = 0
        var ended = false
        override fun start(format: String, header: ByteArray?) {
            starts++
        }
        override fun packet(bytes: ByteArray) {
            packets++
        }
        override fun finish() {
            ended = true
            done.countDown()
        }
    }

    @Test fun deduplicatesBeforeHttpAndUsesFreshSelectedSettings() = fixture { server, api ->
        server.enqueue(
            MockResponse().setHeader(
                "Content-Type",
                "audio/pcm"
            ).setBody(Buffer().write(ByteArray(640)))
        )
        val sink = Sink()
        PhoneClientSpeech(api).use { speech ->
            assertTrue(
                speech.speak(request(), {
                    SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "overrideVoice")
                }, sink) { fail(it) }
            )
            assertFalse(
                speech.speak(request(), {
                    error("Duplicate must not read credentials")
                }, sink) { fail(it) }
            )
            assertTrue(sink.done.await(5, TimeUnit.SECONDS))
            assertEquals(1, sink.starts)
            assertEquals(1, sink.packets)
            assertTrue(sink.ended)
            assertTrue(server.takeRequest().path.orEmpty().contains("overrideVoice"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun removedOptInDeniesSynthesisWithoutRetry() = fixture { server, api ->
        for (settings in listOf(
            SpeechSettings(SpeechMode.SERVER, "synthetic-key", ""),
            SpeechSettings(SpeechMode.PROFILE, "", "")
        )) {
            val failed = CountDownLatch(1)
            val sink = Sink()
            PhoneClientSpeech(api).use { speech ->
                speech.speak(request(), { settings }, sink) { failed.countDown() }
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertEquals(0, sink.starts)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancelDuringCredentialReadInvalidatesLateWork() = fixture { server, api ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val sink = Sink()
        PhoneClientSpeech(api).use { speech ->
            speech.speak(request(), {
                entered.countDown()
                while (release.count >
                    0
                ) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) { }
                }
                done.countDown()
                SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "")
            }, sink) { fail(it) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            speech.cancel()
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(0, server.requestCount)
            assertEquals(0, sink.starts)
        }
    }

    @Test fun cancelPendingResponseNeverStartsPlayerOrRetries() = fixture { server, api ->
        server.enqueue(
            MockResponse().setHeadersDelay(
                2,
                TimeUnit.SECONDS
            ).setBody(Buffer().write(ByteArray(640)))
        )
        val sink = Sink()
        PhoneClientSpeech(api).use { speech ->
            speech.speak(request(), {
                SpeechSettings(SpeechMode.SHARED, "synthetic-key", "")
            }, sink) { fail(it) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            speech.cancel()
            Thread.sleep(250)
            assertEquals(0, sink.starts)
            assertFalse(sink.ended)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancelResponseStopsPacketsWithoutRetryOrCompletion() = fixture {
            server,
            api
        ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/pcm")
                .setBody(
                    Buffer().write(ByteArray(32000))
                ).throttleBody(640, 100, TimeUnit.MILLISECONDS)
        )
        val first = CountDownLatch(1)
        val packets = java.util.concurrent.atomic.AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        PhoneClientSpeech(api).use { speech ->
            speech.speak(
                request(),
                { SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "") },
                object : SpeechSink {
                    override fun start(format: String, header: ByteArray?) = Unit
                    override fun packet(bytes: ByteArray) {
                        packets.incrementAndGet()
                        first.countDown()
                    }
                    override fun finish() {
                        finished.set(true)
                    }
                }
            ) { fail(it) }
            assertTrue(first.await(3, TimeUnit.SECONDS))
            speech.cancel()
            Thread.sleep(50)
            val stoppedAt = packets.get()
            Thread.sleep(300)
            assertEquals(stoppedAt, packets.get())
            assertFalse(finished.get())
            assertEquals(1, server.requestCount)
        }
    }
}
