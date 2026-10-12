package dev.quantumink.hermesgadget.protocol

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GifPortraitTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/gif/$name")).use { it.readBytes() }

    @Test fun originalFramesKeepPixelsWhileMetadataLoopAndShortDelayAreNormalized() {
        val source = fixture("original-two-frame.gif")
        val normalized = GifPortrait.normalize(source)
        assertEquals(8, normalized.width)
        assertEquals(8, normalized.height)
        assertEquals(2, normalized.frames)
        assertEquals(120, normalized.durationMillis)
        val text = normalized.bytes.toString(Charsets.ISO_8859_1)
        assertEquals("GIF89a", text.take(6))
        assertFalse(text.contains("original-synthetic-comment"))
        assertFalse(text.contains("SYNTHETIC01"))
        val loop = text.indexOf("NETSCAPE2.0") + 11
        assertArrayEquals(byteArrayOf(3, 1, 0, 0, 0), normalized.bytes.copyOfRange(loop, loop + 5))
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(ByteArrayInputStream(normalized.bytes)).use { input ->
            reader.input = input
            try {
                assertEquals(2, reader.getNumImages(true))
                assertEquals(0xff7dddd2.toInt(), reader.read(0).getRGB(0, 0))
                assertEquals(0xff9dabff.toInt(), reader.read(1).getRGB(0, 0))
            } finally {
                reader.dispose()
            }
        }
        assertArrayEquals(normalized.bytes, GifPortrait.normalize(normalized.bytes).bytes)
        assertArrayEquals(normalized.bytes, GifPortrait.validate(normalized.bytes).bytes)
        assertThrows(IllegalArgumentException::class.java) { GifPortrait.validate(source) }
    }

    @Test fun single87aFrameGetsABoundedDefaultDelayAndDefensiveBytes() {
        val source = fixture("original-single-87a.gif")
        val info = GifPortrait.normalize(source)
        source.fill(0)
        assertEquals(1, info.width)
        assertEquals(1, info.height)
        assertEquals(1, info.frames)
        assertEquals(50, info.durationMillis)
        val first = info.bytes
        first.fill(0)
        assertEquals("GIF89a", info.bytes.toString(Charsets.US_ASCII).take(6))
        assertEquals(1, GifPortrait.validate(info.bytes).frames)
    }

    @Test fun independentImageIoEncoderExercisesGrowingAndFullLzwDictionaries() {
        val original = BufferedImage(256, 256, BufferedImage.TYPE_BYTE_INDEXED)
        val random = java.util.Random(314159)
        for (y in 0 until 256) {
            for (x in 0 until 256) original.raster.setSample(x, y, 0, random.nextInt(256))
        }
        val source = ByteArrayOutputStream().also {
            ImageIO.write(original, "gif", it)
        }.toByteArray()
        val normalized = GifPortrait.normalize(source)
        assertEquals(256, normalized.width)
        assertEquals(256, normalized.height)
        assertEquals(1, normalized.frames)
        val decoded = ImageIO.read(ByteArrayInputStream(normalized.bytes))
        for (y in 0 until 256 step 17) {
            for (x in 0 until 256 step 19) assertEquals(original.getRGB(x, y), decoded.getRGB(x, y))
        }
        assertArrayEquals(normalized.bytes, GifPortrait.validate(normalized.bytes).bytes)
    }

    @Test fun localPaletteTransparencyAndCanonicalSubBlockBoundariesAreHandled() {
        val source = tiny()
        val local = source.copyOfRange(0, 13).also { it[10] = 0 } +
            source.copyOfRange(19, 37).also { it[17] = 0x80.toByte() } +
            source.copyOfRange(13, 19) + source.copyOfRange(37, source.size)
        assertEquals(
            0xff7dddd2.toInt(),
            ImageIO.read(ByteArrayInputStream(GifPortrait.normalize(local).bytes)).getRGB(0, 0)
        )
        val transparent = GifPortrait.normalize(tiny(control = 1))
        assertEquals(0, ImageIO.read(ByteArrayInputStream(transparent.bytes)).getRGB(0, 0) ushr 24)
        val canonical = GifPortrait.normalize(source).bytes
        val segmented =
            canonical.copyOfRange(0, canonical.size - 5) + byteArrayOf(1, 0x44, 1, 1, 0, 0x3b)
        assertArrayEquals(canonical, GifPortrait.normalize(segmented).bytes)
        assertThrows(IllegalArgumentException::class.java) { GifPortrait.validate(segmented) }
    }

    @Test fun independentCanvasFramePixelAndDurationBoundariesAreEnforced() {
        assertEquals(32, GifPortrait.normalize(tiny(256, 256, 32)).frames)
        assertEquals(120, GifPortrait.normalize(tiny(frames = 120)).frames)
        assertEquals(10000, GifPortrait.normalize(tiny(frames = 100, delay = 10)).durationMillis)
        assertEquals(50, GifPortrait.normalize(tiny(delay = 0)).durationMillis)
        for (invalid in listOf(
            tiny(257, 1), tiny(1, 257), tiny(0, 1), tiny(1, 0), tiny(256, 256, 33),
            tiny(frames = 121), tiny(frames = 101, delay = 10), tiny(delay = 1001),
            ByteArray(262145)
        )) {
            assertThrows(IllegalArgumentException::class.java) { GifPortrait.normalize(invalid) }
        }
    }

    @Test fun everyTruncationAndInvalidControlRectangleOrPaletteIsRejected() {
        val source = fixture("original-two-frame.gif")
        for (length in source.indices) {
            assertThrows("Truncation at $length", IllegalArgumentException::class.java) {
                GifPortrait.normalize(source.copyOf(length))
            }
        }
        val malformed = listOf(
            tiny(control = 2), tiny(control = 16), tiny(control = 32),
            tiny(control = 1, transparent = 2), tiny(imageWidth = 0), tiny(imageHeight = 0),
            tiny(x = 1), tiny(y = 1), tiny(imageFlags = 8),
            tiny().also { it[11] = 2 }, tiny() + byteArrayOf(0)
        )
        for (invalid in malformed) {
            assertThrows(IllegalArgumentException::class.java) { GifPortrait.normalize(invalid) }
        }
    }

    @Test fun actualLzwCodesMustFillExactlyTheFrameWithValidPaletteIndicesAndEndCode() {
        for (invalid in listOf(
            tiny(minimum = 1), tiny(minimum = 9), tiny(data = byteArrayOf()),
            tiny(data = pack(listOf(4, 7, 5))), tiny(data = byteArrayOf(4)),
            tiny(width = 2, imageWidth = 2), tiny(data = pack(listOf(4, 0, 0, 5))),
            tiny(data = pack(listOf(4, 3, 5))), tiny(data = byteArrayOf(0x44, 1, 0))
        )) {
            assertThrows(IllegalArgumentException::class.java) { GifPortrait.normalize(invalid) }
        }
        val repeated = GifPortrait.normalize(
            tiny(width = 3, imageWidth = 3, data = pack(listOf(4, 0, 6, 5)))
        )
        assertEquals(3, ImageIO.read(ByteArrayInputStream(repeated.bytes)).width)
        val growth = GifPortrait.normalize(
            tiny(
                width = 4,
                imageWidth = 4,
                data = pack(listOf(4, 0, 1, 0, 1, 5), listOf(3, 3, 3, 3, 4, 4))
            )
        )
        val decoded = ImageIO.read(ByteArrayInputStream(growth.bytes))
        assertEquals(0xff7dddd2.toInt(), decoded.getRGB(0, 0))
        assertEquals(0xff9dabff.toInt(), decoded.getRGB(3, 0))
        val padded = tiny(data = byteArrayOf(0x44, 0xf9.toByte()))
        assertArrayEquals(GifPortrait.normalize(tiny()).bytes, GifPortrait.normalize(padded).bytes)
        assertThrows(IllegalArgumentException::class.java) { GifPortrait.validate(padded) }
    }

    @Test fun orphanAndDuplicateControlsPlainTextAndMissingColorTableAreRejected() {
        val source = tiny()
        val gce = source.copyOfRange(19, 27)
        val duplicate = source.copyOfRange(0, 27) + gce + source.copyOfRange(27, source.size)
        val orphan = source.copyOfRange(0, source.lastIndex) + gce + byteArrayOf(0x3b)
        val plainText =
            source.copyOfRange(0, 19) + byteArrayOf(0x21, 1, 0) +
                source.copyOfRange(19, source.size)
        val missingPalette =
            source.copyOfRange(0, 13).also { it[10] = 0 } + source.copyOfRange(19, source.size)
        for (invalid in listOf(duplicate, orphan, plainText, missingPalette)) {
            assertThrows(IllegalArgumentException::class.java) { GifPortrait.normalize(invalid) }
        }
    }

    private fun tiny(
        width: Int = 1,
        height: Int = 1,
        frames: Int = 1,
        delay: Int = 5,
        control: Int = 0,
        transparent: Int = 0,
        x: Int = 0,
        y: Int = 0,
        imageWidth: Int = 1,
        imageHeight: Int = 1,
        imageFlags: Int = 0,
        minimum: Int = 2,
        data: ByteArray = byteArrayOf(0x44, 1)
    ): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray())
        word(width)
        word(height)
        write(
            byteArrayOf(
                0xf0.toByte(), 0, 0, 0x7d, 0xdd.toByte(), 0xd2.toByte(),
                0x9d.toByte(), 0xab.toByte(), 0xff.toByte()
            )
        )
        repeat(frames) {
            write(byteArrayOf(0x21, 0xf9.toByte(), 4, control.toByte()))
            word(delay)
            write(transparent)
            write(0)
            write(0x2c)
            word(x)
            word(y)
            word(imageWidth)
            word(imageHeight)
            write(imageFlags)
            write(minimum)
            write(data.size)
            write(data)
            write(0)
        }
        write(0x3b)
    }.toByteArray()

    private fun ByteArrayOutputStream.word(value: Int) {
        write(value and 255)
        write(value ushr 8 and 255)
    }

    private fun pack(codes: List<Int>, widths: List<Int> = List(codes.size) { 3 }): ByteArray {
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        codes.zip(widths).forEach { (code, width) ->
            buffer = buffer or (code shl bits)
            bits += width
            while (bits >= 8) {
                output.write(buffer and 255)
                buffer = buffer ushr 8
                bits -= 8
            }
        }
        if (bits > 0) output.write(buffer)
        return output.toByteArray()
    }
}
