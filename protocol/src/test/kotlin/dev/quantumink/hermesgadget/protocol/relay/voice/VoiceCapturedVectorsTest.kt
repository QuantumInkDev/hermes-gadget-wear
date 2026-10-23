package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every captured exchange of the registered stack decodes with the native codec, byte lengths agree, the typed objects round-trip to the exact wire
 * object, and the closed tables are covered by REAL captures (anything not captured is named, never hand-painted).
 */
class VoiceCapturedVectorsTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    @Test fun manifestPinsEveryFileAndTheFoundationHashesOfTheF1Files() {
        val foundation = mapOf(
            "v3-downlink-abort-frame.json" to
                "6de2262381d309d4218cd910399e2d71473d494318d1c00b7a989a2b1829123b",
            "v3-downlink-opus-frames.json" to
                "d51a926da50421f3685bb052c42b4aabb13831811c9735d64085b46b87431cd8",
            "v3-downlink-pcm-frames.json" to
                "8b0a1cbd2173b42e9561a91fa7e11931b7fd6db3ac32a3174e9628caeca9356d",
            "v3-range-vectors.json" to
                "6a7ded1b1d9ea974d68c5c69c3b4ea0b3b2f57d5e7f06b0e36a0fe3fb159de3a",
            "v3-speak-responses.json" to
                "69cf54732ea6a1d3d7e7f04692fafee7c7074b52dd2b21b9a2f4a6cf0520ec95"
        )
        for (entry in VoiceVectors.files) {
            val name = entry["file"]!!.jsonPrimitive.content
            assertEquals(
                name,
                entry["sha256"]!!.jsonPrimitive.content,
                sha256(VoiceVectors.bytes(name))
            )
            if (name in
                foundation
            ) {
                assertEquals(name, foundation.getValue(name), sha256(VoiceVectors.bytes(name)))
            }
        }
        assertEquals(foundation.keys, VoiceVectors.f1Files.toSet())
        val producer = VoiceVectors.manifest["producer"]!!.jsonObject
        val f1Head = producer["sdk_head_of_f1_files"]!!.jsonPrimitive.content
        val admittedHead = producer["sdk_admitted_head"]!!.jsonPrimitive.content
        assertEquals("c6ee3068af90c674357f1912a8d567d17253694d", f1Head)
        assertEquals("d880ee47a38fcfaf072afbd38090d9cb0667f6a8", admittedHead)
        assertEquals(24, VoiceVectors.v4Files.size)
        assertEquals(5, VoiceVectors.f1Files.size)
    }

    @Test fun noFixtureHoldsAnAbsolutePathOrACredential() {
        for (name in VoiceVectors.f1Files + VoiceVectors.v4Files + "manifest.json") {
            val text = VoiceVectors.text(name)
            assertFalse(name, "/Users/" in text || "quantumdev" in text || "t3-8375" in text)
            assertFalse(
                name,
                Regex("(?i)(api[_-]?key|bearer |secret_key|sk-[a-z0-9]{8})").containsMatchIn(text)
            )
        }
        // The only content marker is the generated, labelled synthetic one.
        assertTrue(
            VoiceVectors.text("v4-input-result-terminal.json").contains("SECRET-TRANSCRIPT-4f1c9e")
        )
    }

    @Test fun capturedLabelsStateZeroRealProviderRequests() {
        for (file in VoiceVectors.v4Files) {
            val provenance = VoiceVectors.json(file)["provenance"]!!.jsonObject
            assertTrue(
                file,
                "zero REAL provider requests" in
                    provenance["synthetic_content"]!!.jsonPrimitive.content
            )
            assertTrue(
                file,
                "ONLY the socket and the core provider execution boundary are synthetic" in
                    provenance["rig"]!!.jsonPrimitive.content
            )
        }
        for (case in VoiceVectors.cases) {
            val synthetic = VoiceVectors.json(case.file)["cases"]!!.jsonArray.first {
                it.jsonObject["id"]!!.jsonPrimitive.content ==
                    case.id
            }
                .jsonObject["synthetic_provider"]?.jsonObject
            if (synthetic !=
                null
            ) {
                assertEquals(
                    case.toString(),
                    "0",
                    synthetic["real_provider_requests"]!!.jsonPrimitive.content
                )
            }
        }
    }

    @Test fun everyCapturedResponseDecodesToItsRecordedOutcome() {
        assertEquals(270, VoiceVectors.cases.size)
        for (case in VoiceVectors.cases) {
            val outcome = VoiceVectors.outcome(case)
            if (case.isSuccess) {
                assertEquals(case.toString(), "ok", case.outcome)
                assertTrue("$case -> $outcome", outcome is RelayOutcome.Success<*>)
            } else {
                assertTrue("$case -> $outcome", outcome is RelayOutcome.Failed)
                val error = (outcome as RelayOutcome.Failed).error
                assertEquals(case.toString(), "error:" + error.code.wire, case.outcome)
                assertEquals(case.toString(), case.errorCode, error.code.wire)
                assertTrue(case.toString(), error.code != RelayErrorCode.UNKNOWN)
            }
        }
    }

    @Test fun theRecordedByteLengthIsTheFrameAndWithinTheMethodBound() {
        for (case in VoiceVectors.cases) {
            assertEquals(
                case.toString(),
                case.responseText.toByteArray(Charsets.UTF_8).size,
                case.wireBytes
            )
            if (case.isSuccess) {
                assertTrue(
                    "$case ${case.wireBytes}",
                    case.wireBytes <= case.method.responseBytes
                )
            }
        }
        assertEquals(8_192, RelayMethod.VOICE_INFO.responseBytes)
        assertEquals(8_192, RelayMethod.VOICE_CAPTURE_START.responseBytes)
        assertEquals(32_768, RelayMethod.VOICE_CAPTURE_FINISH.responseBytes)
        assertEquals(32_768, RelayMethod.VOICE_CAPTURE_RESULT.responseBytes)
        assertEquals(8_192, RelayMethod.VOICE_CAPTURE_DISCARD.responseBytes)
        assertEquals(32_768, RelayMethod.VOICE_SPEAK.responseBytes)
        assertEquals(8_192, RelayMethod.VOICE_SPEAK_STOP.responseBytes)
    }

    @Test fun everyCapturedSuccessRoundTripsToTheExactWireObject() {
        var count = 0
        for (case in VoiceVectors.cases.filter { it.isSuccess }) {
            val value = (VoiceVectors.outcome(case) as RelayOutcome.Success<*>).value
            val wire: JsonObject = when (value) {
                is VoiceInfo -> value.toWire()
                is CaptureStarted -> value.toWire()
                is CaptureResult -> value.toWire()
                is SpeechResult -> value.toWire()
                else -> throw AssertionError("no encoder for ${value::class}")
            }
            assertEquals(case.toString(), case.result, wire)
            count++
        }
        assertTrue(count > 150)
        // The unchanged F1 speak answers too (their frames have no correlation id; the id is added here, nothing else).
        val f1 = VoiceVectors.json("v3-speak-responses.json")["cases"]!!.jsonObject
        var f1Success = 0
        for ((name, case) in f1) {
            for (member in listOf("response", "receipt")) {
                val result =
                    case.jsonObject[member]?.jsonObject?.get("result")?.jsonObject ?: continue
                assertEquals("$name.$member", result, SpeechResult.decode(result).toWire())
                f1Success++
            }
        }
        assertEquals(7, f1Success)
    }

    @Test fun everyAcceptedRequestIsTheBuildersOwnParams() {
        for (case in VoiceVectors.cases) {
            val shapeInvalid =
                case.outcome == "error:invalid_request" && case.id !in STATE_DEPENDENT_INVALID
            val call = VoiceCalls.fromWire(case.method, case.params)
            if (case.outcome == "error:unsupported") {
                // authorization and discovery precede parameter validation: the server never looked at the members
                continue
            }
            if (shapeInvalid) {
                assertNull("$case must be refused locally exactly as the server refused it", call)
            } else {
                assertNotNull(case.toString(), call)
                assertEquals(case.toString(), case.params, call!!.params)
                assertEquals(case.method, call.method)
            }
        }
    }

    @Test fun theServerRefusalsThatDependOnStateNotShapeAreNamed() {
        // The server refuses these for authority/state; the member set and types are valid, so the builders accept them.
        for (id in STATE_DEPENDENT_INVALID) {
            val case = VoiceVectors.byId(id)
            assertEquals(id, "error:invalid_request", case.outcome)
            assertNotNull(id, VoiceCalls.fromWire(case.method, case.params))
        }
    }

    @Test fun closedCaptureTablesAreCoveredByRealCapturesAndTheRestIsNamed() {
        val results = VoiceVectors.cases.filter {
            it.isSuccess &&
                it.method != RelayMethod.VOICE_INFO &&
                it.method != RelayMethod.VOICE_CAPTURE_START
        }
            .filter { it.method.wire.startsWith("relay.voice.capture") }
            .map { VoiceVectors.decodeResult(it.method, it.result) as CaptureResult }
        assertEquals(CaptureState.entries.toSet(), results.map { it.state }.toSet())
        assertEquals(
            CaptureFailure.entries.toSet() - CaptureFailure.AUDIO_DECODE,
            results.mapNotNull {
                it.reason
            }.toSet()
        )
        assertEquals(VoiceProvider.entries.toSet(), results.map { it.provider }.toSet())
        assertEquals(TranscriptSource.entries.toSet(), results.mapNotNull { it.source }.toSet())
        // The one capture state shape that needs a local-decoder boundary is NOT captured and says so.
        val notCaptured = VoiceVectors.v4Files.flatMap { file ->
            VoiceVectors.json(file)["not_captured"]!!.jsonArray.map {
                it.jsonObject["what"]!!.jsonPrimitive.content
            }
        }
        assertTrue("failed(audio_decode)" in notCaptured)
        assertTrue("unavailable(beyond_index)" in notCaptured)
    }

    @Test fun closedSpeechTablesAreCoveredByRealCapturesAndTheRestIsNamed() {
        val v4 = VoiceVectors.cases.filter {
            it.isSuccess &&
                it.method.wire.startsWith("relay.voice.speak")
        }
            .map { VoiceVectors.decodeResult(it.method, it.result) as SpeechResult }
        val f1 = VoiceVectors.json(
            "v3-speak-responses.json"
        )["cases"]!!.jsonObject.values.flatMap { case ->
            listOf("response", "receipt").mapNotNull {
                case.jsonObject[it]?.jsonObject?.get("result")?.jsonObject
            }
        }.map { SpeechResult.decode(it) }
        val all = v4 + f1
        assertEquals(SpeechState.entries.toSet(), all.map { it.state }.toSet())
        assertEquals(
            SpeechReason.entries.toSet() - SpeechReason.BEYOND_INDEX,
            all.mapNotNull {
                it.reason
            }.toSet()
        )
        assertEquals(VoiceProvider.entries.toSet(), all.map { it.provider }.toSet())
        // PURE derivation, not a capture: the F1 range file labels itself so.
        assertEquals(
            "f1-final-vectors",
            VoiceVectors.json(
                "v3-range-vectors.json"
            )["provenance"]!!.jsonObject["label"]!!.jsonPrimitive.content
        )
        assertEquals(
            listOf("constructed", "constructed"),
            VoiceVectors.json("v3-range-vectors.json")["constructed_negative"]!!.jsonArray.map {
                it.jsonObject["label"]!!.jsonPrimitive.content
            }
        )
    }

    @Test fun everyMethodHasCapturedSuccessAndErrorShapes() {
        for (method in RelayMethod.entries.filter { it.isVoice }) {
            val cases = VoiceVectors.forMethod(method)
            assertTrue(method.toString(), cases.any { it.isSuccess })
            assertTrue(method.toString(), cases.any { !it.isSuccess })
        }
        val codes = VoiceVectors.cases.mapNotNull { it.errorCode }.toSet()
        assertEquals(
            setOf(
                "invalid_request", "unsupported", "permission_denied", "not_found", "conflict",
                "expired", "busy", "stale_binding", "stale_epoch"
            ),
            codes
        )
    }

    @Test fun progressStatesCarryTheTruthfulProviderEvidence() {
        val progress = VoiceVectors.forMethod(RelayMethod.VOICE_CAPTURE_RESULT).filter {
            it.isSuccess
        }
            .map { VoiceVectors.decodeResult(it.method, it.result) as CaptureResult }
        val pairs = progress.map { it.state to it.provider }.toSet()
        assertTrue(CaptureState.QUEUED to VoiceProvider.NOT_STARTED in pairs)
        assertTrue(CaptureState.PREPARING to VoiceProvider.NOT_STARTED in pairs)
        assertTrue(CaptureState.TRANSCRIBING to VoiceProvider.IN_FLIGHT in pairs)
        assertTrue(CaptureState.UNKNOWN to VoiceProvider.UNKNOWN in pairs)
        // a pre-seal finish failure never names a row
        val preSeal = VoiceVectors.forMethod(RelayMethod.VOICE_CAPTURE_FINISH).filter {
            it.isSuccess
        }
            .map { VoiceVectors.decodeResult(it.method, it.result) as CaptureResult }.filter {
                it.state ==
                    CaptureState.FAILED
            }
        assertTrue(
            preSeal.isNotEmpty() &&
                preSeal.all { it.sealedAtMs == null && it.provider == VoiceProvider.NOT_STARTED }
        )
    }

    private companion object {
        /** invalid_request answers whose members and types are valid: the server refuses the VALUE against live authority state. */
        val STATE_DEPENDENT_INVALID = setOf(
            "start/error-invalid-format-opus-not-offered",
            "start/error-invalid-conversation-mismatch"
        )
    }
}
