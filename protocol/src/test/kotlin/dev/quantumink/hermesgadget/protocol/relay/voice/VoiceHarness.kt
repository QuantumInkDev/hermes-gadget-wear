package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerHarness
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import org.junit.Assert.assertTrue

/**
 * Drives a [VoiceCaptureLedger] beside a Task2 [dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger] the way a controller would: real
 * bindings, the real clock seam, a separate entropy stream for capture ids. Every call goes through the production transition; nothing is mocked.
 */
internal class VoiceHarness(enrollment: EnrollmentScope = StateTestSupport.enrollment) {
    val h = LedgerHarness(enrollment)
    var captures: VoiceCaptureLedger = VoiceCaptureLedger.empty()
    val effects = mutableListOf<CaptureEffect>()
    var shape: AggregateShape = AggregateShape.EMPTY
    val captureEntropy = StateTestSupport.CountingEntropy(100)
    val stream: VoiceStream = VoiceStream.of(1)!!

    fun <R> apply(step: CaptureStep<R>): R {
        captures = step.ledger
        effects.addAll(step.effects)
        return step.result
    }

    fun begin(token: BindingToken, entropy: RequestIdEntropy = captureEntropy): CaptureKey {
        val outcome = apply(captures.begin(h.ledger, h.authority, token, h.now(), entropy, shape))
        assertTrue(outcome.toString(), outcome is BeginOutcome.Began)
        return (outcome as BeginOutcome.Began).key
    }

    /** Frames of 640 PCM bytes as the real assembly builds them, so the counts are the production counts. */
    fun counts(frames: Int = 13, last: Int = 640): FinishCounts {
        var upload = UplinkCapture.start(stream, CaptureFormat.PCM16)
        repeat(frames - 1) { upload = upload.pcm(ByteArray(640)).capture }
        upload = upload.pcm(ByteArray(last)).capture
        return upload.counts()!!
    }

    fun start(token: BindingToken, key: CaptureKey): CapturePlan<CaptureStarted> =
        apply(captures.planStart(h.ledger, h.authority, token, key, stream, CaptureFormat.PCM16))

    fun finish(
        token: BindingToken,
        key: CaptureKey,
        counts: FinishCounts? = counts()
    ): CapturePlan<CaptureResult> = apply(
        captures.planFinish(h.ledger, h.authority, token, key, stream, counts, h.now(), shape)
    )

    /** begin -> planStart -> planFinish for a normal recording; returns the key. */
    fun released(token: BindingToken): CaptureKey {
        val key = begin(token)
        assertTrue(start(token, key) is CapturePlan.Planned)
        assertTrue(finish(token, key) is CapturePlan.Planned)
        return key
    }

    fun result(
        key: CaptureKey,
        state: CaptureState,
        reason: CaptureFailure? = null,
        provider: VoiceProvider = VoiceProvider.NOT_STARTED,
        source: TranscriptSource? = null,
        transcript: String? = null,
        sealed: Long? = 1_000L
    ): CaptureResult = CaptureResult(
        key.captureId,
        key.conversation.conversationId,
        state,
        reason,
        transcript,
        if (transcript != null) h.serverMs + VoiceLimits.TRANSCRIPT_TTL_MS else null,
        source,
        provider,
        sealed
    )

    fun transcribed(
        key: CaptureKey,
        text: String = "hello voice",
        provider: VoiceProvider = VoiceProvider.COMPLETED
    ): CaptureResult =
        result(key, CaptureState.TRANSCRIBED, null, provider, TranscriptSource.CONFIGURED, text)

    fun onResult(key: CaptureKey, result: CaptureResult): ResultOutcome =
        apply(captures.onResult(h.ledger, key, result, h.now()))

    fun entry(key: CaptureKey): CaptureEntry = captures.entry(key)!!

    /** Another enrollment (another endpoint, profile, key or review epoch) with its own current binding in the same authority. */
    fun otherScope(
        endpoint: String = "endpoint-b",
        enrollmentKey: String = "phone-key-1",
        epoch: Long = 0,
        profile: ProfileId? = null
    ): Pair<EnrollmentScope, BindingToken> {
        val scope = EnrollmentScope(
            RelayScope(
                endpoint,
                profile ?: StateTestSupport.clockInfo.profileId
            ),
            enrollmentKey,
            epoch
        )
        val template: Binding = StateTestSupport.attached(0)
        val step = h.authority.attach(scope, template)
        h.authority = step.authority
        return scope to step.token
    }
}
