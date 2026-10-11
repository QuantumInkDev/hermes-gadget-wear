package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileCatalogTest {
    private val sourceA =
        CatalogSource("source-a", "First box", "https://a.example.invalid/catalog/v1", "synthetic")
    private val sourceB =
        CatalogSource("source-b", "Second box", "https://b.example.invalid/catalog/v1", "synthetic")
    private val portrait = PortraitDescriptor("a".repeat(64), 1234, "/assets/${"a".repeat(64)}.png")

    @Test fun failedAndWithdrawnProfilesKeepStablePagesAndArtwork() {
        val first = CatalogProfile("profile-a", "Agent", "wss://a.example.invalid/gadget", portrait)
        val second = CatalogProfile("profile-b", "Agent", "wss://b.example.invalid/gadget", null)
        val original = CatalogRoster.merge(
            CatalogRoster.merge(emptyList(), sourceA, listOf(first), CatalogStatus.AVAILABLE, 10),
            sourceB,
            listOf(second),
            CatalogStatus.AVAILABLE,
            11
        )
        val failed = CatalogRoster.merge(original, sourceA, null, CatalogStatus.ATTENTION, 12)
        assertEquals(listOf("source-a:profile-a", "source-b:profile-b"), failed.map { it.key })
        assertEquals(portrait.sha256, failed.first().profile.portrait?.sha256)
        assertEquals(CatalogStatus.ATTENTION, failed.first().status)
        assertEquals(CatalogStatus.AVAILABLE, failed.last().status)
        val withdrawn = CatalogRoster.merge(
            failed,
            sourceA,
            emptyList(),
            CatalogStatus.AVAILABLE,
            13
        )
        assertEquals(CatalogStatus.WITHDRAWN, withdrawn.first().status)
        val returned = CatalogRoster.merge(
            withdrawn,
            sourceA,
            listOf(first),
            CatalogStatus.AVAILABLE,
            14
        )
        assertEquals(original.map { it.key }, returned.map { it.key })
        assertEquals(CatalogStatus.AVAILABLE, returned.first().status)
        val text = ProfileCatalog.encodePages(returned)
        assertEquals(returned.map { it.key }, ProfileCatalog.decodePages(text).map { it.key })
        assertFalse(text.contains("synthetic"))
        assertFalse(sourceA.toString().contains("synthetic"))
    }

    @Test fun projectionNeverImportsCredentialsOrInfersMissingGadgetUrl() {
        val result = ProfileCatalog.decodeSource(
            """{"version":1,"profiles":[{"id":"profile-a","name":"Agent","token":"ignored-secret","home":"ignored-home"}]}"""
        )
        assertEquals(null, result.single().gadgetUrl)
        assertFalse(
            ProfileCatalog.encodePages(
                listOf(
                    CatalogPage(
                        sourceA.id,
                        sourceA.label,
                        result.single(),
                        CatalogStatus.AVAILABLE,
                        0
                    )
                )
            ).contains("ignored")
        )
    }

    @Test fun rejectsBadTlsAssetEscapesDuplicateIdentitiesAndBounds() {
        for (url in listOf(
            "http://a.example.invalid/catalog/v1",
            "https://user:pass@a.example.invalid/catalog/v1",
            "https://a.example.invalid/catalog/v1#frag"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CatalogSource("a", "Box", url, "token")
            }
        }
        for (path in listOf(
            "https://b.example.invalid/avatar",
            "/assets/../private",
            "/assets/a.png?token=secret",
            "//other.invalid/asset"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PortraitDescriptor("a".repeat(64), 10, path)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogProfile("p", "Agent", "ws://localhost/gadget", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCatalog.decodeSource(" ".repeat(65537))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCatalog.decodeSource(
                """{"version":1,"profiles":[{"id":"p","name":"A"},{"id":"p","name":"B"}]}"""
            )
        }
        val tooMany = (0..32).map {
            CatalogPage(
                "s",
                "Box",
                CatalogProfile("p-$it", "Agent", null, null),
                CatalogStatus.AVAILABLE,
                0
            )
        }
        assertThrows(IllegalArgumentException::class.java) { ProfileCatalog.encodePages(tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            PortraitDescriptor("a".repeat(64), 262145, "/assets/${"a".repeat(64)}.png")
        }
    }
}
