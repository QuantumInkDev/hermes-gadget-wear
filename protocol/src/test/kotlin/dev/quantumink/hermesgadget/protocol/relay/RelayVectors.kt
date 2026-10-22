package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Test-only reader of `relay-v1/` : frames recorded from the accepted SDK running on the real adapter. */
internal object RelayVectors {
    class Case(
        val id: String,
        val outcome: String,
        val request: JsonObject,
        val response: JsonObject,
        val wireBytes: Int,
        val test: String,
        val discrepancy: String?
    ) {
        val method: String get() = request["method"]!!.jsonPrimitive.content
        val params: JsonObject get() = request["params"]!!.jsonObject
        val responseId: String? get() = response["id"]?.jsonPrimitive?.content

        /** The exact bytes the server sent: ASCII-escaped compact JSON. */
        val wireText: String get() = asciiJson(response)
        val isSuccess: Boolean get() = "result" in response
        val result: JsonObject get() = response["result"]!!.jsonObject
        val error: JsonObject get() = response["error"]!!.jsonObject
        override fun toString(): String = id
    }

    fun text(name: String): String =
        RelayVectors::class.java.getResourceAsStream("/relay-v1/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    val manifest: JsonObject by lazy { json("manifest.json") }

    /** Every coverage file but the hub-level one. */
    val methodFiles: List<String> by lazy {
        manifest["coverage"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }
            .filter { it != "hub-level.json" }
    }

    val all: List<Case> by lazy { methodFiles.flatMap(::load) }

    fun load(file: String): List<Case> = json(file)["cases"]!!.jsonArray.map {
        val case = it.jsonObject
        check(case["kind"]!!.jsonPrimitive.content == "captured")
        Case(
            case["id"]!!.jsonPrimitive.content,
            case["outcome"]!!.jsonPrimitive.content,
            case["request"]!!.jsonObject,
            case["response"]!!.jsonObject,
            case["wire_bytes"]!!.jsonPrimitive.int,
            case["source"]!!.jsonObject["test"]!!.jsonPrimitive.content,
            case["contract_discrepancy"]?.jsonPrimitive?.content
        )
    }

    fun forMethod(method: RelayMethod): List<Case> = all.filter { it.method == method.wire }

    /** Python `json.dumps(obj, separators=(',', ':'))` (ensure_ascii): what `DeviceSession.send_json` writes. */
    fun asciiJson(element: JsonElement): String =
        StringBuilder().also { append(it, element) }.toString()

    private fun append(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    string(out, key)
                    out.append(':')
                    append(out, value)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    append(out, value)
                }
                out.append(']')
            }
            JsonNull -> out.append("null")
            is JsonPrimitive -> if (element.isString) {
                string(
                    out,
                    element.content
                )
            } else {
                out.append(element.content)
            }
        }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c.code < 0x20 || c.code > 0x7e && c.code != 0x7f -> out.append(
                    "\\u"
                ).append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
