package dev.quantumink.hermesgadget.protocol.relay.voice

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix round 1: the ONE focused real `failed(audio_decode)` capture. Three fixed corrupt Opus packet bodies whose header and 20 ms TOC checks pass were
 * sent through the registered rig; the REAL ffmpeg decoder (no replaced decoder) refused each, so the producer answered `failed(audio_decode)` with
 * `provider = not_started`. Only the socket and the provider boundary were synthetic (zero real provider requests).
 *
 * LABEL: the re-keyed ledger step below (`rekeyed`) is a SYNTHETIC test input: the captured result fields under the harness' own capture id.
 */
class VoiceAudioDecodeVectorTest {
    private val file = "v4-input-opus-decode-failure.json"
    private val groups = listOf(
        "A-code3-uneven-cbr",
        "B-code1-odd-payload",
        "C-code2-length-overrun"
    )

    private fun result(group: String, step: String): CaptureResult {
        val case = VoiceVectors.load(file).first { it.id == "audio-decode/$group/$step" }
        return VoiceVectors.decodeResult(case.method, case.result) as CaptureResult
    }

    private fun uplink(group: String): List<ByteArray> = VoiceVectors.binary(file).filter {
        it["case"]!!.jsonPrimitive.content == "audio-decode/$group" && "raw_hex" in it
    }.map { VoiceVectors.hex(it["raw_hex"]!!.jsonPrimitive.content) }

    @Test fun theFixtureIsItsOwnGenerationAndNamesNothingUncaptured() {
        assertEquals(listOf(file), VoiceVectors.fix1Files)
        assertEquals(24, VoiceVectors.v4Files.size)
        assertFalse(file in VoiceVectors.v4Files)
        val json = VoiceVectors.json(file)
        assertTrue(json["captured_audio_decode"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(json["not_captured"]!!.jsonArray.isEmpty())
        val provenance = json["provenance"]!!.jsonObject
        assertTrue(
            "zero REAL provider requests" in provenance["synthetic_content"]!!.jsonPrimitive.content
        )
        assertTrue(
            "ONLY the socket and the core provider execution boundary are synthetic" in
                provenance["rig"]!!.jsonPrimitive.content
        )
        val text = VoiceVectors.text(file)
        assertFalse("/Users/" in text || "quantumdev" in text || "t3-8375" in text)
        for (case in VoiceVectors.load(file)) {
            assertEquals(case.toString(), "ok", case.outcome)
            assertEquals(
                case.toString(),
                case.responseText.toByteArray(Charsets.UTF_8).size,
                case.wireBytes
            )
            assertEquals(
                "0",
                json["cases"]!!.jsonArray.first {
                    it.jsonObject["id"]!!.jsonPrimitive.content == case.id
                }.jsonObject["synthetic_provider"]?.jsonObject?.get(
                    "real_provider_requests"
                )?.jsonPrimitive?.content ?: "0"
            )
        }
    }

    @Test fun everyCorruptBodyIsQueuedAtFinishThenFailedAudioDecodeWithNoProviderStarted() {
        for (group in groups) {
            val finish = result(group, "finish")
            assertEquals(group, CaptureState.QUEUED, finish.state)
            assertEquals(group, VoiceProvider.NOT_STARTED, finish.provider)
            assertNull(finish.reason)
            val last = result(group, "result")
            assertEquals(group, CaptureState.FAILED, last.state)
            assertEquals(group, CaptureFailure.AUDIO_DECODE, last.reason)
            assertEquals(group, VoiceProvider.NOT_STARTED, last.provider)
            assertNotNull("sealed, so it names a row", last.sealedAtMs)
            assertNull(last.transcript)
        }
    }

    @Test fun theNativeUplinkRulesAcceptTheSameBodiesSoOnlyTheServerDecoderCanRefuseThem() {
        for (group in groups) {
            val raws = uplink(group)
            assertEquals(group, 14, raws.size)
            var capture = UplinkCapture.start(VoiceStream.of(4)!!, CaptureFormat.OPUS)
            raws.forEachIndexed { index, raw ->
                val parsed = VoiceFrame.parse(raw, VoiceDirection.UPLINK)
                val frame = (parsed as VoiceParse.Parsed).frame
                assertEquals(index, frame.sequence)
                val step = if (index == 0) {
                    assertEquals(VoiceFrameKind.UPLINK_OPUS_HEADER, frame.kind)
                    capture.header(frame.body)
                } else {
                    assertEquals(VoiceFrameKind.UPLINK_AUDIO, frame.kind)
                    assertTrue(OpusRules.isTwentyMsPacket(frame.body))
                    capture.opus(frame.body)
                }
                assertNull("$group frame $index ${step.refusal}", step.refusal)
                assertArrayEquals(raw, step.frame!!.encode())
                capture = step.capture
            }
            val counts = capture.counts()!!
            val finishCase = VoiceVectors.load(file).first { it.id == "audio-decode/$group/finish" }
            assertEquals(
                finishCase.params["frames"]!!.jsonPrimitive.content.toInt(),
                counts.frames
            )
            assertEquals(
                finishCase.params["duration_ms"]!!.jsonPrimitive.content.toInt(),
                counts.durationMs
            )
        }
    }

    @Test fun theLedgerEndsAnAudioDecodeFailureAsFailedAndNeverBilled() {
        for (group in groups) {
            val v = VoiceHarness()
            val token = v.h.attach()
            val key = v.released(token)
            val captured = result(group, "result")
            val rekeyed = v.result(
                key,
                captured.state,
                captured.reason,
                captured.provider,
                sealed = captured.sealedAtMs
            )
            v.onResult(key, rekeyed)
            val entry = v.entry(key)
            assertEquals(group, CaptureEnd.FAILED, entry.end)
            assertEquals(group, CaptureBilling.NOT_BILLED, entry.billing)
            assertEquals(group, VoiceProvider.NOT_STARTED, entry.provider)
        }
    }
}
