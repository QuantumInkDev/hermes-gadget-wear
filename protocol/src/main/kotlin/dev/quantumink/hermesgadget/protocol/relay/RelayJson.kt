package dev.quantumink.hermesgadget.protocol.relay

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class RelayRejection {
    OVERSIZE,
    NOT_UTF8,
    MALFORMED,
    NOT_OBJECT,
    DEPTH,
    ITEMS,
    DUPLICATE_KEY,
    NUMBER
}

sealed interface RelayParse {
    class Parsed internal constructor(val root: JsonObject) : RelayParse {
        override fun toString(): String = "RelayParse.Parsed(redacted)"
    }

    class Rejected internal constructor(val reason: RelayRejection) : RelayParse {
        override fun toString(): String = "RelayParse.Rejected($reason)"
    }
}

/**
 * Strict, bounded JSON for the Relay wire. The UTF-8 byte limit and the exact item-depth rule are decided on the
 * raw text before a tree exists; recursion is capped by the depth rule itself (at most nine frames), so no input
 * can exhaust the stack. This is deliberately NOT [dev.quantumink.hermesgadget.protocol.Message.parse], which keeps
 * the stock generic bound (depth 64) for the existing handshake, audio, pets and migration traffic.
 */
object RelayJson {
    fun parse(text: String): RelayParse {
        val bytes = utf8Length(text, RelayLimits.FRAME_BYTES)
        return when {
            bytes == LONE_SURROGATE -> RelayParse.Rejected(RelayRejection.NOT_UTF8)
            bytes > RelayLimits.FRAME_BYTES -> RelayParse.Rejected(RelayRejection.OVERSIZE)
            else -> parseBounded(text)
        }
    }

    fun parse(bytes: ByteArray): RelayParse {
        if (bytes.size >
            RelayLimits.FRAME_BYTES
        ) {
            return RelayParse.Rejected(RelayRejection.OVERSIZE)
        }
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return RelayParse.Rejected(RelayRejection.NOT_UTF8)
        }
        return parseBounded(decoded)
    }

    /**
     * UTF-8 length of [text], stopping early once it exceeds [limit] (the exact value is then not needed), or
     * [LONE_SURROGATE] when the text cannot be encoded.
     */
    internal fun utf8Length(text: String, limit: Int = Int.MAX_VALUE): Int {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            when {
                unit.code < 0x80 -> bytes += 1
                unit.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(unit) -> {
                    if (index + 1 >= text.length ||
                        !Character.isLowSurrogate(text[index + 1])
                    ) {
                        return LONE_SURROGATE
                    }
                    bytes += 4
                    index++
                }
                Character.isLowSurrogate(unit) -> return LONE_SURROGATE
                else -> bytes += 3
            }
            index++
            if (bytes > limit) {
                // Keep scanning cheaply only for the one fact that outranks size: encodability.
                return if (hasLoneSurrogate(text, index)) LONE_SURROGATE else limit + 1
            }
        }
        return bytes.toInt()
    }

    private fun hasLoneSurrogate(text: String, from: Int): Boolean {
        var index = from
        while (index < text.length) {
            val unit = text[index]
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length ||
                    !Character.isLowSurrogate(text[index + 1])
                ) {
                    return true
                }
                index++
            } else if (Character.isLowSurrogate(unit)) {
                return true
            }
            index++
        }
        return false
    }

    /** True when no item (container or scalar) is deeper than [limit]; the root is depth 1. Iterative. */
    fun withinDepth(root: JsonElement, limit: Int): Boolean {
        val pending = ArrayList<Pair<JsonElement, Int>>()
        pending.add(root to 1)
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeAt(pending.size - 1)
            if (depth > limit) return false
            when (element) {
                is JsonObject -> for (child in element.values) pending.add(child to depth + 1)
                is JsonArray -> for (child in element) pending.add(child to depth + 1)
                else -> Unit
            }
        }
        return true
    }

    private fun parseBounded(text: String): RelayParse = try {
        RelayParse.Parsed(Parser(text).document())
    } catch (rejection: Reject) {
        RelayParse.Rejected(rejection.reason)
    }

    internal const val LONE_SURROGATE = -1

    private class Reject(val reason: RelayRejection) : RuntimeException(null, null, false, false)

    private class Parser(private val text: String) {
        private var position = 0
        private var items = 0

        fun document(): JsonObject {
            skipSpace()
            if (position >= text.length) throw Reject(RelayRejection.MALFORMED)
            if (text[position] != '{') throw Reject(RelayRejection.NOT_OBJECT)
            val root = value(1) as JsonObject
            skipSpace()
            if (position != text.length) throw Reject(RelayRejection.MALFORMED)
            return root
        }

        private fun value(depth: Int): JsonElement {
            if (depth > RelayLimits.DEPTH) throw Reject(RelayRejection.DEPTH)
            if (++items > RelayLimits.ITEMS) throw Reject(RelayRejection.ITEMS)
            skipSpace()
            if (position >= text.length) throw Reject(RelayRejection.MALFORMED)
            return when (val c = text[position]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' ||
                    c in '0'..'9'
                ) {
                    number()
                } else {
                    throw Reject(RelayRejection.MALFORMED)
                }
            }
        }

        private fun literal(word: String, result: JsonElement): JsonElement {
            if (!text.startsWith(word, position)) throw Reject(RelayRejection.MALFORMED)
            position += word.length
            return result
        }

        private fun obj(depth: Int): JsonObject {
            position++
            val members = LinkedHashMap<String, JsonElement>()
            skipSpace()
            if (peek() == '}') {
                position++
                return JsonObject(members)
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw Reject(RelayRejection.MALFORMED)
                val key = string()
                if (members.containsKey(key)) throw Reject(RelayRejection.DUPLICATE_KEY)
                skipSpace()
                if (peek() != ':') throw Reject(RelayRejection.MALFORMED)
                position++
                members[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonObject(members)
                    }
                    else -> throw Reject(RelayRejection.MALFORMED)
                }
            }
        }

        private fun array(depth: Int): JsonArray {
            position++
            val elements = ArrayList<JsonElement>()
            skipSpace()
            if (peek() == ']') {
                position++
                return JsonArray(elements)
            }
            while (true) {
                elements.add(value(depth + 1))
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonArray(elements)
                    }
                    else -> throw Reject(RelayRejection.MALFORMED)
                }
            }
        }

        private fun string(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (position >= text.length) throw Reject(RelayRejection.MALFORMED)
                val c = text[position++]
                when {
                    c == '"' -> return out.toString()
                    c.code < 0x20 -> throw Reject(RelayRejection.MALFORMED)
                    c == '\\' -> escape(out)
                    else -> out.append(c)
                }
            }
        }

        private fun escape(out: StringBuilder) {
            if (position >= text.length) throw Reject(RelayRejection.MALFORMED)
            when (text[position++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> unicode(out)
                else -> throw Reject(RelayRejection.MALFORMED)
            }
        }

        /** One `\uXXXX`; a high surrogate must be completed by an escaped low one, and a lone low is refused. */
        private fun unicode(out: StringBuilder) {
            val unit = hex4()
            if (Character.isLowSurrogate(unit)) throw Reject(RelayRejection.MALFORMED)
            out.append(unit)
            if (Character.isHighSurrogate(unit)) {
                if (!text.startsWith("\\u", position)) throw Reject(RelayRejection.MALFORMED)
                position += 2
                val low = hex4()
                if (!Character.isLowSurrogate(low)) throw Reject(RelayRejection.MALFORMED)
                out.append(low)
            }
        }

        private fun hex4(): Char {
            if (position + 4 > text.length) throw Reject(RelayRejection.MALFORMED)
            var code = 0
            repeat(4) {
                val digit = Character.digit(text[position++], 16)
                if (digit < 0) throw Reject(RelayRejection.MALFORMED)
                code = code * 16 + digit
            }
            return code.toChar()
        }

        private fun number(): JsonElement {
            val start = position
            if (text[position] == '-') position++
            if (position >= text.length) throw Reject(RelayRejection.MALFORMED)
            if (text[position] == '0') {
                position++
            } else if (text[position] in '1'..'9') {
                digits()
            } else {
                throw Reject(RelayRejection.MALFORMED)
            }
            var integral = true
            if (position < text.length && text[position] == '.') {
                integral = false
                position++
                if (digits() == 0) throw Reject(RelayRejection.MALFORMED)
            }
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                integral = false
                position++
                if (position < text.length &&
                    (text[position] == '+' || text[position] == '-')
                ) {
                    position++
                }
                if (digits() == 0) throw Reject(RelayRejection.MALFORMED)
            }
            val literal = text.substring(start, position)
            if (integral) {
                return JsonPrimitive(literal.toLongOrNull() ?: throw Reject(RelayRejection.NUMBER))
            }
            val number = literal.toDouble()
            if (!number.isFinite()) throw Reject(RelayRejection.NUMBER)
            return JsonPrimitive(number)
        }

        private fun digits(): Int {
            var count = 0
            while (position < text.length && text[position] in '0'..'9') {
                position++
                count++
            }
            return count
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'

        private fun skipSpace() {
            while (position < text.length) {
                val c = text[position]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') position++ else return
            }
        }
    }
}
