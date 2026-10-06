package dev.quantumink.hermesgadget.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.LoopbackConnectBridge
import dev.quantumink.hermesgadget.protocol.RelayStreams
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayTlsTest {
    @Test
    fun androidOwnsTlsAndValidatesCertificateThroughRealLoopbackProxy() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val ssl = SSLContext.getInstance("TLS").apply {
            init(arrayOf(serverTrust.keyManager), arrayOf(serverTrust.trustManager), null)
        }
        val server = ssl.serverSocketFactory.createServerSocket(
            0,
            2,
            InetAddress.getLoopbackAddress()
        ) as SSLServerSocket
        val worker = Executors.newSingleThreadExecutor()
        val served = worker.submit {
            repeat(2) {
                runCatching {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        socket.startHandshake()
                        val input = socket.inputStream.bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        socket.outputStream.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n" +
                                    "Connection: close\r\n\r\nOK"
                                ).toByteArray()
                        )
                        socket.outputStream.flush()
                    }
                }
            }
        }
        val endpoint = Endpoint.parse("wss://localhost:" + server.localPort + "/gadget")
        try {
            LoopbackConnectBridge(endpoint) {
                RelayStreams.socket(Socket(InetAddress.getLoopbackAddress(), server.localPort))
            }.use { bridge ->
                val client = bridge.client()
                val request = Request.Builder().url(
                    "https://localhost:" + server.localPort + "/gadget"
                ).build()
                try {
                    assertThrows(SSLException::class.java) {
                        client.newCall(request).execute().close()
                    }
                    val trust = HandshakeCertificates.Builder().addTrustedCertificate(
                        certificate.certificate
                    ).build()
                    val trusted = client.newBuilder().sslSocketFactory(
                        trust.sslSocketFactory(),
                        trust.trustManager
                    ).build()
                    trusted.newCall(request).execute().use { assertEquals("OK", it.body?.string()) }
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
            served.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }
}
