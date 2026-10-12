package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.PortraitAnimations
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationPublicationTest {
    @Test fun boundAssetsPrecedeManifestAndOnlyOwnedUnreferencedAssetsAreCleaned() {
        val gif = GifPortrait.normalize(
            javaClass.getResourceAsStream("/gif/original-two-frame.gif")!!.readBytes()
        ).bytes
        val hash = MessageDigest.getInstance("SHA-256").digest(gif).joinToString("") {
            "%02x".format(it)
        }
        val fallback = "a".repeat(64)
        val descriptor = AnimationDescriptor(hash, gif.size, fallback)
        val page = CatalogPage(
            "box",
            "Box",
            CatalogProfile(
                "p",
                "Agent",
                null,
                PortraitDescriptor(fallback, 10, "/assets/$fallback.png")
            ),
            CatalogStatus.AVAILABLE,
            0
        )
        val events = mutableListOf<String>()
        val stale = CatalogDataItem("mine", PortraitAnimations.ASSET_PATH + "b".repeat(64))
        val transport = object : AnimationPublicationTransport {
            override val owner = "mine"
            override fun asset(hash: String, bytes: ByteArray) {
                events.add("asset")
            }
            override fun manifest(json: String) {
                assertEquals(mapOf(page.key to descriptor), PortraitAnimations.decode(json))
                events.add("manifest")
            }
            override fun items() = listOf(
                stale,
                CatalogDataItem("other", stale.path),
                CatalogDataItem("mine", PortraitAnimations.ASSET_PATH + hash),
                CatalogDataItem("mine", "/unrelated")
            )
            override fun delete(item: CatalogDataItem) {
                assertEquals(stale, item)
                events.add("delete")
            }
        }
        AnimationPublication.publish(listOf(page), mapOf(page.key to descriptor), {
            gif
        }, transport)
        assertEquals(listOf("asset", "manifest", "delete"), events)
        events.clear()
        val failure = object : AnimationPublicationTransport by transport {
            override fun manifest(json: String): Unit =
                throw IllegalStateException("Synthetic publish failure")
        }
        assertThrows(IllegalStateException::class.java) {
            AnimationPublication.publish(listOf(page), mapOf(page.key to descriptor), {
                gif
            }, failure)
        }
        assertFalse(events.contains("delete"))
        val removed = object : AnimationPublicationTransport by transport {
            override fun manifest(json: String) {
                assertTrue(PortraitAnimations.decode(json).isEmpty())
            }
            override fun items() = emptyList<CatalogDataItem>()
        }
        AnimationPublication.publish(emptyList(), mapOf(page.key to descriptor), { gif }, removed)
        AnimationPublication.publish(
            listOf(page),
            mapOf(page.key to AnimationDescriptor(hash, gif.size, "c".repeat(64))),
            { gif },
            removed
        )
        AnimationPublication.publish(listOf(page), mapOf(page.key to descriptor), { null }, removed)
    }
}
