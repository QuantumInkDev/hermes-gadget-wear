package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayProblem
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Strictness of the voice DTOs. The property runs over every REAL captured success: any single deviation (an unknown member, a missing member -
 * including a required NULLABLE one, a wrong type, an unknown enum spelling, a malformed id) must be [RelayOutcome.Invalid], never a success, never a
 * throw. Voice members are all required: unlike the older builders there is no "optional member" escape.
 */
class VoiceDtoStrictnessTest {
    private fun frame(result: JsonObject, id: String = "m1"): String = RelayVectors.asciiJson(
        JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("relay.response"),
                "v" to JsonPrimitive(1),
                "id" to JsonPrimitive(id),
                "result" to result
            )
        )
    )

    private fun outcomeOf(method: RelayMethod, result: JsonObject): RelayOutcome<Any> {
        val call = RelayCall(method, JsonObject(emptyMap()), VoiceCalls.resultDecoder(method))
        return call.interpret(CorrelationId.of("m1")!!, RelayEnvelope.decodeFrame(frame(result)))
    }

    private fun assertInvalid(method: RelayMethod, result: JsonObject, what: String) {
        val outcome = try {
            outcomeOf(method, result)
        } catch (thrown: Throwable) {
            throw AssertionError("$what threw $thrown", thrown)
        }
        assertTrue("$what -> $outcome", outcome is RelayOutcome.Invalid)
    }

    private class Mutation(
        val description: String,
        val result: JsonObject,
        val mayStayValid: Boolean
    )

    private val enumKeys =
        setOf("state", "reason", "provider", "source", "format", "mode", "style", "output")

    private fun mutations(root: JsonObject): List<Mutation> {
        val out = ArrayList<Mutation>()
        fun walk(node: JsonElement, path: String, replace: (JsonElement) -> JsonObject) {
            when (node) {
                is JsonObject -> {
                    out.add(
                        Mutation(
                            "$path +unknown",
                            replace(JsonObject(node + ("zz_unknown" to JsonPrimitive(1)))),
                            false
                        )
                    )
                    for ((key, value) in node) {
                        val here = "$path.$key"
                        fun swap(newValue: JsonElement?): JsonObject = replace(
                            JsonObject(
                                LinkedHashMap(node).apply {
                                    if (newValue == null) remove(key) else put(key, newValue)
                                }
                            )
                        )
                        // EVERY member is required in the voice wire, nullable ones included.
                        out.add(Mutation("$here -missing", swap(null), false))
                        val flipped: JsonElement? = when {
                            value is JsonNull -> null
                            value is JsonObject || value is JsonArray -> JsonPrimitive("x")
                            value is JsonPrimitive && value.isString -> JsonPrimitive(7)
                            else -> JsonPrimitive("7")
                        }
                        if (flipped !=
                            null
                        ) {
                            out.add(Mutation("$here -wrongtype", swap(flipped), false))
                        }
                        if (value is JsonPrimitive && value.isString && key in enumKeys) {
                            out.add(Mutation("$here -enum", swap(JsonPrimitive("bogus")), false))
                        }
                        if (value is JsonPrimitive && value.isString && key.endsWith("_id")) {
                            out.add(Mutation("$here -id", swap(JsonPrimitive("NOT-A-UUID")), false))
                        }
                        if (value is JsonPrimitive && !value.isString &&
                            value.content.toLongOrNull() != null
                        ) {
                            out.add(Mutation("$here -negative", swap(JsonPrimitive(-1)), false))
                            out.add(Mutation("$here -float", swap(JsonPrimitive(1.5)), false))
                        }
                        walk(value, here) { replacement -> swap(replacement) }
                    }
                }
                is JsonArray -> node.forEachIndexed { index, element ->
                    walk(element, "$path[$index]") { replacement ->
                        replace(
                            JsonArray(
                                node.toMutableList().also {
                                    it[index] =
                                        replacement
                                }
                            )
                        )
                    }
                    out.add(
                        Mutation(
                            "$path[$index] -wrongtype",
                            replace(
                                JsonArray(
                                    node.toMutableList().also {
                                        it[index] =
                                            JsonPrimitive(7)
                                    }
                                )
                            ),
                            false
                        )
                    )
                }
                else -> Unit
            }
        }
        walk(root, "result") { it as JsonObject }
        return out
    }

    private fun successes(): List<Pair<RelayMethod, JsonObject>> {
        val captured = VoiceVectors.cases.filter { it.isSuccess }.map { it.method to it.result }
        val f1 = VoiceVectors.json(
            "v3-speak-responses.json"
        )["cases"]!!.jsonObject.values.flatMap { case ->
            listOf("response", "receipt").mapNotNull {
                case.jsonObject[it]?.jsonObject?.get("result")?.jsonObject
            }
        }.map { RelayMethod.VOICE_SPEAK to it }
        return captured + f1
    }

    @Test fun anySingleDeviationFromARealCapturedSuccessIsInvalidAndNeverThrows() {
        var executed = 0
        val seen = mutableSetOf<RelayMethod>()
        for ((method, result) in successes()) {
            seen += method
            for (mutation in mutations(result)) {
                executed++
                assertInvalid(method, mutation.result, "$method ${mutation.description}")
            }
        }
        assertEquals(
            RelayMethod.entries.filter { it.isVoice }.toSet() - RelayMethod.VOICE_SPEAK_STOP -
                RelayMethod.VOICE_CAPTURE_DISCARD,
            seen - RelayMethod.VOICE_SPEAK_STOP - RelayMethod.VOICE_CAPTURE_DISCARD
        )
        assertTrue("only $executed mutations", executed > 5_000)
    }

    @Test fun theUnmutatedControlSuccessesAreAcceptedSoTheMutationsAreNotVacuous() {
        var accepted = 0
        for ((method, result) in successes()) {
            assertTrue("$method $result", outcomeOf(method, result) is RelayOutcome.Success<*>)
            accepted++
        }
        assertTrue(accepted > 150)
    }

    @Test fun versionMustBeTheIntegerOneExactly() {
        val info = VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first { it.isSuccess }.result
        for (bad in listOf<JsonElement>(
            JsonPrimitive(0),
            JsonPrimitive(2),
            JsonPrimitive(true),
            JsonPrimitive("1"),
            JsonPrimitive(1.0),
            JsonNull
        )) {
            assertInvalid(
                RelayMethod.VOICE_INFO,
                JsonObject(info + ("version" to bad)),
                "version $bad"
            )
        }
        assertTrue(outcomeOf(RelayMethod.VOICE_INFO, info) is RelayOutcome.Success<*>)
    }

    private fun infoWith(path: List<String>, value: JsonElement): JsonObject {
        val info = VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first {
            it.isSuccess &&
                it.result["input"] !is JsonNull
        }.result
        fun set(node: JsonObject, keys: List<String>): JsonObject = if (keys.size == 1) {
            JsonObject(node + (keys[0] to value))
        } else {
            JsonObject(node + (keys[0] to set(node[keys[0]]!!.jsonObject, keys.drop(1))))
        }
        return set(info, path)
    }

    @Test fun infoNumbersAreBoundedByTheirSourceConstantsAndTheCodecIsExact() {
        fun check(path: List<String>, good: Long, vararg bad: Long) {
            assertTrue(
                "$path=$good",
                outcomeOf(
                    RelayMethod.VOICE_INFO,
                    infoWith(path, JsonPrimitive(good))
                ) is RelayOutcome.Success<*>
            )
            for (value in bad) {
                assertInvalid(
                    RelayMethod.VOICE_INFO,
                    infoWith(path, JsonPrimitive(value)),
                    "$path=$value"
                )
            }
        }
        check(listOf("input", "rate"), 16_000, 8_000, 48_000, 0)
        check(listOf("input", "frame_ms"), 20, 10, 40)
        check(listOf("input", "channel"), 5, 4, 6)
        check(listOf("input", "max_ms"), 60_000, 0, 60_001)
        check(listOf("input", "min_ms"), 250, 0, 60_001)
        check(listOf("input", "max_frames"), 3_000, 0, 3_001)
        check(listOf("input", "max_pcm_bytes"), 1_920_000, 0, 1_920_001)
        check(listOf("input", "max_opus_bytes"), 1_000_000, 0, 1_000_001)
        check(listOf("limits", "transcript_code_points"), 4_000, 0, 4_001)
        check(listOf("limits", "transcript_bytes"), 16_384, 0, 16_385)
        check(listOf("limits", "result_wait_ms"), 20_000, -1, 20_001)
        check(listOf("limits", "client_chunk_code_points"), 450, 0, 451)
        check(listOf("limits", "speakable_bytes"), 262_144, 0, 262_145)
        check(listOf("limits", "concise_sentences"), 3, 0, 4)
        check(listOf("limits", "full_code_points"), 3_000, 0, 3_001)
        check(listOf("limits", "range_utf16_units"), 4_096, 0, 4_097)
        // a minimum above the maximum can never be a truthful advertisement
        assertInvalid(
            RelayMethod.VOICE_INFO,
            infoWith(listOf("input", "min_ms"), JsonPrimitive(60_000)).let {
                JsonObject(
                    it +
                        (
                            "input" to
                                JsonObject(
                                    it["input"]!!.jsonObject + ("max_ms" to JsonPrimitive(1_000))
                                )
                            )
                )
            },
            "min above max"
        )
    }

    @Test fun infoFormatsAndModesAreDistinctKnownNonEmptyTokens() {
        fun formats(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })
        assertTrue(
            outcomeOf(
                RelayMethod.VOICE_INFO,
                infoWith(listOf("input", "formats"), formats("opus", "pcm16"))
            ) is RelayOutcome.Success<*>
        )
        for (bad in listOf(
            formats(),
            formats("pcm16", "pcm16"),
            formats("wav"),
            formats("PCM16"),
            formats("opus", "pcm16", "opus"),
            JsonPrimitive("pcm16")
        )) {
            assertInvalid(
                RelayMethod.VOICE_INFO,
                infoWith(listOf("input", "formats"), bad),
                "formats $bad"
            )
        }
        assertInvalid(
            RelayMethod.VOICE_INFO,
            infoWith(listOf("input", "formats"), JsonArray(listOf(JsonPrimitive(1)))),
            "formats number"
        )
        val both = VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first {
            it.isSuccess &&
                it.result["output"] !is JsonNull
        }.result
        for (bad in listOf(
            JsonArray(emptyList()),
            JsonArray(
                listOf("server", "server").map {
                    JsonPrimitive(it)
                }
            ),
            JsonArray(listOf(JsonPrimitive("both")))
        )) {
            val output = JsonObject(both["output"]!!.jsonObject + ("modes" to bad))
            assertInvalid(
                RelayMethod.VOICE_INFO,
                JsonObject(both + ("output" to output)),
                "modes $bad"
            )
        }
    }

    @Test fun infoMayBeNullPerDirectionAndNegotiatesOnlyWhatBothEndsCanProcess() {
        val inputOnly = VoiceInfo.decode(
            VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first {
                it.isSuccess &&
                    it.result["output"] is JsonNull
            }.result
        )
        assertTrue(inputOnly.input != null && inputOnly.output == null)
        val outputOnly = VoiceInfo.decode(
            VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first {
                it.isSuccess &&
                    it.result["input"] is JsonNull
            }.result
        )
        assertTrue(outputOnly.input == null && outputOnly.output != null)
        val both = VoiceInfo.decode(
            VoiceVectors.forMethod(RelayMethod.VOICE_INFO).first {
                it.isSuccess &&
                    it.result["input"] !is JsonNull &&
                    it.result["output"] !is JsonNull
            }.result
        )
        val ours = setOf(CaptureFormat.PCM16)
        assertEquals(listOf(CaptureFormat.PCM16), both.negotiate(ours, ours).captureFormats)
        assertFalse(both.negotiate(emptySet(), emptySet()).canCapture)
        assertFalse(both.negotiate(emptySet(), emptySet()).serverSpeechFormats.isNotEmpty())
        assertTrue(inputOnly.negotiate(ours, ours).canCapture)
        assertFalse(inputOnly.negotiate(ours, ours).canSpeak)
        assertTrue(both.negotiate(ours, ours).canSpeak)
    }

    @Test fun transcriptBoundsAreCodePointsAndUtf8BytesNeverUtf16() {
        val base = VoiceVectors.forMethod(RelayMethod.VOICE_CAPTURE_RESULT).first {
            it.isSuccess &&
                it.result["state"].toString() == "\"transcribed\""
        }.result
        fun with(text: String) = JsonObject(base + ("transcript" to JsonPrimitive(text)))
        assertTrue(
            outcomeOf(
                RelayMethod.VOICE_CAPTURE_RESULT,
                with("a".repeat(4_000))
            ) is RelayOutcome.Success<*>
        )
        assertInvalid(RelayMethod.VOICE_CAPTURE_RESULT, with("a".repeat(4_001)), "4001 cp")
        // 4,000 supplementary code points = 8,000 UTF-16 units and 16,000 UTF-8 bytes: inside both transcript bounds. (The server writes ASCII-escaped
        // JSON, so on the wire that text is 48 KB and the 32,768-byte response bound is reached first; the DTO bound itself is code points and bytes.)
        val full = VoiceVectors.decodeResult(
            RelayMethod.VOICE_CAPTURE_RESULT,
            with("\u20ac".repeat(4_000))
        ) as CaptureResult
        val fullText = full.transcript!!
        assertEquals(4_000, fullText.codePointCount(0, fullText.length))
        val emoji = CaptureResult(
            full.captureId, full.conversationId, CaptureState.TRANSCRIBED, null,
            "\uD83D\uDE00".repeat(
                4_000
            ),
            1L, TranscriptSource.CONFIGURED,
            VoiceProvider.COMPLETED, 1L
        )
        assertEquals(8_000, emoji.transcript?.length)
        assertTrue(
            runCatching {
                CaptureResult(
                    full.captureId, full.conversationId, CaptureState.TRANSCRIBED, null,
                    "\uD83D\uDE00".repeat(
                        4_001
                    ),
                    1L, TranscriptSource.CONFIGURED, VoiceProvider.COMPLETED, 1L
                )
            }.isFailure
        )
        assertTrue(
            outcomeOf(
                RelayMethod.VOICE_CAPTURE_RESULT,
                with("\uD83D\uDE00".repeat(2_500))
            ) is RelayOutcome.Success<*>
        )
        assertInvalid(
            RelayMethod.VOICE_CAPTURE_RESULT,
            with("\uD83D\uDE00".repeat(4_001)),
            "4001 emoji"
        )
        // 3-byte code points: 4,000 of them is 12,000 bytes (fine); 16,384 bytes of 1-byte text is over the 4,000 cp bound first
        assertTrue(
            outcomeOf(
                RelayMethod.VOICE_CAPTURE_RESULT,
                with("\u20AC".repeat(4_000))
            ) is RelayOutcome.Success<*>
        )
        assertInvalid(RelayMethod.VOICE_CAPTURE_RESULT, with(""), "empty transcript")
    }

    @Test fun aLoneSurrogateEscapeIsRefusedByTheStrictParserBeforeAnyDto() {
        val base = VoiceVectors.forMethod(RelayMethod.VOICE_CAPTURE_RESULT).first {
            it.isSuccess
        }.responseText
        val broken = base.replace("\"provider\"", "\"zz\":\"\\ud800\",\"provider\"")
        val frame = RelayEnvelope.decodeFrame(broken)
        assertTrue(frame.toString(), frame.toString().startsWith("RelayFrame.Invalid"))
    }

    @Test fun responsesAreBoundedByBytesAndDepthPerMethodBeforeDecoding() {
        val padded = JsonObject(mapOf("pad" to JsonPrimitive("x".repeat(9_000))))
        for (method in listOf(
            RelayMethod.VOICE_INFO,
            RelayMethod.VOICE_CAPTURE_START,
            RelayMethod.VOICE_CAPTURE_DISCARD,
            RelayMethod.VOICE_SPEAK_STOP
        )) {
            val outcome = outcomeOf(method, padded)
            assertEquals(
                method.toString(),
                RelayProblem.RESPONSE_TOO_LARGE,
                (outcome as RelayOutcome.Invalid).problem
            )
        }
        val big = JsonObject(mapOf("pad" to JsonPrimitive("x".repeat(33_000))))
        for (method in listOf(
            RelayMethod.VOICE_CAPTURE_FINISH,
            RelayMethod.VOICE_CAPTURE_RESULT,
            RelayMethod.VOICE_SPEAK
        )) {
            assertEquals(
                RelayProblem.RESPONSE_TOO_LARGE,
                (outcomeOf(method, big) as RelayOutcome.Invalid).problem
            )
        }
        // between the small and the large bound only the three large methods accept the size
        val mid = JsonObject(mapOf("pad" to JsonPrimitive("x".repeat(20_000))))
        assertEquals(
            RelayProblem.RESPONSE_TOO_LARGE,
            (outcomeOf(RelayMethod.VOICE_INFO, mid) as RelayOutcome.Invalid).problem
        )
        assertEquals(
            RelayProblem.INVALID_RESULT,
            (outcomeOf(RelayMethod.VOICE_SPEAK, mid) as RelayOutcome.Invalid).problem
        )
        val deep = JsonObject(
            mapOf(
                "a" to
                    JsonObject(
                        mapOf(
                            "b" to
                                JsonObject(
                                    mapOf(
                                        "c" to
                                            JsonObject(
                                                mapOf(
                                                    "d" to JsonObject(
                                                        mapOf("e" to JsonPrimitive(1))
                                                    )
                                                )
                                            )
                                    )
                                )
                        )
                    )
            )
        ) // depth 7
        for (method in RelayMethod.entries.filter { it.isVoice }) {
            assertEquals(6, method.responseDepth)
            assertEquals(
                method.toString(),
                RelayProblem.RESPONSE_TOO_DEEP,
                (outcomeOf(method, deep) as RelayOutcome.Invalid).problem
            )
        }
        // an outer frame deeper than the shared Relay bound is refused as a frame
        val tooDeep =
            "{\"type\":\"relay.response\",\"v\":1,\"id\":\"m1\",\"result\":" + "{\"a\":".repeat(8) +
                "1" +
                "}".repeat(8) +
                "}"
        assertTrue(RelayEnvelope.decodeFrame(tooDeep).toString().startsWith("RelayFrame.Invalid"))
    }

    /** An unbounded list that reports how many elements were actually read. */
    private class EndlessList<T>(private val make: (Int) -> T) : java.util.AbstractList<T>() {
        var served = 0
        override val size: Int get() = Int.MAX_VALUE
        override fun get(index: Int): T {
            served++
            return make(index)
        }
    }

    @Test fun callerCollectionsAreOwnedAndReadOnlyUpToTheirBound() {
        val formats = EndlessList { CaptureFormat.entries[it % 2] }
        try {
            VoiceInputInfo(formats, 250, 60_000, 3_000, 1, 1, false)
            fail("an endless list was accepted")
        } catch (refused: IllegalArgumentException) {
            assertEquals("Too many items.", refused.message)
        }
        assertTrue("read ${formats.served}", formats.served <= CaptureFormat.entries.size + 1)
        val chunks = EndlessList { SpeechChunk(it, it + 1, "a") }
        try {
            SpeechResult(
                SpeechId.of(
                    "01a14d67-a300-7000-bdbb-1b2c33245e9b"
                )!!,
                null, SpeechState.TEXT, null, null,
                SpeechSource(
                    SourceDigest.of("a".repeat(64))!!,
                    10,
                    10,
                    false
                ),
                SpeechRange(0, 10, 1, false, true), chunks, null,
                VoiceProvider.NOT_STARTED
            )
            fail("endless chunks were accepted")
        } catch (refused: IllegalArgumentException) {
            assertEquals("Too many items.", refused.message)
        }
        assertEquals(VoiceLimits.MAX_CHUNKS + 1, chunks.served)
        // a list that exposes its own backing array through toArray() cannot be aliased
        val backing = arrayOf<Any?>(CaptureFormat.PCM16, CaptureFormat.OPUS)
        val exposing = object : java.util.AbstractList<CaptureFormat>() {
            override val size: Int get() = backing.size

            @Suppress("UNCHECKED_CAST")
            override fun get(index: Int): CaptureFormat = backing[index] as CaptureFormat
            override fun toArray(): Array<Any?> = backing
        }
        val info = VoiceInputInfo(exposing, 250, 60_000, 3_000, 1, 1, false)
        backing[0] = CaptureFormat.OPUS
        assertEquals(listOf(CaptureFormat.PCM16, CaptureFormat.OPUS), info.formats)
        assertTrue(
            "an owned list cannot be mutated",
            runCatching {
                (info.formats as MutableList<CaptureFormat>).add(CaptureFormat.OPUS)
            }.isFailure
        )
    }

    @Test fun contentNeverLeaksThroughToStringOrAnErrorPath() {
        val result = VoiceVectors.cases.first {
            it.isSuccess &&
                it.result["transcript"]?.toString()?.contains("SECRET") == true
        }
        val captured = VoiceVectors.decodeResult(result.method, result.result) as CaptureResult
        assertFalse(captured.toString().contains("SECRET"))
        val speech = VoiceVectors.cases.first {
            it.isSuccess && it.result["chunks"] !is JsonNull &&
                it.result["chunks"] != null
        }
        val text = VoiceVectors.decodeResult(speech.method, speech.result) as SpeechResult
        val sample = text.chunks!!.first().text
        assertTrue(sample.length > 8)
        assertFalse(text.toString().contains(sample.take(8)))
        assertFalse(text.chunks.first().toString().contains(sample.take(8)))
        // a malformed value is reported by field path only
        val bad =
            JsonObject(
                result.result + ("transcript" to JsonPrimitive("SECRET-LEAK-CANARY".repeat(1_000)))
            )
        val outcome = outcomeOf(RelayMethod.VOICE_CAPTURE_RESULT, bad) as RelayOutcome.Invalid
        assertFalse(outcome.toString().contains("CANARY"))
        assertNotNull(outcome.problem)
    }
}
