package dev.quantumink.hermesgadget.wear

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpusCodecTest {
    @Test
    fun measuresActualPlatformEncoderAndDecoderOnSyntheticTone() {
        var header: ByteArray? = null
        val packets = ArrayList<ByteArray>()
        val started = System.nanoTime()
        OpusEncoder().use { encoder ->
            repeat(150) { frame ->
                val pcm = ByteArray(640)
                repeat(320) { i ->
                    val sample = (sin(2 * PI * 440 * (frame * 320 + i) / 16000) * 8000).toInt()
                    pcm[i * 2] = sample.toByte()
                    pcm[i * 2 + 1] = (sample shr 8).toByte()
                }
                encoder.push(pcm, { header = it }, { packets.add(it) })
            }
            encoder.finish({ header = it }, { packets.add(it) })
        }
        assertTrue(packets.size in 150..153)
        val decoded = ByteArrayOutputStream()
        OpusDecoder(requireNotNull(header)).use { decoder ->
            packets.forEach { decoder.push(it) { pcm -> decoded.write(pcm) } }
            decoder.finish { decoded.write(it) }
        }
        assertTrue(decoded.size() in 285000..297000)
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "opus-uplink.json"
        ).writeText(
            JSONObject().put("header", Base64.getEncoder().encodeToString(requireNotNull(header)))
                .put(
                    "packets",
                    JSONArray(
                        packets.map {
                            Base64.getEncoder().encodeToString(it)
                        }
                    )
                ).toString()
        )
        val bytes = packets.sumOf { it.size }
        assertTrue(bytes < 20000)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "opus_measurement",
                    "synthetic_tone_s=3 pcm_payload_bytes=96000 opus_payload_bytes=" + bytes +
                        " packets=" + packets.size + " decoded_bytes=" + decoded.size() +
                        " codec_elapsed_ms=" + (System.nanoTime() - started) / 1000000
                )
            }
        )
    }

    @Test
    fun decodesFfmpegPacketsFromSdkExtension() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture =
            JSONObject(
                context.assets.open("opus-downlink.json").bufferedReader().use {
                    it.readText()
                }
            )
        val packets = fixture.getJSONArray("packets")
        var decoded = 0
        var energy = 0L
        OpusDecoder(Base64.getDecoder().decode(fixture.getString("header"))).use { decoder ->
            fun pcm(bytes: ByteArray) {
                decoded += bytes.size
                for (i in bytes.indices step 2) {
                    val sample = (
                        ((bytes[i + 1].toInt() and 255) shl 8) or
                            (bytes[i].toInt() and 255)
                        ).toShort().toInt()
                    energy += sample.toLong() * sample
                }
            }
            repeat(packets.length()) {
                decoder.push(Base64.getDecoder().decode(packets.getString(it)), ::pcm)
            }
            decoder.finish(::pcm)
        }
        assertTrue(decoded in 285000..297000 && energy > 1000000)
    }
}
