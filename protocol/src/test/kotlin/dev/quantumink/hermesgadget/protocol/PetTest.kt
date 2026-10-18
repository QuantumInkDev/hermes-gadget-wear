package dev.quantumink.hermesgadget.protocol

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTest {
    private val data = ByteArray(64) { it.toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
        "%02x".format(it)
    }
    private fun manifest(width: Int = 1536, frames: Int = 6) = requireNotNull(
        Message.parse(
            """
        {"type":"pet.manifest","name":"Sample","cell_width":192,"cell_height":208,"loop_ms":1100,
         "sheets":[{"kind":"base","sha256":"$hash","bytes":64,"width":$width,"height":1872,
         "rows":[{"name":"idle","index":0,"frames":$frames}]}]}
            """.trimIndent()
        )
    )
    private fun message(type: String) = requireNotNull(
        Message.parse(
            """
        {"type":"$type","stream":1,"bytes":64,"sha256":"$hash","format":"png"}
            """.trimIndent()
        )
    )

    private fun overlayManifest(
        descriptor: String,
        kind: String = "companion",
        label: String = "talking",
        frames: Int = 3
    ): Message = requireNotNull(
        Message.parse(
            """{"type":"pet.manifest","name":"Synthetic","cell_width":192,"cell_height":208,"loop_ms":1100,
            "sheets":[{"kind":"base","sha256":"${"a".repeat(
                64
            )}","bytes":64,"width":1152,"height":208,
            "rows":[{"name":"idle","index":0,"frames":6}]},
            {"kind":"$kind","sha256":"${"b".repeat(64)}","bytes":64,"width":1152,"height":624,
            "rows":[{"name":"$label","index":1,"frames":$frames,"overlay":$descriptor}]}]}"""
        )
    )

    @Test fun rejectsMalformedOrMisplacedExpressionDescriptors() {
        val invalid = listOf(
            "null", "true", "[]", "{}",
            """{"anchor_frame":true,"rect":[80,100,112,124]}""",
            """{"anchor_frame":1,"rect":[80,100,112,124]}""",
            """{"anchor_frame":0,"rect":[true,100,112,124]}""",
            """{"anchor_frame":0,"rect":[80.0,100,112,124]}""",
            """{"anchor_frame":0,"rect":[80,100,112]}""",
            """{"anchor_frame":0,"rect":[80,100,112,124,125]}""",
            """{"anchor_frame":0,"rect":[-1,100,112,124]}""",
            """{"anchor_frame":0,"rect":[80,100,193,124]}""",
            """{"anchor_frame":0,"rect":[80,100,112,209]}""",
            """{"anchor_frame":0,"rect":[80,100,80,124]}""",
            """{"anchor_frame":0,"rect":[0,0,97,104]}""",
            """{"anchor_frame":0,"rect":[0,0,96,105]}""",
            """{"anchor_frame":0,"rect":[80,100,112,124],"mask":"no"}"""
        )
        invalid.forEach { descriptor ->
            org.junit.Assert.assertThrows(descriptor, IllegalArgumentException::class.java) {
                PetManifest.parse(overlayManifest(descriptor))
            }
        }
        val valid = """{"anchor_frame":0,"rect":[80,100,112,124]}"""
        val parsed = PetManifest.parse(overlayManifest(valid))
        val overlay = requireNotNull(parsed.sheets[1].rows[0].overlay)
        assertEquals(80, overlay.left)
        assertEquals(124, overlay.bottom)
        assertEquals(
            80,
            PetManifest.parse(
                requireNotNull(Message.parse(parsed.encoded))
            ).sheets[1].rows[0].overlay?.left
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PetSheet("base", hash, 64, 1152, 208, listOf(PetRow("talking", 0, 3, overlay)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PetManifest.parse(overlayManifest(valid, label = "listening"))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PetManifest.parse(overlayManifest(valid, frames = 2))
        }
    }

    @Test fun verifiesHashSequenceLengthAndExpiryBeforePromotion() {
        val receiver = PetTransfers()
        assertTrue(receiver.message(manifest(), 0).single() is PetEffect.Manifest)
        receiver.message(message("asset.start"), 1)
        receiver.binary(BinaryFrame(4, 1, 0, data.copyOfRange(0, 32)), 2)
        receiver.binary(BinaryFrame(4, 1, 1, data.copyOfRange(32, 64)), 3)
        assertArrayEquals(
            data,
            (receiver.message(message("asset.end"), 4).single() as PetEffect.Asset).bytes
        )
        receiver.message(message("asset.start"), 5)
        receiver.binary(BinaryFrame(4, 1, 1, data), 6)
        assertTrue(receiver.message(message("asset.end"), 7).isEmpty())
        receiver.message(message("asset.start"), 8)
        receiver.binary(BinaryFrame(4, 1, 0, ByteArray(64)), 9)
        assertTrue(receiver.message(message("asset.end"), 10).isEmpty())
        receiver.message(manifest(), 11)
        receiver.message(message("asset.start"), 12)
        receiver.tick(15012)
        receiver.binary(BinaryFrame(4, 1, 0, data), 15013)
        assertTrue(receiver.message(message("asset.end"), 15014).isEmpty())
    }

    @Test fun rejectsBadGeometryAndUnadvertisedChannel() {
        val receiver = PetTransfers()
        assertTrue(receiver.message(manifest(99999), 0).isEmpty())
        assertTrue(receiver.message(manifest(frames = 7), 0).isEmpty())
        assertEquals(null, BinaryFrame.parse(BinaryFrame(4, 1, 0, data).encode()))
        assertEquals(4, BinaryFrame.parse(BinaryFrame(4, 1, 0, data).encode(), setOf(4))?.channel)
    }

    @Test fun missingStatesAndMouthHysteresisKeepLegacyPetsUsable() {
        val rows = setOf("idle", "wave", "jump", "run", "review")
        assertEquals("wave", PetStates.row("waving", rows))
        assertEquals("review", PetStates.row("listening", rows))
        assertEquals("idle", PetStates.row("talking", rows))
        assertEquals("idle", PetStates.row("sleeping", rows))
        assertEquals(2, PetStates.mouth(.19f, 0))
        assertEquals(2, PetStates.mouth(.14f, 2))
        assertEquals(1, PetStates.mouth(.08f, 2))
        assertEquals(1, PetStates.mouth(.04f, 1))
        assertEquals(0, PetStates.mouth(0f, 1))
    }

    @Test fun completedTurnsEmitOneCueAndIgnoreStaleEnds() {
        val conversation = Conversation()
        conversation.setPaired(true)
        fun turn(type: String, id: String, outcome: String = "success") = requireNotNull(
            Message.parse(
                """{"type":"$type","turn":"$id","outcome":"$outcome"}"""
            )
        )
        conversation.receive(turn("turn.start", "current"), 0)
        assertTrue(conversation.receive(turn("turn.end", "old"), 1).isEmpty())
        assertEquals(
            "jumping",
            (
                conversation.receive(
                    turn("turn.end", "current"),
                    2
                ).single() as ConversationEffect.PetCue
                ).mode
        )
        assertTrue(conversation.receive(turn("turn.end", "current"), 3).isEmpty())
        conversation.receive(turn("turn.start", "next"), 4)
        assertEquals(
            "failed",
            (
                conversation.receive(
                    turn("turn.end", "next", "failure"),
                    5
                ).single() as ConversationEffect.PetCue
                ).mode
        )
        assertEquals(
            "waving",
            (conversation.cancel(6, true).last() as ConversationEffect.PetCue).mode
        )
    }
}
