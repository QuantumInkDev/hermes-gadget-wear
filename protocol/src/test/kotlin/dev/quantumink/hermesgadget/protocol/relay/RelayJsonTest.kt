package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw Relay parser: exact UTF-8 byte and item-depth bounds, applied before any tree exists. */
class RelayJsonTest {
    private fun rejected(text: String): RelayRejection =
        (RelayJson.parse(text) as RelayParse.Rejected).reason

    private fun parsed(text: String): JsonObject = (RelayJson.parse(text) as RelayParse.Parsed).root

    private fun frameOfBytes(size: Int, filler: String): String {
        val prefix = "{\"a\":\""
        val suffix = "\"}"
        val fill = StringBuilder()
        var bytes = prefix.length + suffix.length
        while (bytes + filler.toByteArray().size <= size) {
            fill.append(filler)
            bytes += filler.toByteArray().size
        }
        while (bytes < size) {
            fill.append('x')
            bytes++
        }
        return prefix + fill + suffix
    }

    @Test fun exactByteBoundaryIsAcceptedAndOneMoreIsRefusedForAsciiAndMultibyte() {
        for (filler in listOf("x", "é", "€", "\uD83D\uDE00")) {
            val atLimit = frameOfBytes(RelayLimits.FRAME_BYTES, filler)
            assertEquals(RelayLimits.FRAME_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
            assertNotNull(parsed(atLimit))
            val over = atLimit.dropLast(2) + "x\"}"
            assertEquals(RelayLimits.FRAME_BYTES + 1, over.toByteArray(Charsets.UTF_8).size)
            assertEquals(RelayRejection.OVERSIZE, rejected(over))
        }
    }

    @Test fun characterCountBelowTheLimitStillCountsUtf8Bytes() {
        // 30,000 euro signs are 30,000 characters but 90,000 UTF-8 bytes.
        val text = "{\"a\":\"" + "€".repeat(30_000) + "\"}"
        assertTrue(text.length < RelayLimits.FRAME_BYTES)
        assertEquals(RelayRejection.OVERSIZE, rejected(text))
    }

    @Test fun byteArrayInputUsesTheSameLimitAndRefusesInvalidUtf8() {
        val ok = frameOfBytes(RelayLimits.FRAME_BYTES, "é").toByteArray(Charsets.UTF_8)
        assertTrue(RelayJson.parse(ok) is RelayParse.Parsed)
        assertEquals(
            RelayRejection.OVERSIZE,
            (
                RelayJson.parse(
                    ByteArray(RelayLimits.FRAME_BYTES + 1) {
                        'x'.code.toByte()
                    }
                ) as RelayParse.Rejected
                ).reason
        )
        val invalid = "{\"a\":\"".toByteArray() + byteArrayOf(0xC3.toByte()) + "\"}".toByteArray()
        assertEquals(
            RelayRejection.NOT_UTF8,
            (RelayJson.parse(invalid) as RelayParse.Rejected).reason
        )
        val overlong =
            "{\"a\":\"".toByteArray() + byteArrayOf(0xC0.toByte(), 0x80.toByte()) +
                "\"}".toByteArray()
        assertEquals(
            RelayRejection.NOT_UTF8,
            (RelayJson.parse(overlong) as RelayParse.Rejected).reason
        )
    }

    @Test fun aLoneSurrogateCannotBeEncodedAsUtf8AndIsRefused() {
        assertEquals(RelayRejection.NOT_UTF8, rejected("{\"a\":\"\uD800\"}"))
        assertEquals(RelayRejection.MALFORMED, rejected("{\"a\":\"\\ud800\"}"))
        assertEquals(RelayRejection.MALFORMED, rejected("{\"a\":\"\\udc00\\ud800\"}"))
        assertEquals(
            "\uD83D\uDE00",
            (parsed("{\"a\":\"\\ud83d\\ude00\"}")["a"] as JsonPrimitive).content
        )
    }

    @Test fun itemDepthMatchesTheServerRuleWhereScalarsAlsoCount() {
        // Root object is depth 1; its scalar values are depth 2. The server refuses any item deeper than 8.
        val eightContainers = "{\"a\":".repeat(7) + "{}" + "}".repeat(7) // empty object at depth 8
        assertNotNull(parsed(eightContainers))
        val scalarAtEight = "{\"a\":".repeat(7) + "1" + "}".repeat(7) // scalar at depth 8
        assertNotNull(parsed(scalarAtEight))
        val scalarAtNine = "{\"a\":".repeat(8) + "1" + "}".repeat(8)
        assertEquals(RelayRejection.DEPTH, rejected(scalarAtNine))
        val containerAtNine = "{\"a\":".repeat(8) + "{}" + "}".repeat(8)
        assertEquals(RelayRejection.DEPTH, rejected(containerAtNine))
        val arrays = "{\"a\":" + "[".repeat(6) + "1" + "]".repeat(6) + "}" // scalar at depth 8
        assertNotNull(parsed(arrays))
        assertEquals(
            RelayRejection.DEPTH,
            rejected("{\"a\":" + "[".repeat(7) + "1" + "]".repeat(7) + "}")
        )
    }

    @Test fun pathologicalNestingIsRefusedWithoutRecursionOrDisproportionateWork() {
        val bomb = "[".repeat(RelayLimits.FRAME_BYTES - 2) + "]".repeat(1)
        assertTrue(RelayJson.parse(bomb) is RelayParse.Rejected)
        assertEquals(RelayRejection.NOT_OBJECT, rejected("[" + "[".repeat(5) + "]".repeat(5) + "]"))
        assertEquals(
            RelayRejection.DEPTH,
            rejected("{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}")
        )
    }

    @Test fun strictGrammarRejectsWhatLenientParsersAccept() {
        for (
        bad in listOf(
            "", " ", "{", "{}x", "{} {}", "{'a':1}", "{\"a\":1,}", "{\"a\":}", "{\"a\" 1}", "{,}",
            "{\"a\":01}", "{\"a\":+1}", "{\"a\":.5}", "{\"a\":1.}", "{\"a\":NaN}",
            "{\"a\":Infinity}",
            "{\"a\":-Infinity}", "{\"a\":tru}", "{\"a\":nul}", "{\"a\":\"\t\"}", "{\"a\":\"\\x\"}",
            "{\"a\":\"\\u12\"}", "\uFEFF{}", "{\"a\":[1,]}", "{\"a\":1e}", "{\"a\":-}",
            "{\"a\":123456789012345678901234567890}", "{\"a\":\"unterminated}"
        )
        ) {
            assertTrue("accepted: $bad", RelayJson.parse(bad) is RelayParse.Rejected)
        }
        assertEquals(RelayRejection.NOT_OBJECT, rejected("[]"))
        assertEquals(RelayRejection.NOT_OBJECT, rejected("1"))
        assertEquals(RelayRejection.NOT_OBJECT, rejected("\"x\""))
        assertEquals(RelayRejection.NOT_OBJECT, rejected("null"))
    }

    @Test fun duplicateKeysAreAmbiguousAndRefusedAtEveryLevel() {
        assertEquals(RelayRejection.DUPLICATE_KEY, rejected("{\"a\":1,\"a\":2}"))
        assertEquals(RelayRejection.DUPLICATE_KEY, rejected("{\"a\":{\"b\":1,\"b\":1}}"))
        assertEquals(RelayRejection.DUPLICATE_KEY, rejected("{\"a\":[{\"b\":1,\"b\":1}]}"))
        // An escaped spelling is the same key.
        assertEquals(RelayRejection.DUPLICATE_KEY, rejected("{\"a\":1,\"\\u0061\":2}"))
    }

    @Test fun integersAreExactInt64AndFloatsStayDistinct() {
        val root =
            parsed(
                "{\"max\":9223372036854775807,\"min\":-9223372036854775808,\"f\":1.5,\"g\":2.0,\"e\":1e2}"
            )
        assertEquals(Long.MAX_VALUE, (root["max"] as JsonPrimitive).content.toLong())
        assertEquals(Long.MIN_VALUE, (root["min"] as JsonPrimitive).content.toLong())
        assertNull((root["g"] as JsonPrimitive).content.toLongOrNull())
        assertNull((root["e"] as JsonPrimitive).content.toLongOrNull())
        assertTrue(RelayJson.parse("{\"a\":9223372036854775808}") is RelayParse.Rejected)
        assertTrue(RelayJson.parse("{\"a\":-9223372036854775809}") is RelayParse.Rejected)
        assertEquals(RelayRejection.NUMBER, rejected("{\"a\":1e999}"))
    }

    @Test fun nullAndBooleanAndEscapesParseExactly() {
        val root =
            parsed(
                "{\"n\":null,\"t\":true,\"f\":false,\"s\":\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00e9\",\"l\":[1,\"x\",null]}"
            )
        assertEquals(kotlinx.serialization.json.JsonNull, root["n"])
        assertEquals(JsonPrimitive(true), root["t"])
        assertEquals(JsonPrimitive(false), root["f"])
        assertEquals("a\"b\\c/d\b\u000c\n\r\t\u00e9", (root["s"] as JsonPrimitive).content)
        assertEquals(3, (root["l"] as JsonArray).size)
    }

    @Test fun itemCountIsBoundedBeforeTheTreeGrows() {
        val many = "{\"a\":[" + List(RelayLimits.ITEMS) { "0" }.joinToString(",") + "]}"
        // 65,536 scalars are 131 KB of text, so the byte limit already refuses it; a dense frame is the check.
        assertEquals(RelayRejection.OVERSIZE, rejected(many))
        val dense = "{\"a\":[" + List(32_000) { "0" }.joinToString(",") + "]}"
        assertTrue(dense.length < RelayLimits.FRAME_BYTES)
        assertNotNull(parsed(dense))
    }
}
