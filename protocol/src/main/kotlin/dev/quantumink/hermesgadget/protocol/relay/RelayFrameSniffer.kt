package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.BinaryFrame

/**
 * Bounded routing scan BEFORE stock Message.parse. Decodes JSON-equivalent top-level type keys and values without
 * building a body tree. Any Relay type or ambiguous/malformed header goes to the strict Relay parser. Legitimate
 * stock objects keep their original parser and limits. Allocation is constant (only short keys/type values).
 */
object RelayFrameSniffer {
    enum class Verdict { RELAY_RESPONSE, OTHER, UNDETERMINED }

    fun classify(text: String): Verdict = if (text.length > BinaryFrame.MAX_FRAME_BYTES) {
        // No valid stock frame can be this long; never risk a Relay fallthrough.
        Verdict.RELAY_RESPONSE
    } else {
        Scan(text).run()
    }

    private class Scan(private val text: String) {
        private var index = 0

        fun run(): Verdict {
            space()
            if (!take('{')) return Verdict.UNDETERMINED
            var types = 0
            var relay = false
            var sawType = false
            space()
            if (take('}')) return Verdict.UNDETERMINED
            while (index < text.length) {
                space()
                val key = string() ?: return Verdict.RELAY_RESPONSE
                space()
                if (!take(':')) return Verdict.RELAY_RESPONSE
                space()
                if (key == "type") {
                    types++
                    if (index < text.length && text[index] == '"') {
                        val type = string() ?: return Verdict.RELAY_RESPONSE
                        sawType = true
                        relay = relay || type == "relay.response"
                    } else {
                        return Verdict.RELAY_RESPONSE
                    }
                } else if (!skipValue()) {
                    return Verdict.RELAY_RESPONSE
                }
                space()
                if (take('}')) {
                    space()
                    if (index != text.length) return Verdict.RELAY_RESPONSE
                    return when {
                        relay || types > 1 -> Verdict.RELAY_RESPONSE
                        sawType -> Verdict.OTHER
                        else -> Verdict.UNDETERMINED
                    }
                }
                if (!take(',')) return Verdict.RELAY_RESPONSE
            }
            return Verdict.RELAY_RESPONSE
        }

        /** Decodes escapes but retains at most 32 characters, enough for type/key equality. */
        private fun string(): String? {
            if (!take('"')) return null
            val small = StringBuilder(32)
            var length = 0
            while (index < text.length) {
                var c = text[index++]
                if (c == '"') return if (length <= 32) small.toString() else "\u0000"
                if (c < ' ') return null
                if (c == '\\') {
                    if (index >= text.length) return null
                    c = when (val escape = text[index++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (index + 4 > text.length) return null
                            var value = 0
                            repeat(4) {
                                val digit = text[index++].digitToIntOrNull(16) ?: return null
                                value = value * 16 + digit
                            }
                            value.toChar()
                        }
                        else -> return null
                    }
                }
                if (length++ < 32) small.append(c)
            }
            return null
        }

        /** Iterative, no tree or stack proportional to input. Full validation remains with the selected parser. */
        private fun skipValue(): Boolean {
            var depth = 0
            val start = index
            while (index < text.length) {
                when (text[index]) {
                    '"' -> {
                        if (string() == null) return false
                        if (depth == 0) return true
                        continue
                    }
                    '{', '[' -> {
                        depth++
                        if (depth > 64) return false
                    }
                    '}', ']' -> {
                        if (depth == 0) return index > start
                        depth--
                        if (depth == 0) {
                            index++
                            return true
                        }
                    }
                    ',' -> if (depth == 0) return index > start
                }
                index++
            }
            return false
        }

        private fun space() {
            while (index < text.length && text[index] in " \n\r\t") index++
        }

        private fun take(c: Char): Boolean = if (index < text.length && text[index] == c) {
            index++
            true
        } else {
            false
        }
    }
}
