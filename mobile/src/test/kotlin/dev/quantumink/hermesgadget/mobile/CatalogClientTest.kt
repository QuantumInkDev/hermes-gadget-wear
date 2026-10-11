package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogClientTest {
    @Test fun sourceEditRejectsPendingOldConfigurationAndRefreshesTheNewOne() {
        val source = CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "old")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val oldUpdates = java.util.concurrent.CopyOnWriteArrayList<List<CatalogPage>>()
        val engine = CatalogDiscovery { current ->
            if (current.credential == "old") {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            listOf(CatalogProfile(current.credential, "Agent", null, null))
        }
        try {
            val old = engine.refresh(listOf(source), emptyList(), true) { oldUpdates.add(it) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            engine.invalidate()
            val fresh = engine.refresh(
                listOf(CatalogSource("a", "Box", source.url, "new")),
                emptyList(),
                true
            ) {}
            release.countDown()
            assertEquals("new", fresh.get(4, TimeUnit.SECONDS).single().profile.id)
            assertTrue(old.isCancelled)
            assertTrue(oldUpdates.isEmpty())
        } finally {
            release.countDown()
            engine.close()
        }
    }

    @Test fun emptySourceAuthFailureIsAttentionRatherThanAnEmptySuccessfulCatalog() {
        val source = CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "synthetic")
        val engine = CatalogDiscovery { throw CatalogFailure(CatalogStatus.ATTENTION) }
        try {
            assertTrue(
                engine.refresh(listOf(source), emptyList(), false) {
                }.get(3, TimeUnit.SECONDS).isEmpty()
            )
            assertEquals(CatalogStatus.ATTENTION, engine.statuses()["a"])
        } finally {
            engine.close()
        }
    }

    @Test fun tlsCatalogScopesCredentialAndRejectsRedirectsAuthAndOversizedBodies() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(
            certificate.certificate
        ).build()
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false)
            server.start()
            val source =
                CatalogSource(
                    "a",
                    "Box",
                    server.url("/catalog/v1").toString(),
                    "syntheticCatalogCredential"
                )
            val client =
                CatalogClient(
                    OkHttpClient.Builder().sslSocketFactory(
                        trust.sslSocketFactory(),
                        trust.trustManager
                    ).build()
                )
            server.enqueue(
                MockResponse().setBody("""{"version":1,"profiles":[{"id":"p","name":"Agent"}]}""")
            )
            assertEquals("Agent", client.fetch(source).single().name)
            assertEquals(
                "Bearer syntheticCatalogCredential",
                server.takeRequest().getHeader("Authorization")
            )
            server.enqueue(
                MockResponse().setResponseCode(
                    302
                ).setHeader("Location", "https://other.example.invalid/private")
            )
            assertEquals(
                CatalogStatus.ATTENTION,
                assertThrows(CatalogFailure::class.java) {
                    client.fetch(source)
                }.status
            )
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(
                CatalogStatus.ATTENTION,
                assertThrows(CatalogFailure::class.java) {
                    client.fetch(source)
                }.status
            )
            server.enqueue(MockResponse().setBody(" ".repeat(65537)))
            assertEquals(
                CatalogStatus.ATTENTION,
                assertThrows(CatalogFailure::class.java) {
                    client.fetch(source)
                }.status
            )
            val data = "synthetic artwork bytes".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                "%02x".format(it)
            }
            server.enqueue(MockResponse().setBody("wrong bytes"))
            assertThrows(CatalogFailure::class.java) {
                client.portrait(source, PortraitDescriptor(hash, data.size, "/assets/$hash.png"))
            }
        }
    }

    @Test fun independentRefreshPublishesFastSourceBeforeSlowFailureAndRetainsCache() {
        val sources =
            listOf(
                CatalogSource("a", "First", "https://a.example.invalid/catalog/v1", "synthetic"),
                CatalogSource("b", "Second", "https://b.example.invalid/catalog/v1", "synthetic")
            )
        val previous =
            listOf(
                CatalogPage(
                    "b",
                    "Second",
                    CatalogProfile("remembered", "Agent", null, null),
                    CatalogStatus.AVAILABLE,
                    1
                )
            )
        val updates = java.util.concurrent.CopyOnWriteArrayList<List<CatalogPage>>()
        var calls = 0
        val engine = CatalogDiscovery { source ->
            synchronized(this) { calls++ }
            if (source.id == "b") {
                Thread.sleep(200)
                throw CatalogFailure(CatalogStatus.OFFLINE)
            }
            listOf(CatalogProfile("fresh", "Agent", null, null))
        }
        try {
            val future = engine.refresh(sources, previous, false) { updates.add(it) }
            val same = engine.refresh(sources, previous, false) {}
            assertTrue(future === same)
            val pages = future.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("b:remembered", "a:fresh"), pages.map { it.key })
            assertEquals(CatalogStatus.OFFLINE, pages.first().status)
            assertTrue(updates.first().any { it.key == "a:fresh" })
            assertEquals(CatalogStatus.AVAILABLE, updates.first().first().status)
            engine.refresh(sources, previous, false) {}.get(3, TimeUnit.SECONDS)
            assertEquals(2, calls)
            engine.refresh(sources, pages, true) {}.get(3, TimeUnit.SECONDS)
            assertEquals(4, calls)
        } finally {
            engine.close()
        }
    }
}
