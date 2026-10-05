package dev.quantumink.hermesgadget.protocol

class BinaryFrame(val channel: Int, val stream: Int, val sequence: Int, payload: ByteArray) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()

    fun encode(): ByteArray {
        require(channel in 1..255)
        require(bytes.size <= MAX_FRAME_BYTES - 4)
        return byteArrayOf(
            channel.toByte(),
            stream.toByte(),
            sequence.toByte(),
            (sequence ushr 8).toByte()
        ) +
            bytes
    }

    companion object {
        const val AUDIO = 1
        const val IMAGE = 2
        const val FIRMWARE = 3
        const val MAX_FRAME_BYTES = 1 shl 20

        /** Unknown channels are ignored; extensions must be explicitly enabled by the caller. */
        fun parse(data: ByteArray, additionalChannels: Set<Int> = emptySet()): BinaryFrame? {
            if (data.size !in 4..MAX_FRAME_BYTES) return null
            val channel = data[0].toInt() and 255
            if (channel !in setOf(AUDIO, IMAGE, FIRMWARE) &&
                channel !in additionalChannels
            ) {
                return null
            }
            return BinaryFrame(
                channel,
                data[1].toInt() and 255,
                (data[2].toInt() and 255) or ((data[3].toInt() and 255) shl 8),
                data.copyOfRange(4, data.size)
            )
        }
    }
}
