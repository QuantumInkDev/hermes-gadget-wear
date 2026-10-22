package dev.quantumink.hermesgadget.protocol.relay

import java.io.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identifiers are opaque; endpoint and profile are independent; a binding is never a persistable identity. */
class RelayIdentityAndScopeTest {
    private val info = Info.decode(
        RelayVectors.forMethod(RelayMethod.INFO).first {
            it.isSuccess &&
                "server_time_ms" in it.result
        }.result
    )

    @Test fun serverIdentifiersAreCanonicalLowercaseUuidsAndOtherwiseOpaque() {
        val valid = "0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11"
        assertEquals(valid, ConversationId.of(valid)!!.value)
        assertEquals(valid, TaskId.of(valid)!!.value)
        for (bad in listOf(
            "",
            valid.uppercase(),
            valid.replace("-", ""),
            valid + "0",
            "0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c1",
            "g" + valid.drop(1),
            " $valid"
        )) {
            assertNull(bad, ConversationId.of(bad))
            assertNull(bad, BindingId.of(bad))
            assertNull(bad, PromptId.of(bad))
            assertNull(bad, ProfileId.of(bad))
        }
        // The version nibble is irrelevant for server ids: equality is the only operation.
        assertEquals(
            "00000000-0000-0000-0000-000000000000",
            MessageId.of("00000000-0000-0000-0000-000000000000")!!.value
        )
    }

    @Test fun endpointAndProfileAreIndependentScopeDimensions() {
        val profile = info.profileId
        val other = ProfileId.of("11111111-2222-4333-8444-555555555555")!!
        val here = RelayScope("endpoint-a", profile)
        assertEquals(here, RelayScope("endpoint-a", profile))
        // The same profile id seen through another endpoint is another scope.
        assertNotEquals(here, RelayScope("endpoint-b", profile))
        assertNotEquals(here, RelayScope("endpoint-a", other)) // same endpoint, a profile change
        assertTrue(here.matches(info))
        assertFalse(RelayScope("endpoint-a", other).matches(info))
        assertEquals(here, RelayScope.of("endpoint-a", info))
        val conversation = ConversationId.of("4b327fa7-d0ea-46fa-a917-3fdc62d1c349")!!
        assertNotEquals(
            ConversationKey(here, conversation),
            ConversationKey(RelayScope("endpoint-b", profile), conversation)
        )
        assertNotEquals(Scoped(here, 1), Scoped(RelayScope("endpoint-b", profile), 1))
    }

    @Test fun scopeLabelsRefuseBlankOversizeAndControlKeysAndNeverPrintThem() {
        for (bad in listOf("", "x".repeat(129), "a\nb", "a\u0000b")) {
            try {
                RelayScope(bad, info.profileId)
                throw AssertionError("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(
            RelayScope("https-secret-endpoint", info.profileId).toString().contains("secret")
        )
    }

    @Test fun aBindingIsTransportLocalNotSerializableAndHasNoPersistentIdentityOfItsOwn() {
        val binding = Binding.decode(
            RelayVectors.forMethod(RelayMethod.CONVERSATION_ATTACH).first {
                it.isSuccess
            }.result
        )
        assertFalse(Serializable::class.java.isAssignableFrom(Binding::class.java))
        assertFalse(Serializable::class.java.isAssignableFrom(BindingRef::class.java))
        // What a consumer may keep is the conversation id under its scope; the epoch dies with the socket.
        val key = ConversationKey(RelayScope("endpoint-a", info.profileId), binding.conversationId)
        assertEquals(binding.conversationId, key.conversationId)
        assertFalse(key.toString().contains(binding.bindingId.value))
        assertTrue(
            Binding::class.java.methods.none {
                it.name.startsWith("persist") ||
                    it.name == "toKey"
            }
        )
    }
}
