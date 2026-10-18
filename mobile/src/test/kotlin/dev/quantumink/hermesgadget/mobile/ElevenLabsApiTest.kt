package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.SpeechRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ElevenLabsApiTest {
    private fun fixture(block: (MockWebServer, ElevenLabsApi) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName(
            "Synthetic speech fixture"
        ).addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(
            certificate.certificate
        ).build()
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(
                clientTrust.sslSocketFactory(),
                clientTrust.trustManager
            ).build()
            ElevenLabsApi(server.url("/"), client).use { block(server, it) }
        }
    }
    private fun request(format: String) = SpeechRequest(
        ClientSpeech.endpointId(Endpoint.parse("wss://example.invalid/gadget")),
        "synthetic-request",
        "An original synthetic reply.",
        "syntheticVoice",
        format
    )

    @Test fun quotaAndPcmStreamingUseTheKeyOnlyInTheProviderHeader() = fixture { server, api ->
        val key = UUID.randomUUID().toString()
        server.enqueue(MockResponse().setBody("""{"character_count":120,"character_limit":1000}"""))
        assertEquals(880L, api.quota(key).remaining)
        assertEquals(key, server.takeRequest().getHeader("xi-api-key"))
        val pcm = ByteArray(6400) { (it % 100).toByte() }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm))
        )
        val output = ByteArrayOutputStream()
        api.stream(SpeechSettings(SpeechMode.PROFILE, key, ""), request("pcm16"), output, {})
        val provider = server.takeRequest()
        assertEquals("pcm_16000", provider.requestUrl?.queryParameter("output_format"))
        assertEquals(key, provider.getHeader("xi-api-key"))
        assertTrue(provider.body.readUtf8().contains(request("pcm16").text))
        assertFalse(output.toString(Charsets.UTF_8).contains(key))
        val received = ByteArrayOutputStream()
        ClientSpeech.receive(ByteArrayInputStream(output.toByteArray()), "pcm16", {
        }, received::write)
        assertArrayEquals(pcm, received.toByteArray())
    }

    @Test fun invalidPcmCannotStartPlayback() = fixture { server, api ->
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1))) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "audio/pcm")
                    .setBody(Buffer().write(bytes))
            )
            val output = ByteArrayOutputStream()
            assertThrows(Exception::class.java) {
                api.stream(
                    SpeechSettings(SpeechMode.PROFILE, "synthetic-key", ""),
                    request("pcm16"),
                    output,
                    {}
                )
            }
            assertEquals("Invalid PCM must not start a native player", 0, output.size())
        }
    }

    @Test fun closingSpeechCannotShutDownTheInjectedSocketClient() {
        val client = OkHttpClient()
        ElevenLabsApi(client = client).close()
        assertFalse(
            "Speech must own its dispatcher independently of foreground sockets",
            client.dispatcher.executorService.isShutdown
        )
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun oversizedPcmCannotStartPlayback() = fixture { server, api ->
        server.enqueue(
            MockResponse().setHeader(
                "Content-Type",
                "audio/pcm"
            ).setBody(Buffer().write(ByteArray(1920002)))
        )
        assertThrows(Exception::class.java) {
            api.stream(
                SpeechSettings(SpeechMode.PROFILE, "synthetic-key", ""),
                request("pcm16"),
                object : SpeechSink {
                    override fun start(format: String, header: ByteArray?) {
                        fail("Oversized response started playback")
                    }
                    override fun packet(bytes: ByteArray) = Unit
                    override fun finish() = Unit
                },
                {}
            )
        }
    }

    @Test fun typedAndFramedConsumersReceiveIdenticalValidatedOpus() = fixture { server, api ->
        val ogg = requireNotNull(javaClass.getResourceAsStream("/client-speech.ogg")).readBytes()
        repeat(2) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "audio/ogg").setBody(Buffer().write(ogg))
            )
        }
        val framed = ByteArrayOutputStream()
        val settings = SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "")
        api.stream(settings, request("opus"), framed, {})
        var expectedHeader: ByteArray? = null
        val expectedPackets = ArrayList<ByteArray>()
        ClientSpeech.receive(ByteArrayInputStream(framed.toByteArray()), "opus", {
            expectedHeader =
                it
        }, expectedPackets::add)
        var receivedHeader: ByteArray? = null
        var ended = false
        val packets = ArrayList<ByteArray>()
        api.stream(
            settings,
            request("opus"),
            object : SpeechSink {
                override fun start(format: String, header: ByteArray?) {
                    assertEquals("opus", format)
                    receivedHeader =
                        header
                }
                override fun packet(bytes: ByteArray) {
                    packets.add(bytes)
                }
                override fun finish() {
                    ended = true
                }
            },
            {}
        )
        assertArrayEquals(expectedHeader, receivedHeader)
        assertEquals(31, packets.size)
        expectedPackets.zip(packets).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertTrue(ended)
        assertEquals(2, server.requestCount)
    }

    @Test fun typedSinkRejectsProviderErrorsRedirectsAndMalformedMediaWithoutRetry() = fixture {
            server,
            api
        ->
        val settings = SpeechSettings(SpeechMode.PROFILE, "synthetic-key", "")
        val cases = listOf(
            MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(429),
            MockResponse().setResponseCode(
                307
            ).setHeader("Location", "https://example.invalid/must-not-follow"),
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}"),
            MockResponse().setHeader("Content-Type", "audio/pcm").setBody("x"),
            MockResponse().setHeader("Content-Type", "audio/pcm").setBody(""),
            MockResponse().setHeader(
                "Content-Type",
                "audio/pcm"
            ).setBody(Buffer().write(ByteArray(1920002)))
        )
        var finished = 0
        for (response in cases) {
            server.enqueue(response)
            assertThrows(Exception::class.java) {
                api.stream(
                    settings,
                    request("pcm16"),
                    object : SpeechSink {
                        override fun start(format: String, header: ByteArray?) = Unit
                        override fun packet(bytes: ByteArray) = Unit
                        override fun finish() {
                            finished++
                        }
                    },
                    {}
                )
            }
        }
        for (bytes in listOf(
            byteArrayOf(),
            "invalid ogg".toByteArray(),
            ByteArray(4 * 1024 * 1024 + 1)
        )) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "audio/ogg").setBody(Buffer().write(bytes))
            )
            assertThrows(Exception::class.java) {
                api.stream(
                    settings,
                    request("opus"),
                    object : SpeechSink {
                        override fun start(format: String, header: ByteArray?) = Unit
                        override fun packet(bytes: ByteArray) = Unit
                        override fun finish() {
                            finished++
                        }
                    },
                    {}
                )
            }
        }
        assertEquals(0, finished)
        assertEquals(11, server.requestCount)
    }

    @Test fun nativeOpusPacketsAreValidatedAndPacedInsteadOfSendingOggPages() = fixture {
            server,
            api
        ->
        val ogg = requireNotNull(javaClass.getResourceAsStream("/client-speech.ogg")).readBytes()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "audio/ogg").setBody(Buffer().write(ogg))
        )
        val output = ByteArrayOutputStream()
        val started = System.nanoTime()
        api.stream(
            SpeechSettings(
                SpeechMode.SHARED,
                UUID.randomUUID().toString(),
                "syntheticOverride"
            ),
            request("opus"),
            output,
            {
            }
        )
        val provider = server.takeRequest()
        assertEquals("opus_48000_32", provider.requestUrl?.queryParameter("output_format"))
        assertTrue(provider.path.orEmpty().contains("syntheticOverride"))
        var count = 0
        ClientSpeech.receive(ByteArrayInputStream(output.toByteArray()), "opus", {
            assertEquals(19, it?.size)
        }, { count++ })
        assertEquals(31, count)
        assertTrue(System.nanoTime() - started >= 350000000)
    }

    @Test fun permissionFailureRedirectAndCancellationNeverRetrySynthesis() = fixture {
            server,
            api
        ->
        val settings =
            SpeechSettings(SpeechMode.PROFILE, UUID.randomUUID().toString(), "syntheticVoice")
        server.enqueue(MockResponse().setResponseCode(403))
        val first = ByteArrayOutputStream()
        assertThrows(SpeechFailure::class.java) {
            api.stream(settings, request("pcm16"), first, {})
        }
        assertEquals(0, first.size())
        server.enqueue(
            MockResponse().setResponseCode(
                302
            ).setHeader("Location", server.url("/must-not-follow"))
        )
        assertThrows(SpeechFailure::class.java) {
            api.stream(settings, request("pcm16"), ByteArrayOutputStream(), {})
        }
        assertEquals(2, server.requestCount)
        assertThrows(java.io.IOException::class.java) {
            api.stream(settings, request("pcm16"), ByteArrayOutputStream()) { it.cancel() }
        }
        assertEquals(2, server.requestCount)
    }
}
