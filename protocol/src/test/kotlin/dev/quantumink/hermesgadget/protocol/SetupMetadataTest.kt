package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupMetadataTest {
    private val now = 1792281600L
    private val url = "wss://gadget.example.test:8443/socket"
    private fun metadata(): SetupMetadata = SetupMetadataCodec.create("Synthetic", url, now)
    private fun reject(text: String, time: Long = now) {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SetupMetadataCodec.decode(text, time)
            }
        assertEquals("Invalid or expired setup metadata.", error.message)
        assertEquals(null, error.cause)
    }

    @Test fun metadataRoundtripAndPrivateConnectionPreview() {
        val value = metadata()
        val text = SetupMetadataCodec.encode(value)
        val review = SetupMetadataCodec.decode(text, now)
        assertEquals(value, review.metadata)
        assertEquals(text, SetupMetadataCodec.encode(review.metadata))
        assertEquals("wss", review.endpoint!!.scheme)
        assertEquals("gadget.example.test", review.endpoint.host)
        assertEquals(8443, review.endpoint.port)
        assertEquals("/socket", review.endpoint.path)
        assertFalse(review.endpoint.requiresCleartextReview)
        assertFalse(review.toString().contains("example"))
    }

    @Test fun idsHave128BitsAndAreFresh() {
        val first = metadata()
        val second = metadata()
        assertTrue(first.setupId.matches(Regex("[a-f0-9]{32}")))
        assertNotEquals(first.setupId, second.setupId)
    }

    @Test fun expiredFutureAndBadClocksAreRejected() {
        val text = SetupMetadataCodec.encode(metadata())
        reject(text, now + 600)
        reject(text, now - 31)
        reject(text, 0)
        reject(text, now * 1000)
        reject(text, Long.MAX_VALUE)
        assertEquals(
            metadata().issuedAtEpochSeconds,
            SetupMetadataCodec.decode(text, now - 30).metadata.issuedAtEpochSeconds
        )
        assertEquals(now, SetupMetadataCodec.decode(text, now + 599).metadata.issuedAtEpochSeconds)
    }

    @Test fun lifetimeIntervalAndIdBoundsAreStrict() {
        val value = metadata()
        assertThrows(Exception::class.java) { value.copy(setupId = "A".repeat(32)) }
        assertThrows(Exception::class.java) { value.copy(expiresAtEpochSeconds = now) }
        assertThrows(Exception::class.java) { value.copy(expiresAtEpochSeconds = now + 601) }
        reject(SetupMetadataCodec.encode(value).replace(value.setupId, "0".repeat(31)))
    }

    @Test fun unsupportedFieldsAndPrimitiveTypesAreRejected() {
        val text = SetupMetadataCodec.encode(metadata())
        for (candidate in listOf(
            text.replace("\"version\":1", "\"version\":2"),
            text.replace("\"version\":1", "\"version\":\"1\""),
            text.replace("\"issued_at\":$now", "\"issued_at\":$now.0"),
            text.replace("\"issued_at\":$now", "\"issued_at\":${now}e0"),
            text.replace("\"issued_at\":$now", "\"issued_at\":99999999999999999999"),
            text.replace("\"issued_at\":$now", "\"issued_at\":-$now"),
            text.replace("\"issued_at\":$now", "\"issued_at\":0$now"),
            text.replace("\"issued_at\":$now", "\"issued_at\":${now * 1000}"),
            text.replace(
                "\"endpoint\":{",
                "\"endpoint\":[{"
            ).replace(",\"catalog\"", "],\"catalog\""),
            text.replace("\"label\":\"Synthetic\"", "\"label\":false"),
            text.dropLast(1) + ",\"identity\":\"secret\"}",
            text.replace("\"transport\":\"AUTO\"", "\"transport\":\"AUTO\",\"cleartext\":true"),
            text.replace("\"catalog\":null", "\"catalog\":{}"),
            text + "{}",
            text + "\u0000"
        )) {
            reject(candidate)
        }
    }

    @Test fun duplicateAndEscapedAliasKeysAreRejectedAtEveryDepth() {
        val text = SetupMetadataCodec.encode(metadata())
        reject(text.replace("\"version\":1", "\"version\":1,\"version\":1"))
        reject(text.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        reject(
            text.replace(
                "\"transport\":\"AUTO\"",
                "\"transport\":\"AUTO\",\"transport\":\"DIRECT\""
            )
        )
    }

    @Test fun byteDepthUnicodeAndLabelBoundsAreStrict() {
        val text = SetupMetadataCodec.encode(metadata())
        reject(" ".repeat(4097))
        reject(text + " ".repeat(4096))
        reject("{\"unknown\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}")
        reject(text.replace("Synthetic", "\\uD800"))
        reject(text.replace("Synthetic", "\uD800"))
        reject(text.replace("Synthetic", "\\n"))
        reject(text.replace("Synthetic", "x".repeat(33)))
        val unicode = metadata().copy(label = "月".repeat(32))
        assertEquals(
            unicode,
            SetupMetadataCodec.decode(SetupMetadataCodec.encode(unicode), now).metadata
        )
    }

    @Test fun uriSecretsMalformedHostsAndUnsupportedSchemesAreRejected() {
        for (bad in listOf(
            "wss://user:secret@gadget.example.test/", "wss://gadget.example.test/?token=secret",
            "wss://gadget.example.test/#secret", "https://gadget.example.test/",
            "file:///private/data",
            "wss://bad_host/", "wss://-bad.example.test/", "wss://bad..example.test/",
            "wss://999.1.1.1/", "wss://127.1/", "wss://01.2.3.4/", "wss://gadget.example.test:0/",
            "wss://[fe80::1%25zone]/", "wss://gadget.example.test/%0Asecret",
            "wss://gadget.example.test/a/../b",
            "wss://gadget.example.test/ secret", "ws://203.0.113.1/"
        )) {
            assertThrows("Accepted $bad", Exception::class.java) { SetupEndpoint("Synthetic", bad) }
        }
    }

    @Test fun cleartextMetadataNeverTransfersConsentOrCredentials() {
        val native =
            EndpointProfile(Endpoint.parse("ws://10.0.0.1/", true, true), "Synthetic", "SENTINEL")
        val value = SetupMetadataCodec.create("Private", SetupEndpoint.from(native), null, now)
        val text = SetupMetadataCodec.encode(value)
        assertFalse(text.contains("SENTINEL"))
        assertFalse(text.contains("cleartext"))
        assertFalse(text.contains("private"))
        val review = SetupMetadataCodec.decode(text, now)
        assertTrue(review.endpoint!!.requiresCleartextReview)
        assertEquals(80, review.endpoint.port)
        assertEquals(value.setupId, review.metadata.setupId)
    }

    @Test fun validMaliciousEditsRemainUntrustedAndReplayIsNotSpentHere() {
        val text = SetupMetadataCodec.encode(
            metadata()
        ).replace("gadget.example.test", "other.example.test")
        val first = SetupMetadataCodec.decode(text, now)
        val second = SetupMetadataCodec.decode(text, now)
        assertEquals("other.example.test", first.endpoint!!.host)
        assertEquals(first.metadata.setupId, second.metadata.setupId)
    }

    @Test fun catalogMetadataHasNoCredentialAndRequiresHttps() {
        val catalog = SetupCatalog.from(
            CatalogSource("box", "Box", "https://catalog.example.test/catalog", "CATALOG_SENTINEL")
        )
        val value = SetupMetadataCodec.create("Catalog", null, catalog, now)
        val text = SetupMetadataCodec.encode(value)
        assertFalse(text.contains("SENTINEL"))
        assertEquals("https", SetupMetadataCodec.decode(text, now).catalog!!.scheme)
        assertThrows(Exception::class.java) {
            SetupCatalog("box", "Box", "http://catalog.example.test/")
        }
    }

    @Test fun equivalentNativeCatalogUrlsAreCanonicalizedForTransfer() {
        val source = CatalogSource("box", "Box", "https://Catalog.Example.TEST:443", "SENTINEL")
        assertEquals("https://catalog.example.test/", SetupCatalog.from(source).url)
        val port = CatalogSource("box", "Box", "https://catalog.example.test:8443/c", "SENTINEL")
        assertEquals("https://catalog.example.test:8443/c", SetupCatalog.from(port).url)
        assertThrows(Exception::class.java) {
            SetupCatalog.from(CatalogSource("box", "Box", "https://h.example.test/a/../b", "S"))
        }
        assertThrows(Exception::class.java) {
            SetupCatalog("box", "Box", "https://catalog.example.test")
        }
    }

    @Test fun ipv6PreviewShowsBareHostAndDefaultPort() {
        val value = SetupMetadataCodec.create(
            "Synthetic",
            SetupEndpoint("Synthetic", "wss://[fd00::1]/"),
            null,
            now
        )
        val review = SetupMetadataCodec.decode(SetupMetadataCodec.encode(value), now)
        assertEquals("fd00::1", review.endpoint!!.host)
        assertEquals(443, review.endpoint.port)
        assertEquals("/", review.endpoint.path)
    }

    @Test fun oversizedMetadataCannotBeEncodedAsAQrPayload() {
        val long = "wss://gadget.example.test/" + "a".repeat(2000)
        val catalog = SetupCatalog("box", "Box", "https://catalog.example.test/" + "b".repeat(2000))
        val value = SetupMetadataCodec.create("Synthetic", SetupEndpoint("S", long), catalog, now)
        assertThrows(IllegalArgumentException::class.java) { SetupMetadataCodec.encode(value) }
    }
}
