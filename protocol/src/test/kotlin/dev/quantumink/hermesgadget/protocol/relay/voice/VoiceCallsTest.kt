package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.BindingRef
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayEncoded
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seven typed builders: exact parameter sets (required nullable members present as null), local refusals, bounds and the inverse parse. */
class VoiceCallsTest {
    private val capture = CaptureId.of("01a14d67-a300-7000-9f3a-17469850b711")!!
    private val speech = SpeechId.of("01a14d67-a300-7000-bdbb-1b2c33245e9b")!!
    private val binding = BindingRef(BindingId.of("88ac42d3-bf80-449b-b0d1-5593fd7ea50a")!!, 1)
    private val conversation = ConversationId.of("fc25a8ad-dc63-4ed7-bc95-2066ab0172c2")!!
    private val message = MessageId.of("6e87dff2-3cf6-5fb9-91b4-11756446af6a")!!
    private val request = RequestId.of("01a14d69-299f-7000-a21e-649318111330")!!
    private val digest = SourceDigest.of(
        "27ebf69b1ecb7a358d86a4d53133ede23d2f3b9debb89e2603b250830983dddb"
    )!!
    private val stream = VoiceStream.of(7)!!

    @Test fun eachBuilderUsesItsMethodAndExactlyTheDocumentedMembers() {
        assertEquals(RelayMethod.VOICE_INFO, VoiceCalls.info().method)
        assertEquals(emptySet<String>(), VoiceCalls.info().params.keys)
        val start = VoiceCalls.captureStart(
            capture,
            binding,
            conversation,
            stream,
            CaptureFormat.PCM16
        )
        assertEquals(RelayMethod.VOICE_CAPTURE_START, start.method)
        assertEquals(
            setOf(
                "capture_id",
                "binding_id",
                "authority_epoch",
                "conversation_id",
                "stream",
                "format"
            ),
            start.params.keys
        )
        val finish = VoiceCalls.captureFinish(capture, stream, 13, 260)
        assertEquals(setOf("capture_id", "stream", "frames", "duration_ms"), finish.params.keys)
        assertEquals(
            setOf("capture_id", "wait_ms"),
            VoiceCalls.captureResult(capture, 20_000).params.keys
        )
        assertEquals(setOf("capture_id"), VoiceCalls.captureDiscard(capture).params.keys)
        assertEquals(setOf("speech_id"), VoiceCalls.stop(speech).params.keys)
        assertEquals(RelayMethod.VOICE_SPEAK_STOP, VoiceCalls.stop(speech).method)
    }

    @Test fun speakSendsAllElevenMembersAndRequiredNullsAreNullNotOmitted() {
        val server = SpeechDelivery.Server(CaptureFormat.OPUS)
        val keys =
            setOf(
                "speech_id", "binding_id", "authority_epoch", "mode", "request_id", "message_id",
                "source_sha256", "start_cp", "style", "output", "format"
            )
        val live = VoiceCalls.speak(
            binding,
            SpeechRequest.Live(speech, request, null, SpeechStyle.FULL, server)
        ).params
        assertEquals(keys, live.keys)
        assertEquals(JsonNull, live["message_id"])
        assertEquals(JsonNull, live["source_sha256"])
        assertEquals(JsonPrimitive(0), live["start_cp"])
        assertEquals(JsonPrimitive("opus"), live["format"])
        val replay = VoiceCalls.speak(
            binding,
            SpeechRequest.Replay(speech, message, SpeechStyle.CONCISE, SpeechDelivery.Client)
        ).params
        assertEquals(keys, replay.keys)
        assertEquals(JsonNull, replay["request_id"])
        assertEquals(JsonNull, replay["source_sha256"])
        assertEquals(JsonNull, replay["format"])
        assertEquals(JsonPrimitive("client"), replay["output"])
        val more = VoiceCalls.speak(
            binding,
            SpeechRequest.More(speech, message, digest, 41, SpeechStyle.FULL, server)
        ).params
        assertEquals(keys, more.keys)
        assertEquals(JsonNull, more["request_id"])
        assertEquals(JsonPrimitive(41), more["start_cp"])
        assertEquals(JsonPrimitive(digest.value), more["source_sha256"])
        assertEquals(JsonPrimitive("more"), more["mode"])
    }

    @Test fun anIllegalModeMixCannotBeBuilt() {
        assertTrue(
            runCatching {
                SpeechRequest.More(
                    speech,
                    message,
                    digest,
                    -1,
                    SpeechStyle.FULL,
                    SpeechDelivery.Client
                )
            }.isFailure
        )
        assertTrue(runCatching { VoiceCalls.captureFinish(capture, stream, 0, 0) }.isFailure)
        assertTrue(runCatching { VoiceCalls.captureFinish(capture, stream, 1, -1) }.isFailure)
        assertTrue(runCatching { VoiceCalls.captureResult(capture, 20_001) }.isFailure)
        assertTrue(runCatching { VoiceCalls.captureResult(capture, -1) }.isFailure)
        assertNull(VoiceStream.of(0))
        assertNull(VoiceStream.of(256))
        // a v5 id is not a capture id
        assertNull(CaptureId.of("6e87dff2-3cf6-5fb9-91b4-11756446af6a"))
        assertNull(CaptureId.of(capture.value.uppercase()))
        assertNull(SourceDigest.of("A".repeat(64)))
        assertNull(SourceDigest.of("a".repeat(63)))
    }

    @Test fun everyBuilderRoundTripsThroughItsInverseParse() {
        val calls = listOf(
            VoiceCalls.info(),
            VoiceCalls.captureStart(capture, binding, conversation, stream, CaptureFormat.OPUS),
            VoiceCalls.captureFinish(capture, stream, 3_000, 60_000),
            VoiceCalls.captureResult(capture, 0),
            VoiceCalls.captureDiscard(capture),
            VoiceCalls.speak(
                binding,
                SpeechRequest.Live(
                    speech,
                    request,
                    message,
                    SpeechStyle.FULL,
                    SpeechDelivery.Server(CaptureFormat.PCM16)
                )
            ),
            VoiceCalls.speak(
                binding,
                SpeechRequest.Replay(speech, message, SpeechStyle.CONCISE, SpeechDelivery.Client)
            ),
            VoiceCalls.speak(
                binding,
                SpeechRequest.More(
                    speech,
                    message,
                    digest,
                    Int.MAX_VALUE,
                    SpeechStyle.CONCISE,
                    SpeechDelivery.Server(CaptureFormat.OPUS)
                )
            ),
            VoiceCalls.stop(speech)
        )
        for (call in calls) {
            val parsed = RelayCalls.fromWire(call.method, call.params)
            assertNotNull(call.method.toString(), parsed)
            assertEquals(call.params, parsed!!.params)
        }
    }

    @Test fun theInverseRefusesWhatTheServerRefuses() {
        val ok = VoiceCalls.speak(
            binding,
            SpeechRequest.Replay(
                speech,
                message,
                SpeechStyle.FULL,
                SpeechDelivery.Server(CaptureFormat.PCM16)
            )
        ).params
        fun refusedWith(vararg changes: Pair<String, kotlinx.serialization.json.JsonElement>) =
            RelayCalls.fromWire(RelayMethod.VOICE_SPEAK, JsonObject(ok + changes.toMap())) == null
        assertTrue(refusedWith("format" to JsonNull))
        assertTrue(refusedWith("start_cp" to JsonPrimitive(3)))
        assertTrue(refusedWith("request_id" to JsonPrimitive(request.value)))
        assertTrue(refusedWith("message_id" to JsonNull))
        assertTrue(refusedWith("output" to JsonPrimitive("client")))
        assertTrue(refusedWith("style" to JsonPrimitive("loud")))
        assertTrue(RelayCalls.fromWire(RelayMethod.VOICE_SPEAK, JsonObject(ok - "format")) == null)
        assertTrue(
            RelayCalls.fromWire(
                RelayMethod.VOICE_INFO,
                JsonObject(mapOf("x" to JsonPrimitive(1)))
            ) ==
                null
        )
        assertNotNull(RelayCalls.fromWire(RelayMethod.VOICE_SPEAK, ok))
    }

    @Test fun eachRequestEncodesInsideTheSharedRelayFrameBound() {
        val call = VoiceCalls.speak(
            binding,
            SpeechRequest.More(
                speech,
                message,
                digest,
                262_144,
                SpeechStyle.FULL,
                SpeechDelivery.Server(CaptureFormat.OPUS)
            )
        )
        val encoded = RelayEnvelope.encodeRequest(CorrelationId.of("v4-1")!!, call)
        assertTrue(encoded is RelayEncoded.Frame)
        assertTrue((encoded as RelayEncoded.Frame).bytes < 1_000)
        assertTrue(
            encoded.text.contains("\"request_id\":null") &&
                encoded.text.contains("\"mode\":\"more\"")
        )
    }
}
