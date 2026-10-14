package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortraitAnimationsTest {
    private val gifHash = "a".repeat(64)
    private val pngHash = "b".repeat(64)
    private val fields = "\"sha256\":\"$gifHash\",\"bytes\":123,\"fallback_sha256\":\"$pngHash\""
    private val literal = """{"version":1,"animations":{"box:agent":{$fields}}}"""

    @Test fun independentVersionOneLiteralBindsPageGifLengthAndPngFallback() {
        val parsed = PortraitAnimations.decode(literal)
        assertEquals(setOf("box:agent"), parsed.keys)
        assertEquals(AnimationDescriptor(gifHash, 123, pngHash), parsed.getValue("box:agent"))
        assertEquals(literal, PortraitAnimations.encode(parsed))
        assertEquals(
            emptyMap<String, AnimationDescriptor>(),
            PortraitAnimations.decode("""{"version":1,"animations":{}}""")
        )
        assertEquals("/hermes/animations/v1", PortraitAnimations.DATA_PATH)
        assertEquals("/hermes/animation-assets/v1/", PortraitAnimations.ASSET_PATH)
    }

    @Test fun duplicateFieldsKeysAndEscapedAliasesCannotSilentlyOverwriteBindings() {
        for (invalid in listOf(
            """{"version":1,"version":1,"animations":{}}""",
            """{"version":1,"animations":{},"animations":{}}""",
            """{"version":1,"animations":{"box:agent":{$fields},"box:agent":{$fields}}}""",
            """{"version":1,"animations":{"box:agent":{$fields},"\u0062ox:agent":{$fields}}}""",
            """{"version":1,"animations":{"box:agent":{$fields,"bytes":124}}}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PortraitAnimations.decode(invalid)
            }
        }
    }

    @Test fun rejectsWrongTypesUnknownVersionsFieldsHashesKeysAndBounds() {
        for (invalid in listOf(
            literal.replace("\"version\":1", "\"version\":\"1\""),
            literal.replace("\"version\":1", "\"version\":2"),
            literal.replace("\"bytes\":123", "\"bytes\":\"123\""),
            literal.replace("\"bytes\":123", "\"bytes\":123.0"),
            literal.replace("\"bytes\":123", "\"bytes\":0"),
            literal.replace("\"bytes\":123", "\"bytes\":262145"),
            literal.replace(gifHash, "A".repeat(64)),
            literal.replace(pngHash, "c".repeat(63)),
            literal.replace("box:agent", "box:agent:extra"),
            literal.replace("box:agent", "box:../agent"),
            literal.replace("box:agent", "agent"),
            literal.replace("box:agent", "x".repeat(65) + ":agent"),
            """{"version":1,"animations":[]}""",
            """{"version":1,"animations":{"box:agent":null}}""",
            """{"version":1,"animations":{"box:agent":{$fields,"credential":"ignored"}}}""",
            """{"version":1,"animations":{},"extra":true}""",
            " ".repeat(16385)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PortraitAnimations.decode(invalid)
            }
        }
        val full = (0 until 32).associate {
            "box:p$it" to
                AnimationDescriptor(gifHash, 262144, pngHash)
        }
        assertEquals(full, PortraitAnimations.decode(PortraitAnimations.encode(full)))
        assertThrows(IllegalArgumentException::class.java) {
            PortraitAnimations.encode(
                full + ("box:extra" to AnimationDescriptor(gifHash, 1, pngHash))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortraitAnimations.encode(mapOf("bad key" to AnimationDescriptor(gifHash, 1, pngHash)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimationDescriptor(gifHash, -1, pngHash)
        }
    }

    @Test fun unchangedPngCatalogStillDecodesWithoutAnimationAwareness() {
        val png = """{"version":1,"pages":[{"source_id":"box","source_label":"Synthetic",""" +
            """"profile":{"id":"agent","name":"Agent","portrait":{"sha256":"$pngHash",""" +
            """"bytes":123,"path":"/assets/$pngHash.png"}},""" +
            """"status":"AVAILABLE","checked_at":0}]}"""
        val page = ProfileCatalog.decodePages(png).single()
        assertEquals("box:agent", page.key)
        assertEquals(pngHash, page.profile.portrait?.sha256)
        PortraitAnimations.decode(literal)
        assertEquals(png, ProfileCatalog.encodePages(listOf(page)))
    }
}
