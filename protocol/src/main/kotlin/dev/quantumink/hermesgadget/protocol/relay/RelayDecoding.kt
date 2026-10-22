package dev.quantumink.hermesgadget.protocol.relay

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An enum member with a fixed wire spelling. */
interface WireToken {
    val wire: String
}

/** A decode failure that names only a field path, never a value (values can be user text). */
internal class DecodeFailure(val path: String) : RuntimeException(path, null, false, false)

/**
 * Exact-key reader over one object. Unknown, missing, wrong-typed and out-of-range members are refused; `null` is
 * accepted only through the explicit `...OrNull` readers, so an allowed null is never confused with a missing or
 * invalid member.
 */
internal class FieldReader private constructor(
    private val path: String,
    private val fields: JsonObject
) {
    companion object {
        fun of(
            path: String,
            element: JsonElement?,
            required: Set<String>,
            optional: Set<String> = emptySet()
        ): FieldReader {
            val fields = element as? JsonObject ?: throw DecodeFailure(path)
            if (!fields.keys.containsAll(required) ||
                !(required + optional).containsAll(fields.keys)
            ) {
                throw DecodeFailure(path)
            }
            return FieldReader(path, fields)
        }
    }

    fun has(name: String): Boolean = fields.containsKey(name)

    fun fail(name: String): Nothing = throw DecodeFailure("$path.$name")

    private fun primitive(name: String): JsonPrimitive {
        val value = fields[name]
        if (value !is JsonPrimitive || value is JsonNull) fail(name)
        return value
    }

    fun isNull(name: String): Boolean = fields[name] is JsonNull

    fun string(name: String, maxBytes: Int, minBytes: Int = 1): String {
        val value = primitive(name)
        if (!value.isString) fail(name)
        val bytes = RelayJson.utf8Length(value.content, maxBytes)
        if (bytes < minBytes || bytes > maxBytes) fail(name)
        return value.content
    }

    fun stringOrNull(name: String, maxBytes: Int, minBytes: Int = 1): String? =
        if (isNull(name)) null else string(name, maxBytes, minBytes)

    fun long(name: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long {
        val value = primitive(name)
        if (value.isString) fail(name)
        val number = value.content.toLongOrNull() ?: fail(name)
        if (number < min || number > max) fail(name)
        return number
    }

    fun longOrNull(name: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long? =
        if (isNull(name)) null else long(name, min, max)

    fun boolean(name: String): Boolean {
        val value = primitive(name)
        if (value.isString) fail(name)
        return when (value.content) {
            "true" -> true
            "false" -> false
            else -> fail(name)
        }
    }

    /** Only the literal `true`: for members the server emits exclusively as `true` (never `false`). */
    fun mustBeTrue(name: String) {
        if (!boolean(name)) fail(name)
    }

    fun obj(name: String, required: Set<String>, optional: Set<String> = emptySet()): FieldReader =
        of("$path.$name", fields[name], required, optional)

    fun objOrNull(
        name: String,
        required: Set<String>,
        optional: Set<String> = emptySet()
    ): FieldReader? = if (isNull(name)) null else obj(name, required, optional)

    fun rawObject(name: String): JsonObject = fields[name] as? JsonObject ?: fail(name)

    fun array(name: String, maxItems: Int): List<JsonElement> {
        val value = fields[name] as? JsonArray ?: fail(name)
        if (value.size > maxItems) fail(name)
        return value
    }

    fun <T : Any> objects(
        name: String,
        maxItems: Int,
        required: Set<String>,
        optional: Set<String> = emptySet(),
        decode: (FieldReader) -> T
    ): List<T> {
        val source = array(name, maxItems)
        val out = ArrayList<T>(minOf(source.size, 64))
        for ((index, element) in source.withIndex()) {
            out.add(decode(of("$path.$name[$index]", element, required, optional)))
        }
        return Collections.unmodifiableList(out)
    }

    fun <E> token(name: String, values: Array<E>): E where E : Enum<E>, E : WireToken {
        val text = string(name, 64)
        return values.firstOrNull { it.wire == text } ?: fail(name)
    }

    fun <E> tokenOrNull(name: String, values: Array<E>): E? where E : Enum<E>, E : WireToken =
        if (isNull(name)) null else token(name, values)

    fun <T : Any> id(name: String, parse: (String) -> T?): T =
        parse(string(name, RelayLimits.ID_CHARS)) ?: fail(name)

    fun <T : Any> idOrNull(name: String, parse: (String) -> T?): T? =
        if (isNull(name)) null else id(name, parse)
}

/**
 * Caller collections are copied by bounded element iteration into a new list that no caller can reach. Never
 * `ArrayList(Collection)` / `toList()`: Android 10 libcore keeps an `Object[]` returned by the source's `toArray()`,
 * which would alias caller-owned storage. A hostile or oversized source is refused after at most [maximum] + 1
 * elements, never copied whole.
 */
internal fun <T : Any> ownedBounded(values: Iterable<T>, maximum: Int): List<T> {
    val owned = ArrayList<T>(minOf(maximum, 16))
    for (value in values) {
        require(owned.size < maximum) { "Too many items." }
        owned.add(value)
    }
    return Collections.unmodifiableList(owned)
}

internal fun wireObject(vararg members: Pair<String, JsonElement>): JsonObject =
    JsonObject(linkedMapOf(*members))

internal fun wireString(value: String?): JsonElement = if (value ==
    null
) {
    JsonNull
} else {
    JsonPrimitive(value)
}

internal fun wireLong(value: Long?): JsonElement = if (value ==
    null
) {
    JsonNull
} else {
    JsonPrimitive(value)
}

internal fun wireBoolean(value: Boolean): JsonElement = JsonPrimitive(value)

internal fun <T> wireArray(values: List<T>, encode: (T) -> JsonElement): JsonArray =
    JsonArray(values.map(encode))

internal fun validText(value: String, maxBytes: Int, minBytes: Int = 1): Boolean {
    val bytes = RelayJson.utf8Length(value, maxBytes)
    return bytes in minBytes..maxBytes
}

/** `[a-z0-9_]{1,32}`: the display label of an existing source (`relay_outbox._SOURCE_CHARS`). */
internal fun validSourceLabel(value: String): Boolean =
    value.length in 1..RelayLimits.SOURCE_CHARS &&
        value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

/** A cursor the server issued: opaque, ASCII, 1..512 bytes. Never parsed. */
internal fun validCursor(value: String): Boolean =
    value.length in 1..RelayLimits.CURSOR_BYTES && value.all { it.code in 0x21..0x7e }
