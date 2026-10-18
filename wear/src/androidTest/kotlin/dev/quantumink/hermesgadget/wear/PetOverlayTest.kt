package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.PetManifest
import dev.quantumink.hermesgadget.protocol.PetRow
import dev.quantumink.hermesgadget.protocol.PetSheet
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original synthetic art with deliberately different bodies; never edits enrolled profiles. */
class PetOverlayTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    internal fun fixture(bodyShift: Int = 0): Triple<PetManifest, ByteArray, ByteArray> {
        val bitmap = Bitmap.createBitmap(1152, 208, Bitmap.Config.ARGB_8888)
        repeat(3) { column ->
            for (y in 40 until 190) {
                for (x in 40 until 152) {
                    bitmap.setPixel(
                        column * 192 + x,
                        y,
                        Color.argb(
                            if (x <
                                60
                            ) {
                                123
                            } else {
                                255
                            },
                            80 + column * 60 + bodyShift,
                            140,
                            90
                        )
                    )
                }
            }
            for (y in 101 until 125) {
                for (x in 81 until 113) {
                    bitmap.setPixel(
                        column * 192 + x,
                        y,
                        when (column) {
                            0 -> Color.BLACK
                            1 -> if (x < 95) Color.TRANSPARENT else Color.BLUE
                            else -> Color.RED
                        }
                    )
                }
            }
        }
        val baseStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baseStream)
        val base = baseStream.toByteArray()
        val baseHash = MessageDigest.getInstance("SHA-256").digest(base).joinToString("") {
            "%02x".format(it)
        }
        // Different hashes and assets, with a changed unused cell outside all occupied poses.
        bitmap.setPixel(1100, 200, Color.WHITE)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val bytes = stream.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        val manifest = PetManifest.parse(
            requireNotNull(
                Message.parse(
                    """{"type":"pet.manifest","name":"Overlay fixture","cell_width":192,"cell_height":208,"loop_ms":1100,
            "sheets":[{"kind":"base","sha256":"$baseHash","bytes":${base.size},"width":1152,"height":208,
            "rows":[{"name":"idle","index":0,"frames":3}]},
            {"kind":"companion","sha256":"$hash","bytes":${bytes.size},"width":1152,"height":208,
            "rows":[{"name":"talking","index":0,"frames":3,"overlay":{"anchor_frame":0,"rect":[81,101,113,125]}}]}]}"""
                )
            )
        )
        return Triple(manifest, base, bytes)
    }

    @Test fun replacesRegionIncludingTransparencyAndKeepsEveryOtherSampleAndSourceByte() {
        val (manifest, _, bytes) = fixture()
        val before = bytes.copyOf()
        val cache = PetCache(context)
        val raw = manifest.sheets[1]
        val source = cache.decode(
            PetSheet(
                "base",
                raw.sha256,
                raw.bytes,
                raw.width,
                raw.height,
                listOf(PetRow("idle", 0, 3))
            ),
            bytes
        ).getValue("idle")
        val originalPixels = source.map { frame ->
            IntArray(96 * 104).also { frame.getPixels(it, 0, 96, 0, 0, 96, 104) }
        }
        val derived = cache.decode(manifest.sheets[1], bytes).getValue("talking")
        try {
            assertEquals(3, derived.size)
            for (column in 0..2) {
                for (y in 0 until 104) {
                    for (x in 0 until 96) {
                        val inside = x * 2 in 81 until 113 && y * 2 in 101 until 125
                        assertEquals(
                            "pose=$column sample=$x,$y",
                            (if (inside) source[column] else source[0]).getPixel(x, y),
                            derived[column].getPixel(x, y)
                        )
                    }
                }
            }
            assertEquals(Color.TRANSPARENT, derived[1].getPixel(43, 54))
            assertNotEquals(derived[0].getPixel(51, 54), derived[1].getPixel(51, 54))
            assertNotEquals(derived[1].getPixel(51, 54), derived[2].getPixel(51, 54))
            assertArrayEquals(before, bytes)
            source.forEachIndexed { index, frame ->
                val after = IntArray(96 * 104)
                frame.getPixels(after, 0, 96, 0, 0, 96, 104)
                assertArrayEquals(originalPixels[index], after)
            }
        } finally {
            (source + derived).forEach(Bitmap::recycle)
        }
        assertTrue((source + derived).all { it.isRecycled })
    }

    @Test fun lateEndpointAtlasCannotReplaceAnotherProfilesDerivedAnchor() {
        val cache = PetCache(context)
        val (aManifest, _, aBytes) = fixture()
        val (bManifest, _, bBytes) = fixture(10)
        val aRows = cache.decode(aManifest.sheets[1], aBytes)
        val bRows = cache.decode(bManifest.sheets[1], bBytes)
        val a =
            PetAtlas("Same name", 1100, aRows + ("idle" to listOf(aRows.getValue("talking")[0])))
        val b =
            PetAtlas("Same name", 1100, bRows + ("idle" to listOf(bRows.getValue("talking")[0])))
        try {
            val aEndpoint = dev.quantumink.hermesgadget.protocol.Endpoint.parse(
                "ws://localhost:8797/a",
                true,
                true
            )
            val bEndpoint = dev.quantumink.hermesgadget.protocol.Endpoint.parse(
                "ws://localhost:8797/b",
                true,
                true
            )
            val profile = EndpointProfile(bEndpoint, "Same name", "")
            val current = WatchState(profile = profile, pet = b, petEndpoint = bEndpoint.url)
            val late = current.withPresentationPet(aEndpoint.url, a)
            org.junit.Assert.assertSame(b, late.pet)
            assertEquals(bEndpoint.url, late.petEndpoint)
            assertNotEquals(aManifest.sheets[1].sha256, bManifest.sheets[1].sha256)
            assertNotEquals(
                aRows.getValue("talking")[0].getPixel(65, 70),
                bRows.getValue("talking")[0].getPixel(65, 70)
            )
            for (frames in listOf(aRows.getValue("talking"), bRows.getValue("talking"))) {
                assertEquals(frames[0].getPixel(65, 70), frames[2].getPixel(65, 70))
            }
        } finally {
            (aRows.values.flatten() + bRows.values.flatten()).forEach(Bitmap::recycle)
        }
    }

    @Test fun exactHashManifestRoundTripIsEndpointOwnedAndCorruptionFallsBackToBase() {
        val (manifest, base, bytes) = fixture()
        val cache = PetCache(context)
        val endpoint = "ws://localhost:8799/o2-${System.nanoTime()}"
        val other = "$endpoint-other"
        fun recycle(atlas: PetAtlas?) {
            atlas?.rows?.values?.flatten()?.forEach(Bitmap::recycle)
        }
        try {
            assertNull(cache.saveManifest(endpoint, manifest))
            recycle(cache.saveAsset(endpoint, manifest, manifest.sheets[0], base))
            val fallback = requireNotNull(cache.load(endpoint))
            assertNull(fallback.rows["talking"])
            recycle(fallback)
            recycle(cache.saveAsset(endpoint, manifest, manifest.sheets[1], bytes))
            val loaded = requireNotNull(cache.load(endpoint))
            assertEquals(Color.TRANSPARENT, loaded.rows.getValue("talking")[1].getPixel(43, 54))
            recycle(loaded)
            assertNull(cache.load(other))
            val corrupt = bytes.copyOf().apply {
                this[lastIndex] =
                    (this[lastIndex].toInt() xor 1).toByte()
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                cache.saveAsset(endpoint, manifest, manifest.sheets[1], corrupt)
            }
            val retained = requireNotNull(cache.load(endpoint))
            assertEquals(3, retained.rows.getValue("talking").size)
            recycle(retained)
            val endpointHash = MessageDigest.getInstance(
                "SHA-256"
            ).digest(endpoint.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
            java.io.File(
                context.filesDir,
                "pet-cache/$endpointHash/${manifest.sheets[1].sha256}.png"
            ).writeBytes(corrupt)
            val baseFallback = requireNotNull(cache.load(endpoint))
            assertNull(baseFallback.rows["talking"])
            assertEquals(3, baseFallback.rows.getValue("idle").size)
            recycle(baseFallback)
        } finally {
            cache.remove(endpoint)
            cache.remove(other)
        }
    }
}
