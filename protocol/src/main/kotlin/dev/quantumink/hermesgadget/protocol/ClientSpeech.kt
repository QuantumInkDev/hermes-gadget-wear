package dev.quantumink.hermesgadget.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpeechRequest(
    val endpointId: String,
    val id: String,
    val text: String,
    val voice: String,
    val format: String
) {
    init {
        require(
            endpointId.matches(Regex("[a-f0-9]{64}")) && id.matches(Regex("[a-zA-Z0-9-]{1,64}"))
        )
        require(
            text.isNotBlank() && text.length <= 4096 && voice.length <= 128 &&
                format in setOf("pcm16", "opus")
        )
        require(voice.isEmpty() || voice.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
    }
    override fun toString(): String = "SpeechRequest(redacted)"
}

object ClientSpeech {
    const val CHANNEL_PATH = "/hermes/tts/audio/v1"
    const val SETTINGS_PATH = "/hermes/tts/config/v1/"
    fun endpointId(endpoint: Endpoint): String = MessageDigest.getInstance("SHA-256")
        .digest(endpoint.url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun writeMessage(output: OutputStream, message: Message) {
        val bytes = message.encode().toByteArray()
        require(bytes.size in 1..32768)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }
    fun readMessage(input: InputStream): Message {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..32768)
        return requireNotNull(
            Message.parse(ByteArray(size).also(stream::readFully).toString(Charsets.UTF_8))
        )
    }
    fun request(output: OutputStream, request: SpeechRequest) = writeMessage(
        output,
        Message.create(
            "tts.request",
            buildJsonObject {
                put("endpoint", request.endpointId)
                put("id", request.id)
                put("text", request.text)
                put("voice", request.voice)
                put("format", request.format)
            }
        )
    )
    fun request(input: InputStream): SpeechRequest {
        val message = readMessage(input)
        require(message.type == "tts.request")
        return SpeechRequest(
            requireNotNull(message.string("endpoint")),
            requireNotNull(message.string("id")),
            requireNotNull(message.string("text")),
            message.string("voice").orEmpty(),
            requireNotNull(message.string("format"))
        )
    }
    fun start(output: OutputStream, format: String, header: ByteArray? = null) = writeMessage(
        output,
        Message.create(
            "tts.start",
            buildJsonObject {
                put("format", format)
                if (header !=
                    null
                ) {
                    put(
                        "opus_header",
                        Base64.getEncoder().encodeToString(OpusConfiguration.header(header))
                    )
                }
            }
        )
    )
    fun packet(output: OutputStream, data: ByteArray) {
        require(data.size in 1..1275)
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(data)
            flush()
        }
    }
    fun finish(output: OutputStream) {
        DataOutputStream(output).apply {
            writeInt(0)
            flush()
        }
    }
    fun receive(
        input: InputStream,
        expected: String,
        start: (ByteArray?) -> Unit,
        packet: (ByteArray) -> Unit
    ) {
        val message = readMessage(input)
        require(message.type == "tts.start" && message.string("format") == expected)
        val header = if (expected == "opus") {
            val encoded = requireNotNull(message.string("opus_header"))
            require(encoded.length <= 88)
            OpusConfiguration.header(Base64.getDecoder().decode(encoded))
        } else {
            null
        }
        start(header)
        val stream = DataInputStream(input)
        var count = 0
        while (true) {
            val size = stream.readInt()
            if (size == 0) {
                require(count > 0)
                return
            }
            require(++count <= 3000 && size in 1..(if (expected == "opus") 1275 else 640))
            val data = ByteArray(size).also(stream::readFully)
            if (expected ==
                "opus"
            ) {
                OpusConfiguration.require20ms(data)
            } else {
                require(data.size % 2 == 0)
            }
            packet(data)
        }
    }
}

/** Strict streaming Ogg/Opus demux: bounded pages, CRC, serial/sequence, mono 20 ms packets. */
object OggOpus {
    fun read(input: InputStream, header: (ByteArray) -> Unit, packet: (ByteArray) -> Unit) {
        val stream = DataInputStream(input)
        val pending = java.io.ByteArrayOutputStream()
        var sequence = 0L
        var serial: ByteArray? = null
        var packets = 0
        var audio = 0
        var total = 0
        while (true) {
            val fixed = ByteArray(27).also(stream::readFully)
            require(
                fixed.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) && fixed[4].toInt() == 0
            )
            val flags = fixed[5].toInt() and 255
            require(flags and 248 == 0 && (flags and 1 != 0) == (pending.size() > 0))
            require((flags and 2 != 0) == (sequence == 0L))
            val identity = fixed.copyOfRange(14, 18)
            if (serial == null) serial = identity else require(identity.contentEquals(serial))
            val order =
                java.nio.ByteBuffer.wrap(
                    fixed,
                    18,
                    4
                ).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and
                    0xffffffffL
            require(order == sequence++)
            val laces = ByteArray(fixed[26].toInt() and 255).also(stream::readFully)
            val body = ByteArray(laces.sumOf { it.toInt() and 255 }).also(stream::readFully)
            total += fixed.size + laces.size + body.size
            require(total <= 4 * 1024 * 1024)
            val page = fixed + laces + body
            val expected = java.nio.ByteBuffer.wrap(
                page,
                22,
                4
            ).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            repeat(4) { page[22 + it] = 0 }
            var crc = 0
            for (byte in page) {
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            require(crc == expected)
            var offset = 0
            for (lace in laces) {
                val size = lace.toInt() and 255
                require(pending.size() + size <= if (packets < 2) 8192 else 1275)
                pending.write(body, offset, size)
                offset += size
                if (size < 255) {
                    val value = pending.toByteArray()
                    pending.reset()
                    when (packets++) {
                        0 -> {
                            require(value.size == 19)
                            header(OpusConfiguration.header(value))
                        }
                        1 -> require(
                            value.size >= 8 &&
                                value.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray())
                        )
                        else -> {
                            require(++audio <= 3000)
                            OpusConfiguration.require20ms(value)
                            packet(value)
                        }
                    }
                }
            }
            if (flags and 4 != 0) {
                require(pending.size() == 0 && audio > 0)
                return
            }
        }
    }
}
