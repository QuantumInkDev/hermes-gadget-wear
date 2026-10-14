package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPublicationTest {
    private class Fake : CatalogPublicationTransport {
        override val owner = "phone"
        val stored = mutableSetOf(
            CatalogDataItem(
                "other",
                ProfileCatalog.PORTRAIT_PATH + "f".repeat(64)
            ),
            CatalogDataItem(owner, "/unrelated/item")
        )
        val operations = mutableListOf<String>()
        var fail = false
        override fun portrait(hash: String, bytes: ByteArray) {
            operations.add("portrait")
            stored.add(
                CatalogDataItem(
                    owner,
                    ProfileCatalog.PORTRAIT_PATH + hash
                )
            )
        }
        override fun pages(json: String) {
            if (fail) error("synthetic failure")
            operations.add("pages")
        }
        override fun items() = stored.toList()
        override fun delete(item: CatalogDataItem) {
            operations.add("delete")
            stored.remove(item)
        }
    }

    @Test fun replacementsAndRemovalDeleteOnlyLocalUnreferencedPortraitsAfterMetadata() {
        val fake = Fake()
        repeat(40) { index ->
            val hash = "%064x".format(index)
            val pages =
                listOf(
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile(
                            "p",
                            "Agent",
                            null,
                            PortraitDescriptor(hash, 1, "/assets/$hash.png")
                        ),
                        CatalogStatus.AVAILABLE,
                        1
                    )
                )
            fake.operations.clear()
            CatalogPublication.publish(pages, { byteArrayOf(1) }, fake)
            assertEquals(3, fake.stored.size)
            assertTrue(
                fake.operations.indexOf("delete").let {
                    it < 0 ||
                        it > fake.operations.indexOf("pages")
                }
            )
        }
        CatalogPublication.publish(emptyList(), { null }, fake)
        assertEquals(2, fake.stored.size)
        fake.fail = true
        val protected = CatalogDataItem(fake.owner, ProfileCatalog.PORTRAIT_PATH + "a".repeat(64))
        fake.stored.add(protected)
        assertThrows(IllegalStateException::class.java) {
            CatalogPublication.publish(emptyList(), { null }, fake)
        }
        assertTrue(fake.stored.contains(protected))
    }
}
