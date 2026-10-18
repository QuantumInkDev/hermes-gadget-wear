package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.Conversation
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.Message
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAudioCapabilitiesTest {
    @Test fun probeResultKeepsPcmFallbackAndExplicitSpeechOffer() {
        for (opus in listOf(false, true)) {
            for (client in listOf(false, true)) {
                val caps = PhoneConversation.audioCapabilities(opus, client)
                for (name in listOf("mic", "speaker")) {
                    val formats =
                        (caps.getValue(name) as JsonObject)["formats"] as? JsonArray
                            ?: JsonArray(emptyList())
                    assertTrue(formats.contains(JsonPrimitive("pcm16")))
                    assertEquals(opus, formats.contains(JsonPrimitive("opus")))
                }
                assertEquals(if (client) JsonPrimitive("client") else null, caps["tts"])
                assertFalse(caps.containsKey("actions"))
            }
        }
    }

    @Test fun negotiatedDirectionsRemainIndependentAndCaptureTokenUsesSelectedFormat() {
        for ((mic, speaker) in listOf("opus" to "pcm16", "pcm16" to "opus")) {
            val conversation = Conversation(supportsOpus = true)
            conversation.setPaired(true)
            conversation.selectAudio(
                requireNotNull(
                    Message.parse(
                        """{"type":"welcome","audio":{"mic":"$mic","speaker":"$speaker"}}"""
                    )
                )
            )
            assertEquals(mic, conversation.state.microphoneFormat)
            assertEquals(speaker, conversation.state.speakerFormat)
            val effects = conversation.startRecording(1)
            assertEquals(
                mic,
                effects.filterIsInstance<ConversationEffect.CaptureStart>().single().format
            )
        }
    }
}
