package dev.quantumink.hermesgadget.wear

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import dev.quantumink.hermesgadget.protocol.OpusConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** One owner thread; bounded synchronous codec calls. No recording is stored. */
class OpusEncoder : AutoCloseable {
    private val format = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_OPUS,
        16000,
        1
    ).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 24000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 640)
    }
    private val codec = MediaCodec.createByCodecName(
        requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format))
    ).apply {
        try {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }
    private var samples = 0L
    private var header: ByteArray? = null
    private var ended = false

    fun push(pcm: ByteArray, onHeader: (ByteArray) -> Unit, onPacket: (ByteArray) -> Unit) {
        require(!ended && pcm.size in 2..640 && pcm.size % 2 == 0)
        val index = codec.dequeueInputBuffer(10000)
        check(index >= 0)
        requireNotNull(codec.getInputBuffer(index)).apply {
            clear()
            put(pcm)
        }
        codec.queueInputBuffer(index, 0, pcm.size, samples * 1000000 / 16000, 0)
        samples += pcm.size / 2
        drain(false, onHeader, onPacket)
    }

    fun finish(onHeader: (ByteArray) -> Unit, onPacket: (ByteArray) -> Unit) {
        check(!ended)
        val index = codec.dequeueInputBuffer(10000)
        check(index >= 0)
        codec.queueInputBuffer(
            index,
            0,
            0,
            samples * 1000000 / 16000,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        ended = true
        drain(true, onHeader, onPacket)
    }

    private fun drain(
        final: Boolean,
        onHeader: (ByteArray) -> Unit,
        onPacket: (ByteArray) -> Unit
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val info = MediaCodec.BufferInfo()
        while (System.nanoTime() < deadline) {
            when (val index = codec.dequeueOutputBuffer(info, if (final) 10000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!final) return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codec.outputFormat.getByteBuffer("csd-0")?.let { buffer ->
                        val copy = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                        acceptHeader(copy, onHeader)
                    }
                }
                else -> if (index >= 0) {
                    try {
                        val bytes = ByteArray(info.size)
                        codec.getOutputBuffer(index)?.duplicate()?.apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            get(bytes)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            acceptHeader(bytes, onHeader)
                        } else if (bytes.isNotEmpty()) {
                            check(header != null)
                            OpusConfiguration.require20ms(bytes)
                            onPacket(bytes)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        check(!final) { "Opus encoder drain timed out." }
    }

    private fun acceptHeader(bytes: ByteArray, accepted: (ByteArray) -> Unit) {
        if (header != null) return
        header = OpusConfiguration.header(bytes)
        accepted(requireNotNull(header).copyOf())
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}

class OpusDecoder(header: ByteArray) : AutoCloseable {
    private val codec: MediaCodec
    private var packets = 0L
    init {
        require(
            header.size == 19 && header.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())
        )
        require(header[9].toInt() == 1 && header[18].toInt() == 0)
        val skip = (header[10].toInt() and 255) or ((header[11].toInt() and 255) shl 8)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            16000,
            1
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(header.copyOf()))
            setByteBuffer(
                "csd-1",
                ByteBuffer.allocate(
                    8
                ).order(ByteOrder.nativeOrder()).putLong(skip * 1000000000L / 48000).apply {
                    flip()
                }
            )
            setByteBuffer(
                "csd-2",
                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).apply {
                    flip()
                }
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1275)
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (failure: Exception) {
            codec.release()
            throw failure
        }
    }

    fun push(packet: ByteArray, pcm: (ByteArray) -> Unit) {
        require(packet.size in 1..1275)
        val index = input(pcm)
        requireNotNull(codec.getInputBuffer(index)).apply {
            clear()
            put(packet)
        }
        codec.queueInputBuffer(index, 0, packet.size, packets++ * 20000, 0)
        drain(false, pcm)
    }

    fun finish(pcm: (ByteArray) -> Unit) {
        val index = input(pcm)
        codec.queueInputBuffer(index, 0, 0, packets * 20000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true, pcm)
    }

    private fun input(pcm: (ByteArray) -> Unit): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueInputBuffer(10000)
            if (index >= 0) return index
            check(index == MediaCodec.INFO_TRY_AGAIN_LATER)
            drain(false, pcm)
        }
        error("Opus decoder input timed out.")
    }

    private fun drain(final: Boolean, pcm: (ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueOutputBuffer(info, if (final) 10000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER && !final) return
            if (index ==
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                check(codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000)
            }
            if (index >= 0) {
                try {
                    require(info.size <= 3840)
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        requireNotNull(codec.getOutputBuffer(index)).duplicate().apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            get(bytes)
                        }
                        pcm(bytes)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        check(!final) { "Opus decoder drain timed out." }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}

object OpusSupport {
    fun probe(): Boolean = runCatching {
        var header: ByteArray? = null
        val packets = ArrayList<ByteArray>()
        OpusEncoder().use { encoder ->
            repeat(15) { encoder.push(ByteArray(640), { header = it }, { packets.add(it) }) }
            encoder.finish({ header = it }, { packets.add(it) })
        }
        var decoded = 0
        OpusDecoder(requireNotNull(header)).use { decoder ->
            packets.forEach { decoder.push(it) { bytes -> decoded += bytes.size } }
            decoder.finish { decoded += it.size }
        }
        packets.size in 14..17 && decoded in 25000..35000
    }.getOrDefault(false)
}
