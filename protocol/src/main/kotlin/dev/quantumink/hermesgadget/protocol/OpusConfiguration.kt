package dev.quantumink.hermesgadget.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android unified CSD is a container, not an Opus packet or RFC 7845 header. */
object OpusConfiguration {
    fun header(csd: ByteArray): ByteArray {
        require(csd.size in 19..512)
        val head = if (csd.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) {
            csd.copyOf()
        } else {
            var offset = 0
            var found: ByteArray? = null
            while (offset + 16 <= csd.size) {
                val marker = csd.copyOfRange(offset, offset + 8).toString(Charsets.US_ASCII)
                val size = ByteBuffer.wrap(csd, offset + 8, 8).order(ByteOrder.nativeOrder()).long
                require(size in 1..64 && offset + 16 + size <= csd.size)
                if (marker == "AOPUSHDR") {
                    require(found == null)
                    found = csd.copyOfRange(offset + 16, offset + 16 + size.toInt())
                } else {
                    require(marker in setOf("AOPUSDLY", "AOPUSPRL") && size == 8L)
                }
                offset += 16 + size.toInt()
            }
            require(offset == csd.size)
            requireNotNull(found)
        }
        require(head.size == 19 && head.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray()))
        require(head[8].toInt() == 1 && head[9].toInt() == 1 && head[18].toInt() == 0)
        return head
    }

    fun require20ms(packet: ByteArray) {
        require(packet.size in 1..1275)
        val toc = packet[0].toInt() and 255
        val samples = when {
            toc and 128 != 0 -> 120 shl ((toc shr 3) and 3)
            toc and 96 == 96 -> if (toc and 8 != 0) 960 else 480
            ((toc shr 3) and 3) == 3 -> 2880
            else -> 480 shl ((toc shr 3) and 3)
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> {
                require(packet.size >= 2)
                packet[1].toInt() and 63
            }
        }
        require(samples * frames == 960) { "Only 20 ms Opus packets are supported." }
    }
}
