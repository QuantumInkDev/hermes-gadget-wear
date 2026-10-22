package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ExistingBinding
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import java.io.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scopes are exact and independent; a binding is transient, current only until the next attach. */
class StateScopesTest {
    private val profile = StateTestSupport.clockInfo.profileId
    private val other = ProfileId.of("11111111-2222-4333-8444-555555555555")!!

    @Test fun equalTextualIdsUnderDifferentEndpointsProfilesEnrollmentsAndReviewsAreIndependent() {
        // Each line changes exactly one dimension: endpoint, profile, device key, reviewed identity.
        val a = EnrollmentScope(RelayScope("endpoint-a", profile), "key-1", 0)
        assertEquals(a, EnrollmentScope(RelayScope("endpoint-a", profile), "key-1", 0))
        assertNotEquals(a, EnrollmentScope(RelayScope("endpoint-b", profile), "key-1", 0))
        assertNotEquals(a, EnrollmentScope(RelayScope("endpoint-a", other), "key-1", 0))
        assertNotEquals(a, EnrollmentScope(RelayScope("endpoint-a", profile), "key-2", 0))
        assertNotEquals(a, EnrollmentScope(RelayScope("endpoint-a", profile), "key-1", 1))
        val conversation = StateTestSupport.attached().conversationId
        assertNotEquals(
            ConversationScope(a, conversation),
            ConversationScope(a.copy(reviewEpoch = 1), conversation)
        )
        assertEquals(ConversationScope(a, conversation), ConversationScope(a, conversation))
    }

    @Test fun enrollmentLabelsRefuseBlankOversizeControlAndNegativeReviewAndNeverPrint() {
        for (bad in listOf("", "x".repeat(129), "a\nb", "a\u0000b")) {
            try {
                EnrollmentScope(StateTestSupport.scope, bad, 0)
                throw AssertionError("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            EnrollmentScope(StateTestSupport.scope, "k", -1)
            throw AssertionError("accepted")
        } catch (_: IllegalArgumentException) {
        }
        val scope = EnrollmentScope(StateTestSupport.scope, "private-enrollment-label", 7)
        assertFalse(scope.toString().contains("private"))
        assertFalse(
            ConversationScope(
                scope,
                StateTestSupport.attached().conversationId
            ).toString().contains("private")
        )
    }

    @Test fun anAttachMakesTheCurrentTokenAndTheNextAttachRetiresTheOldOne() {
        val scope = StateTestSupport.enrollment
        val first = RelayAuthority.fresh().attach(scope, StateTestSupport.attached(0))
        assertTrue(first.authority.isCurrent(first.token))
        assertNull(first.retired)
        assertEquals(1, first.token.serial)
        val second = first.authority.attach(scope, StateTestSupport.attached(1))
        assertEquals(2, second.token.serial)
        assertSame(first.token, second.retired)
        assertFalse(second.authority.isCurrent(first.token))
        assertTrue(second.authority.isCurrent(second.token))
        // The earlier authority value is unchanged: transitions are pure.
        assertTrue(first.authority.isCurrent(first.token))
        assertEquals(second.token, second.authority.current(scope))
    }

    @Test fun tokensCarryExactlyTheAttachResultAndOfferTheCallArguments() {
        val binding = StateTestSupport.attached(1)
        val step = RelayAuthority.fresh().attach(StateTestSupport.enrollment, binding)
        val token = step.token
        assertEquals(BindingKind.RELAY, token.kind)
        assertEquals(binding.bindingId, token.bindingId)
        assertEquals(binding.conversationId, token.conversationId)
        assertEquals(binding.authorityEpoch, token.authorityEpoch)
        assertEquals(binding.executionGeneration, token.executionGeneration)
        assertEquals(binding.replayEpoch, token.replayEpoch)
        assertEquals(binding.lastSequence, token.baselineSequence)
        assertEquals(binding.ref, token.ref)
        assertEquals(
            ConversationScope(StateTestSupport.enrollment, binding.conversationId),
            token.conversation
        )
    }

    @Test fun scopesHoldIndependentBindingsAndRetiringOneNeverTouchesAnother() {
        val one = StateTestSupport.enrollment
        val two = EnrollmentScope(StateTestSupport.scope, "watch-key-1", 0)
        val a = RelayAuthority.fresh().attach(one, StateTestSupport.attached(0))
        // The same binding text under another enrollment is another scope.
        val b = a.authority.attach(two, StateTestSupport.attached(0))
        assertTrue(b.authority.isCurrent(a.token))
        assertTrue(b.authority.isCurrent(b.token))
        assertNull(b.retired)
        val without = b.authority.retire(a.token)
        assertFalse(without.isCurrent(a.token))
        assertTrue(without.isCurrent(b.token))
        // Retiring a token that is already stale changes nothing.
        val again = without.retire(a.token)
        assertSame(without, again)
        val stale = b.authority.attach(one, StateTestSupport.attached(1))
        assertSame(stale.authority.retire(a.token), stale.authority)
        assertTrue(stale.authority.isCurrent(stale.token))
        assertSame(without, without.retireScope(one))
        assertTrue(RelayAuthority.fresh().isEmpty)
    }

    @Test fun anExistingBindingIsATokenOfItsOwnKindAndAFreshAuthorityStartsEmpty() {
        val existing = ExistingBinding.decode(
            RelayVectors.forMethod(RelayMethod.EXISTING_ATTACH).first { it.isSuccess }.result
        )
        val step = RelayAuthority.fresh().attachExisting(StateTestSupport.enrollment, existing)
        assertEquals(BindingKind.EXISTING, step.token.kind)
        assertEquals(existing.executionGeneration, step.token.executionGeneration)
        assertTrue(RelayAuthority.fresh().isEmpty)
        assertEquals(0, RelayAuthority.fresh().lastSerial)
    }

    @Test fun aTokenAndTheAuthorityAreNeverPersistableAndPrintNoBindingText() {
        val step = RelayAuthority.fresh().attach(
            StateTestSupport.enrollment,
            StateTestSupport.attached()
        )
        assertFalse(Serializable::class.java.isAssignableFrom(BindingToken::class.java))
        assertFalse(Serializable::class.java.isAssignableFrom(RelayAuthority::class.java))
        assertFalse(step.token.toString().contains(step.token.bindingId.value))
        assertFalse(step.token.toString().contains(step.token.replayEpoch))
    }
}
