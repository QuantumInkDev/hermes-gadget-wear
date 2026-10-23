package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test-only reader of `relay-voice-v1/`: frames recorded from the REGISTERED stack (SDK `d880ee47` plus the V4 capture tests) through
 * `DeviceHub._on_text` / `_on_binary`, and the five unchanged F1 output files. Nothing in those files is written by hand.
 */
internal object VoiceVectors {
    class Case(
        val file: String,
        val id: String,
        val method: RelayMethod,
        val outcome: String,
        val request: JsonObject,
        val requestText: String,
        val response: JsonObject,
        val responseText: String,
        val wireBytes: Int,
        val note: String?
    ) {
        val params: JsonObject get() = request["params"]!!.jsonObject
        val isSuccess: Boolean get() = "result" in response
        val result: JsonObject get() = response["result"]!!.jsonObject
        val errorCode: String? get() = response["error"]?.jsonObject?.get(
            "code"
        )?.jsonPrimitive?.content
        val correlation: CorrelationId get() = CorrelationId.of(
            response["id"]!!.jsonPrimitive.content
        )!!

        override fun toString(): String = "$file::$id"
    }

    fun text(name: String): String =
        VoiceVectors::class.java.getResourceAsStream("/relay-voice-v1/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    fun bytes(name: String): ByteArray =
        VoiceVectors::class.java.getResourceAsStream("/relay-voice-v1/$name")!!.use {
            it.readBytes()
        }

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    val manifest: JsonObject by lazy { json("manifest.json") }

    val files: List<JsonObject> by lazy { manifest["files"]!!.jsonArray.map { it.jsonObject } }

    val v4Files: List<String> by lazy {
        files.filter {
            it["generation"]!!.jsonPrimitive.content == "v4-capture"
        }.map { it["file"]!!.jsonPrimitive.content }
    }

    val f1Files: List<String> by lazy {
        files.filter {
            it["generation"]!!.jsonPrimitive.content == "f1-output"
        }.map { it["file"]!!.jsonPrimitive.content }
    }

    /** Every captured exchange of the V4 files, in file order. */
    val cases: List<Case> by lazy { v4Files.flatMap(::load) }

    fun load(file: String): List<Case> = json(file)["cases"]!!.jsonArray.map {
        val case = it.jsonObject
        check(
            case["kind"]!!.jsonPrimitive.content == "captured" &&
                case["origin"]!!.jsonPrimitive.content == "registered-hub-text"
        )
        val method = RelayMethod.fromWire(case["method"]!!.jsonPrimitive.content)!!
        Case(
            file,
            case["id"]!!.jsonPrimitive.content,
            method,
            case["outcome"]!!.jsonPrimitive.content,
            case["request"]!!.jsonObject,
            case["request_text"]!!.jsonPrimitive.content,
            case["response"]!!.jsonObject,
            case["response_text"]!!.jsonPrimitive.content,
            case["wire_bytes"]!!.jsonPrimitive.int,
            case["note"]?.jsonPrimitive?.content
        )
    }

    fun forMethod(method: RelayMethod): List<Case> = cases.filter { it.method == method }

    fun byId(id: String): Case = cases.first { it.id == id }

    /** The `binary` entries of a V4 file: frame records (with `raw_hex`) and marks. */
    fun binary(file: String): List<JsonObject> =
        json(file)["binary"]!!.jsonArray.map { it.jsonObject }

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) {
        text.substring(
            it * 2,
            it * 2 + 2
        ).toInt(16).toByte()
    }

    /** Decodes [case]'s recorded response with the method's own decoder, exactly as a client would after the correlation check. */
    fun outcome(case: Case): RelayOutcome<Any> {
        val call = RelayCall(case.method, case.params, VoiceCalls.resultDecoder(case.method))
        return call.interpret(case.correlation, RelayEnvelope.decodeFrame(case.responseText))
    }

    fun decodeResult(method: RelayMethod, result: JsonObject): Any =
        RelayCalls.resultDecoder(method)(result)
}
