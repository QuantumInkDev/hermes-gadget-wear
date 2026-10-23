package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.BinaryFrame

/**
 * A cheap, allocation-light look at the top level of a raw text frame, made BEFORE the permissive stock
 * [dev.quantumink.hermesgadget.protocol.Message.parse] (depth 64, 1 MiB) is allowed to see it. Only a connection that
 * deliberately opted in to Relay asks: a frame whose top-level `type` is `relay.response` belongs to the Relay router
 * (bounded by [RelayLimits.FRAME_BYTES] and [RelayLimits.DEPTH]) and is never handed to the stock conversation; every
 * other frame continues to the stock parser unchanged.
 *
 * The scan reads the first top-level `type` member only (nested `type` members never count) without building a tree, and
 * is itself bounded by the stock frame limit. It is a ROUTING decision, not validation: [RelayEnvelope.decodeFrame]
 * still parses strictly and refuses duplicates and malformed frames.
 */
object RelayFrameSniffer {
    enum class Verdict {
        /** The top-level `type` is `relay.response`. */
        RELAY_RESPONSE,

        /** A top-level `type` is present and is something else (stock traffic). */
        OTHER,

        /** No readable top-level `type`: the stock parser decides (and normally refuses). */
        UNDETERMINED
    }

    private const val TYPE = "type"
    private const val RELAY_RESPONSE = "relay.response"
    private const val MAX_KEY = 16

    fun classify(text: String): Verdict {
        if (text.length > BinaryFrame.MAX_FRAME_BYTES) return Verdict.UNDETERMINED
        val scan = Scan(text)
        return scan.run()
    }

    private class Scan(private val text: String) {
        private var index = 0

        fun run(): Verdict {
            skipSpace()
            if (!take('{')) return Verdict.UNDETERMINED
            while (true) {
                skipSpace()
                if (index >= text.length) return Verdict.UNDETERMINED
                if (text[index] == '}') return Verdict.UNDETERMINED
                if (text[index] != '"') return Verdict.UNDETERMINED
                val key = readKey() ?: return Verdict.UNDETERMINED
                skipSpace()
                if (!take(':')) return Verdict.UNDETERMINED
                skipSpace()
                if (key == TYPE) {
                    return when (readTypeValue()) {
                        null -> Verdict.UNDETERMINED
                        RELAY_RESPONSE -> Verdict.RELAY_RESPONSE
                        else -> Verdict.OTHER
                    }
                }
                if (!skipValue()) return Verdict.UNDETERMINED
                skipSpace()
                if (index >= text.length) return Verdict.UNDETERMINED
                when (text[index]) {
                    ',' -> index++
                    else -> return Verdict.UNDETERMINED
                }
            }
        }

        /**
         * A key without escapes of at most [MAX_KEY] characters; any other key is returned as a marker that
         * never equals `type`.
         */
        private fun readKey(): String? {
            index++ // opening quote
            val start = index
            var escaped = false
            while (index < text.length) {
                val c = text[index]
                if (c == '\\') {
                    escaped = true
                    index += 2
                    continue
                }
                if (c == '"') {
                    val length = index - start
                    index++
                    return if (escaped || length > MAX_KEY) {
                        "\u0000"
                    } else {
                        text.substring(start, start + length)
                    }
                }
                index++
            }
            return null
        }

        /**
         * The `type` string value (without escapes, at most 32 characters), or null when it is not a plain
         * short string.
         */
        private fun readTypeValue(): String? {
            if (index >= text.length || text[index] != '"') return null
            index++
            val start = index
            while (index < text.length) {
                val c = text[index]
                if (c == '\\' || index - start > 32) return null
                if (c == '"') return text.substring(start, index)
                index++
            }
            return null
        }

        /** Skips one JSON value of any shape without allocating; false when the text ends early. */
        private fun skipValue(): Boolean {
            if (index >= text.length) return false
            var depth = 0
            while (index < text.length) {
                val c = text[index]
                when {
                    c == '"' -> {
                        if (!skipString()) return false
                        if (depth == 0) return true
                        continue
                    }
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> {
                        if (depth == 0) return true
                        depth--
                        if (depth == 0) {
                            index++
                            return true
                        }
                    }
                    c == ',' && depth == 0 -> return true
                }
                index++
            }
            return depth == 0
        }

        private fun skipString(): Boolean {
            index++ // opening quote
            while (index < text.length) {
                val c = text[index]
                if (c == '\\') {
                    index += 2
                    continue
                }
                index++
                if (c == '"') return true
            }
            return false
        }

        private fun skipSpace() {
            while (index < text.length && text[index] in " \n\r\t") index++
        }

        private fun take(c: Char): Boolean {
            if (index < text.length && text[index] == c) {
                index++
                return true
            }
            return false
        }
    }
}
