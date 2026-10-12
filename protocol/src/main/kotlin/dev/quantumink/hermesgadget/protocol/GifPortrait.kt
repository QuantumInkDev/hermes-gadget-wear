package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream

class GifInfo internal constructor(
    bytes: ByteArray,
    val width: Int,
    val height: Int,
    val frames: Int,
    val durationMillis: Int
) {
    private val encoded = bytes.copyOf()
    val bytes: ByteArray get() = encoded.copyOf()
}

/** Bounded GIF image validation; platform decoders must also accept the normalized result. */
object GifPortrait {
    const val MAX_BYTES = 262144
    const val MAX_DIMENSION = 256
    const val MAX_FRAMES = 120
    const val MAX_DURATION_MILLIS = 10000
    const val MAX_CANVAS_PIXELS = 2097152
    private val loop = byteArrayOf(0x21, 0xff.toByte(), 11) +
        "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 1, 0, 0, 0)

    fun validate(bytes: ByteArray): GifInfo {
        require(bytes.size in 1..MAX_BYTES) { "GIF exceeds the encoded size limit." }
        val input = bytes.copyOf()
        val info = normalize(input)
        require(input.contentEquals(info.bytes)) { "GIF transfer is not normalized." }
        return info
    }

    fun normalize(bytes: ByteArray): GifInfo {
        require(bytes.size in 1..MAX_BYTES) { "GIF exceeds the encoded size limit." }
        val input = Reader(bytes.copyOf())
        val signature = input.take(6).toString(Charsets.US_ASCII)
        require(signature == "GIF87a" || signature == "GIF89a") { "Use a GIF image." }
        val width = input.word()
        val height = input.word()
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "GIF canvas must be at most 256 by 256 pixels."
        }
        val packed = input.byte()
        val background = input.byte()
        input.byte() // Pixel aspect ratio is normalized to square pixels.
        val colors = if (packed and 0x80 != 0) 1 shl ((packed and 7) + 1) else 0
        require(colors == 0 || background < colors) { "Invalid GIF background index." }
        val palette = input.take(colors * 3)
        val output = ByteArrayOutputStream()
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        output.word(width)
        output.word(height)
        output.write(if (colors > 0) 0xf0 or (packed and 7) else 0x70)
        output.write(if (colors > 0) background else 0)
        output.write(0)
        output.write(palette)
        output.write(loop)
        var control: Control? = null
        var frames = 0
        var duration = 0
        while (true) {
            when (input.byte()) {
                0x21 -> when (input.byte()) {
                    0xf9 -> {
                        require(control == null && input.byte() == 4) { "Invalid GIF control." }
                        val flags = input.byte()
                        require(flags and 0xe2 == 0 && (flags ushr 2 and 7) <= 3) {
                            "Unsupported GIF disposal or user-input control."
                        }
                        val delay = input.word().coerceAtLeast(5)
                        val transparent = input.byte()
                        require(input.byte() == 0) { "Invalid GIF control terminator." }
                        control = Control(flags, delay, if (flags and 1 != 0) transparent else 0)
                    }
                    0xfe -> input.blocks() // Comments never reach the normalized asset.
                    0xff -> {
                        require(input.byte() == 11) { "Invalid GIF application block." }
                        val application = input.take(11).toString(Charsets.US_ASCII)
                        val data = input.blocks()
                        if (application == "NETSCAPE2.0" || application == "ANIMEXTS1.0") {
                            require(data.size == 3 && data[0].toInt() == 1) {
                                "Invalid GIF repeat control."
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported GIF extension.")
                }
                0x2c -> {
                    val x = input.word()
                    val y = input.word()
                    val frameWidth = input.word()
                    val frameHeight = input.word()
                    require(
                        frameWidth > 0 && frameHeight > 0 &&
                            x + frameWidth <= width && y + frameHeight <= height
                    ) {
                        "GIF frame lies outside its canvas."
                    }
                    frames++
                    require(
                        frames <= MAX_FRAMES &&
                            width.toLong() * height * frames <= MAX_CANVAS_PIXELS
                    ) {
                        "GIF exceeds the decoded frame budget."
                    }
                    val frameControl = control ?: Control(0, 5, 0)
                    control = null
                    duration += frameControl.delay * 10
                    require(duration <= MAX_DURATION_MILLIS) { "GIF loop exceeds ten seconds." }
                    val flags = input.byte()
                    require(flags and 0x18 == 0) { "Invalid GIF image flags." }
                    val localColors = if (flags and 0x80 != 0) 1 shl ((flags and 7) + 1) else 0
                    val localPalette = input.take(localColors * 3)
                    val colorCount = if (localColors > 0) localColors else colors
                    require(
                        colorCount > 0 &&
                            (frameControl.flags and 1 == 0 || frameControl.transparent < colorCount)
                    ) {
                        "GIF frame has no usable palette."
                    }
                    val minimum = input.byte()
                    require(minimum in 2..8) { "Invalid GIF LZW code size." }
                    val data = input.blocks()
                    val usedBits = validateLzw(data, minimum, frameWidth * frameHeight, colorCount)
                    if (usedBits % 8 != 0) {
                        data[data.lastIndex] =
                            (data.last().toInt() and ((1 shl (usedBits % 8)) - 1)).toByte()
                    }
                    output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, frameControl.flags.toByte()))
                    output.word(frameControl.delay)
                    output.write(frameControl.transparent)
                    output.write(0)
                    output.write(0x2c)
                    output.word(x)
                    output.word(y)
                    output.word(frameWidth)
                    output.word(frameHeight)
                    output.write(if (localColors > 0) flags and 0xc7 else flags and 0x40)
                    output.write(localPalette)
                    output.write(minimum)
                    output.blocks(data)
                }
                0x3b -> {
                    require(input.finished && control == null && frames > 0) {
                        "GIF is incomplete or has trailing data."
                    }
                    output.write(0x3b)
                    require(output.size() <= MAX_BYTES) {
                        "Normalized GIF exceeds the encoded limit."
                    }
                    return GifInfo(output.toByteArray(), width, height, frames, duration)
                }
                else -> throw IllegalArgumentException("Invalid GIF block.")
            }
        }
    }

    /** Decode indices without constructing RGBA frames. Dictionary and stack are capped at 4096. */
    private fun validateLzw(data: ByteArray, minimum: Int, pixels: Int, colors: Int): Int {
        val clear = 1 shl minimum
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096) { if (it < clear) it else 0 }
        val stack = IntArray(4096)
        val input = Bits(data)
        var size = minimum + 1
        var next = end + 1
        var previous = -1
        var first = 0
        var emitted = 0
        while (true) {
            val code = input.code(size)
            if (code == clear) {
                size = minimum + 1
                next = end + 1
                previous = -1
                continue
            }
            if (code == end) {
                require(emitted == pixels && data.size == (input.position + 7) / 8) {
                    "GIF LZW data does not match the frame."
                }
                return input.position
            }
            require(code <= next && (code != next || previous >= 0)) { "Invalid GIF LZW code." }
            var current = code
            var top = 0
            if (current == next) {
                stack[top++] = first
                current = previous
            }
            while (current >= clear) {
                require(current in (end + 1) until next && top < stack.size) {
                    "Invalid GIF LZW dictionary."
                }
                stack[top++] = suffix[current]
                current = prefix[current]
            }
            first = current
            require(top < stack.size) { "Invalid GIF LZW expansion." }
            stack[top++] = first
            while (top > 0) {
                require(stack[--top] < colors && ++emitted <= pixels) {
                    "GIF LZW palette or pixel count is invalid."
                }
            }
            if (previous >= 0 && next < 4096) {
                prefix[next] = previous
                suffix[next] = first
                next++
                if (next == 1 shl size && size < 12) size++
            }
            previous = code
        }
    }

    private data class Control(val flags: Int, val delay: Int, val transparent: Int)

    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        val finished get() = position == bytes.size
        fun byte(): Int {
            require(position < bytes.size) { "Truncated GIF." }
            return bytes[position++].toInt() and 255
        }
        fun word(): Int = byte() or (byte() shl 8)
        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= bytes.size - position) { "Truncated GIF." }
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
        fun blocks(): ByteArray {
            val data = ByteArrayOutputStream()
            while (true) {
                val count = byte()
                if (count == 0) return data.toByteArray()
                data.write(take(count))
            }
        }
    }

    private class Bits(private val bytes: ByteArray) {
        var position = 0
            private set
        fun code(size: Int): Int {
            require(size <= bytes.size * 8 - position) { "Truncated GIF LZW code." }
            var code = 0
            repeat(size) { bit ->
                val value = bytes[position / 8].toInt() ushr (position % 8) and 1
                code = code or (value shl bit)
                position++
            }
            return code
        }
    }

    private fun ByteArrayOutputStream.word(value: Int) {
        write(value and 255)
        write(value ushr 8 and 255)
    }

    private fun ByteArrayOutputStream.blocks(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(255, bytes.size - offset)
            write(count)
            write(bytes, offset, count)
            offset += count
        }
        write(0)
    }
}
