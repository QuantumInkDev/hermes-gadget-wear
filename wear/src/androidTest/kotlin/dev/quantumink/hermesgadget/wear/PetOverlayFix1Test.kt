package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.BinaryFrame
import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.PetEffect
import dev.quantumink.hermesgadget.protocol.PetTransfers
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix-round receipts and all cache writes are confined to new synthetic namespaces. */
class PetOverlayFix1Test {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preScaleFailureRecyclesCropsAndPreservesSource() {
        val (manifest, _, bytes) = PetOverlayTest().fixture()
        val before = bytes.copyOf()
        val crops = mutableListOf<Bitmap>()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                PetCache(context).decode(manifest.sheets[1], bytes) { crop ->
                    crops.add(crop)
                    if (crops.size == 2) error("Synthetic pre-scale failure")
                }
            }
            assertEquals("Synthetic pre-scale failure", failure.message)
            assertEquals(2, crops.size)
            assertArrayEquals(before, bytes)
            assertTrue(
                "Every created crop must recycle even before pixel extraction",
                crops.all {
                    it.isRecycled
                }
            )
        } finally {
            crops.filterNot { it.isRecycled }.forEach(Bitmap::recycle)
        }
    }

    @Test fun malformedWireCallbacksCannotChangeValidCacheOrAdmitLateAssets() {
        val (manifest, base, bytes) = PetOverlayTest().fixture()
        val cache = PetCache(context)
        val endpoint = "ws://localhost:8799/o2-fix1-${System.nanoTime()}"
        val endpointHash = MessageDigest.getInstance(
            "SHA-256"
        ).digest(endpoint.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        val directory = File(context.filesDir, "pet-cache/$endpointHash")
        val receiver = PetTransfers()
        fun recycle(atlas: PetAtlas?) {
            atlas?.rows?.values?.flatten()?.forEach(Bitmap::recycle)
        }
        var manifests = 0
        var assets = 0
        fun accept(effect: PetEffect) {
            when (effect) {
                is PetEffect.Manifest -> {
                    manifests++
                    recycle(cache.saveManifest(endpoint, effect.value))
                }
                is PetEffect.Asset -> {
                    assets++
                    error("Late asset must never be delivered")
                }
            }
        }
        var retained: PetAtlas? = null
        try {
            receiver.message(requireNotNull(Message.parse(manifest.encoded)), 0).forEach(::accept)
            recycle(cache.saveAsset(endpoint, manifest, manifest.sheets[0], base))
            recycle(cache.saveAsset(endpoint, manifest, manifest.sheets[1], bytes))
            retained = requireNotNull(cache.load(endpoint))
            val anchor = retained.rows.getValue("talking")[0].getPixel(65, 70)
            val originals = requireNotNull(directory.listFiles()).associate {
                it.name to
                    it.readBytes()
            }
            manifests = 0
            for (replacement in listOf(
                "\"anchor_frame\":0,\"rect\":[2147483647,100,-2147483648,124]",
                "\"anchor_frame\":0,\"rect\":[80,2147483647,112,-2147483648]",
                "\"anchor_frame\":0e0,\"rect\":[81,101,113,125]",
                "\"anchor_frame\":0,\"rect\":[8.1e1,101,113,125]"
            )) {
                receiver.message(requireNotNull(Message.parse(manifest.encoded)), 1)
                receiver.message(
                    requireNotNull(
                        Message.parse(
                            """{"type":"asset.start","stream":1,"sha256":"${manifest.sheets[1].sha256}","bytes":${bytes.size},"format":"png"}"""
                        )
                    ),
                    2
                )
                val invalid = manifest.encoded.replace(
                    "\"anchor_frame\":0,\"rect\":[81,101,113,125]",
                    replacement
                )
                assertTrue(invalid != manifest.encoded)
                receiver.message(requireNotNull(Message.parse(invalid)), 3).forEach(::accept)
                bytes.toList().chunked(4096).forEachIndexed { index, chunk ->
                    receiver.binary(
                        BinaryFrame(4, 1, index, chunk.toByteArray()),
                        4
                    ).forEach(::accept)
                }
                receiver.message(
                    requireNotNull(Message.parse("""{"type":"asset.end","stream":1}""")),
                    5
                ).forEach(::accept)
            }
            assertEquals("Malformed metadata must never reach the cache callback", 0, manifests)
            assertEquals(0, assets)
            for ((name, original) in originals) {
                assertArrayEquals(
                    original,
                    File(directory, name).readBytes()
                )
            }
            val restored = requireNotNull(cache.load(endpoint))
            try {
                assertEquals(anchor, restored.rows.getValue("talking")[2].getPixel(65, 70))
                assertEquals(3, restored.rows.getValue("talking").size)
                assertNull(cache.load("$endpoint-other"))
                val receipts = File(context.filesDir, "o2-fix1-captures").apply { mkdirs() }
                File(receipts, "manifest.json").writeText(manifest.encoded)
                File(receipts, "source.png").writeBytes(bytes)
                restored.rows.getValue("talking").forEachIndexed { index, frame ->
                    File(receipts, "derived-$index.png").outputStream().use {
                        frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                File(
                    receipts,
                    "callback-proof.txt"
                ).writeText(
                    "malformed_manifest_callbacks=$manifests; late_asset_callbacks=$assets; existing_cache_files_identical=${originals.size}; retained_mouths=3"
                )
            } finally {
                recycle(restored)
            }
            assertNotNull(retained.rows["idle"])
        } finally {
            recycle(retained)
            cache.remove(endpoint)
        }
    }
}
