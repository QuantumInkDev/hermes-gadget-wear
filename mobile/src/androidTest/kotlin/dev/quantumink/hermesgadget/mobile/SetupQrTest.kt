package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import dev.quantumink.hermesgadget.protocol.TransportPreference
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real ZXing raster encode/decode and the reviewed, replay-guarded add on real stores. */
class SetupQrTest {
    private val fixtures = mutableListOf<TransferFixture>()
    private fun fixture() = TransferFixture().also(fixtures::add)
    private val now = System.currentTimeMillis() / 1000

    @After fun close() {
        fixtures.forEach { it.quiesce() }
        fixtures.forEach { it.close() }
    }

    private fun code(url: String = ALPHA_URL, issued: Long = now): String =
        SetupMetadataCodec.encode(
            SetupMetadataCodec.create("Alpha", SetupEndpoint("Alpha", url), null, issued)
        )

    private fun png(bitmap: Bitmap): ByteArray = SetupQr.png(bitmap).also { bitmap.recycle() }

    @Test fun renderedCodeDecodesFromAPictureAndFromADistortedPhoto() {
        val text = code()
        assertEquals(text, SetupQr.decode(png(SetupQr.render(text))))
        // A photographed screen: rotated, scaled into a large noisy frame, JPEG compressed.
        val qr = SetupQr.render(text, 600)
        val photo = Bitmap.createBitmap(3000, 2200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo)
        val random = Random(4)
        canvas.drawColor(Color.rgb(120, 110, 100))
        repeat(4000) {
            canvas.drawCircle(
                random.nextFloat() * 3000,
                random.nextFloat() * 2200,
                random.nextFloat() * 6,
                Paint().apply { color = Color.rgb(random.nextInt(256), 90, 80) }
            )
        }
        canvas.rotate(7f, 1500f, 1100f)
        canvas.drawBitmap(
            qr,
            null,
            android.graphics.RectF(800f, 300f, 2200f, 1700f),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        qr.recycle()
        val jpeg = ByteArrayOutputStream().also {
            photo.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        photo.recycle()
        val started = SystemClock.elapsedRealtime()
        assertEquals(text, SetupQr.decode(jpeg))
        android.util.Log.i(
            "SetupQrTest",
            "photo decode ms=" + (SystemClock.elapsedRealtime() - started)
        )
        // A picture without any code.
        assertNull(SetupQr.decode(TransferFixture.png(0, 400, noise = true)))
    }

    /** A valid grayscale PNG of any declared size whose pixel data compresses to almost nothing. */
    private fun blankPng(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray(Charsets.US_ASCII) + data
            val crc = CRC32().apply { update(body) }.value.toInt()
            return ByteBuffer.allocate(4).putInt(data.size).array() + body +
                ByteBuffer.allocate(4).putInt(crc).array()
        }
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(1).put(0).put(0).put(0).put(0).array()
        val deflater = Deflater(9)
        val row = ByteArray(1 + (width + 7) / 8)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        repeat(height) {
            deflater.setInput(row)
            while (!deflater.needsInput()) output.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return signature + chunk("IHDR", header) + chunk("IDAT", output.toByteArray()) +
            chunk("IEND", ByteArray(0))
    }

    @Test fun oversizedPicturesAreRejectedFromTheHeaderBeforePixelAllocation() {
        listOf(9000 to 10, 7000 to 7000).forEach { (width, height) ->
            val bytes = blankPng(width, height)
            assertTrue(bytes.size < SetupQr.MAX_IMAGE_BYTES)
            val runtime = Runtime.getRuntime()
            val started = SystemClock.elapsedRealtime()
            assertThrows(IllegalArgumentException::class.java) { SetupQr.decode(bytes) }
            android.util.Log.i(
                "SetupQrTest",
                "reject ${width}x$height bytes=${bytes.size} ms=" +
                    (SystemClock.elapsedRealtime() - started) +
                    " heapUsed=" + (runtime.totalMemory() - runtime.freeMemory())
            )
        }
        // Within bounds, a large picture is scaled down while decoding and still processed.
        assertNull(SetupQr.decode(blankPng(6000, 4000)))
        assertThrows(IllegalArgumentException::class.java) {
            SetupQr.decode(ByteArray(SetupQr.MAX_IMAGE_BYTES + 1))
        }
    }

    @Test fun reviewedAddSpendsOnlyAfterTheWriteAndRefusesReplayExpiryAndConsentGaps() {
        val target = fixture()
        val stores = target.stores()
        val review = SetupMetadataCodec.decode(code(), now)
        // Review then cancel: nothing written, nothing spent.
        assertFalse(SetupReplayJournal(stores.directory).spent(review.metadata.setupId, now))
        assertEquals("", target.state(stores).getValue("endpoints"))
        assertEquals(QrAddResult.ADDED, SetupQrImport.add(stores, review, QrAddChoice(), now))
        assertEquals(
            "$ALPHA_URL|Alpha||false|false|AUTO",
            target.state().getValue("endpoints")
        )
        assertEquals(1, target.setupPublications.get())
        assertEquals(
            QrAddResult.ALREADY_USED,
            SetupQrImport.add(stores, review, QrAddChoice(), now)
        )
        assertEquals(
            QrAddResult.EXPIRED,
            SetupQrImport.add(stores, review, QrAddChoice(), now + 601)
        )
        assertThrows(IllegalArgumentException::class.java) {
            SetupMetadataCodec.decode(code(issued = now - 601), now)
        }
        // A different code for an existing address changes nothing and is not spent.
        val local = target.stores()
        local.profiles.save(EndpointProfile(Endpoint.parse(TWO_URL), "Mine", "LOCAL-TOKEN"))
        val again = SetupMetadataCodec.decode(code(TWO_URL), now)
        assertEquals(
            QrAddResult.ALREADY_SAVED,
            SetupQrImport.add(stores, again, QrAddChoice(), now)
        )
        assertFalse(SetupReplayJournal(stores.directory).spent(again.metadata.setupId, now))
        assertTrue(target.state().getValue("endpoints").contains("$TWO_URL|Mine|LOCAL-TOKEN"))
        // Cleartext requires both owner choices; a refused add leaves the code unspent.
        val cleartext = SetupMetadataCodec.decode(code(BETA_URL), now)
        assertTrue(cleartext.endpoint!!.requiresCleartextReview)
        assertEquals("192.168.40.20", cleartext.endpoint!!.host)
        assertEquals(8765, cleartext.endpoint!!.port)
        assertEquals("/gadget", cleartext.endpoint!!.path)
        assertThrows(IllegalArgumentException::class.java) {
            SetupQrImport.add(stores, cleartext, QrAddChoice(privateNetwork = true), now)
        }
        assertFalse(SetupReplayJournal(stores.directory).spent(cleartext.metadata.setupId, now))
        assertEquals(
            QrAddResult.ADDED,
            SetupQrImport.add(stores, cleartext, QrAddChoice(true, true), now)
        )
        assertTrue(target.state().getValue("endpoints").contains("$BETA_URL|Alpha||true|true|AUTO"))
    }

    @Test fun catalogCodesNeedAFreshCredentialAndNeverDiscover() {
        val target = fixture()
        val stores = target.stores()
        val review = SetupMetadataCodec.decode(
            SetupMetadataCodec.encode(
                SetupMetadataCodec.create(
                    "Box",
                    null,
                    SetupCatalog("home", "Home box", CATALOG_URL),
                    now
                )
            ),
            now
        )
        assertThrows(IllegalArgumentException::class.java) {
            SetupQrImport.add(stores, review, QrAddChoice(), now)
        }
        assertEquals("", target.state().getValue("sources"))
        assertEquals(
            QrAddResult.ADDED,
            SetupQrImport.add(stores, review, QrAddChoice(catalogCredential = "typed-key"), now)
        )
        assertEquals("home|Home box|$CATALOG_URL|typed-key", target.state().getValue("sources"))
        // Fetchers in the fixture throw; reaching here proves no discovery was attempted.
        assertTrue(target.published.isNotEmpty())
    }

    @Test fun unreadableReplayHistoryRefusesAddsUntilCleared() {
        val target = fixture()
        val stores = target.stores()
        File(stores.directory, "replay.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"version\":1,\"spent\":[{\"id\":\"zz\",\"expires\":1}]}")
        }
        val review = SetupMetadataCodec.decode(code(), now)
        assertEquals(QrAddResult.UNAVAILABLE, SetupQrImport.add(stores, review, QrAddChoice(), now))
        assertEquals("", target.state().getValue("endpoints"))
        SetupReplayJournal(stores.directory).clearUnreadable(now)
        assertEquals(QrAddResult.ADDED, SetupQrImport.add(stores, review, QrAddChoice(), now))
    }

    @Test fun relayTransportCannotBeCombinedWithCleartext() {
        assertThrows(IllegalArgumentException::class.java) {
            SetupEndpoint("Beta", BETA_URL, TransportPreference.RELAY)
        }
    }
}
