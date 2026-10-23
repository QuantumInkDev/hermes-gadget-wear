package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.state.ConversationScope
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import java.util.Collections

/** The exact message a coverage belongs to: its message id under its conversation and enrollment scope. A change of any part is another message. */
data class CoverageKey(val conversation: ConversationScope, val message: MessageId) {
    override fun toString(): String = "CoverageKey(message=${message.value}, scope=redacted)"
}

/** Why a speech did not play to its end. The interrupted range stays uncovered whatever the reason; nothing resumes by itself. */
enum class InterruptReason {
    USER_STOP,
    FOCUS_LOSS,
    ROUTE_CHANGE,
    ABORTED,
    STREAM_VIOLATION,
    HIDDEN,
    REPLACED
}

enum class CoverageRefusal {
    /** A state that was not issued for playback (off, stopped, failed, ...) or a result with no message, source or range. */
    NOT_FOR_PLAYBACK,
    START_NOT_AN_ISSUED_BOUNDARY,

    /** The same source digest with another speakable end or limited flag: a contradictory server answer. */
    SOURCE_INCONSISTENT,
    SPEECH_ID_CONFLICT,
    UNKNOWN_ATTEMPT,
    UNKNOWN_CHUNK,

    /** A client-mode range is covered only when EVERY chunk has played. */
    CHUNKS_PENDING
}

/** A local resource limit of the coverage ledger (policy). Reaching one is a typed result; nothing is evicted or merged away. */
enum class CoverageLimit { MESSAGES, ATTEMPTS, INTERVALS, BOUNDARIES }

sealed interface CoverageOutcome {
    /** [sourceChanged]: another source digest replaced the earlier coverage of this message, which is gone (it described text that no longer exists). */
    class Issued(val sourceChanged: Boolean) : CoverageOutcome {
        override fun toString(): String = "Issued(sourceChanged=$sourceChanged)"
    }

    class Covered(val frontier: Int, val complete: Boolean) : CoverageOutcome {
        override fun toString(): String = "Covered(frontier=$frontier, complete=$complete)"
    }

    class ChunkPlayed(val remaining: Int) : CoverageOutcome {
        override fun toString(): String = "ChunkPlayed(remaining=$remaining)"
    }

    /** The range stays uncovered; [resumeStart] is where the interrupted range starts (the only part to re-speak). */
    class Interrupted(val resumeStart: Int) : CoverageOutcome {
        override fun toString(): String = "Interrupted(resumeStart=$resumeStart)"
    }

    class Refused(val reason: CoverageRefusal) : CoverageOutcome {
        override fun toString(): String = "Refused($reason)"
    }

    class Backpressure(val limit: CoverageLimit) : CoverageOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }
}

class CoverageStep(val coverage: SpeechCoverage, val outcome: CoverageOutcome) {
    override fun toString(): String = "CoverageStep($outcome)"
}

/** Where the next deliberate request starts. It never lies beyond an uncovered interval. */
class Continuation(
    /** The exact source the `start_cp` is an issued boundary of (`source_sha256` of a `more` request). */
    val source: SourceDigest,
    /** `start_cp` of the next `more`: the end of the contiguous covered prefix. For an interrupted range this is that range's start. */
    val startCp: Int,
    /** The speakable end: [startCp] equal to it means nothing is left to speak (the server answers `exhausted`). */
    val speakableEndCp: Int,
    /** Canonical text continues beyond [speakableEndCp] (it stays displayed; speech stopped there). Never a length of the unread tail. */
    val limited: Boolean,
    /** The range that was interrupted at [startCp], if any: the only range a Resume re-speaks. */
    val interruptedEndCp: Int?,
    /** Why that range was interrupted (for the notice "Stopped: earbuds disconnected"); never a reason to resume by itself. */
    val interruptedBecause: InterruptReason?
) {
    val exhausted: Boolean get() = startCp >= speakableEndCp

    override fun toString(): String =
        "Continuation(start=$startCp, end=$speakableEndCp, limited=$limited, exhausted=$exhausted)"
}

internal class Interval(val start: Int, val end: Int)

internal class InterruptedRange(val range: Interval, val reason: InterruptReason)

internal class Attempt(
    val speech: SpeechId,
    val range: Interval,
    /** Start offsets of the client chunks still to play (null for server audio, which plays as one range). */
    val chunksPending: List<Int>?
)

internal class MessageCoverage(
    val source: SpeechSource,
    val boundaries: List<Int>,
    val played: List<Interval>,
    val attempts: List<Attempt>,
    val interrupted: List<InterruptedRange>
) {
    /** The end of the contiguous covered prefix from 0: where a continuation may start. */
    val frontier: Int get() = played.firstOrNull()?.takeIf { it.start == 0 }?.end ?: 0
}

/**
 * Exact range coverage of canonical replies (design §9.4, ruling R3): a persistent, memory-only value, never saved and never a billing record.
 *
 * What counts: ONLY a range the playback controller reports as completely played ([onPlayed]; for client mode every chunk, [onChunkPlayed]). A receipt,
 * a `completed` provider state or an END frame is not playback. What a continuation may use: the end of the contiguous covered prefix, never beyond an
 * uncovered gap, and an interrupted range restarts at its own start. A changed source digest or a retired scope drops the stale coverage. The end of the
 * text is `speakable_end_cp` (the processed prefix); `total_cp` is never read as an unread-tail length.
 */
class SpeechCoverage private constructor(private val messages: Map<CoverageKey, MessageCoverage>) {
    val size: Int get() = messages.size

    fun frontier(key: CoverageKey): Int? = messages[key]?.frontier

    /** The covered intervals as `[start, end)` pairs, sorted and disjoint. */
    fun covered(key: CoverageKey): List<IntRange> = Collections.unmodifiableList(
        messages[key]?.played.orEmpty().map { it.start until it.end }
    )

    fun isComplete(key: CoverageKey): Boolean =
        messages[key]?.let { it.frontier >= it.source.speakableEndCp } ?: false

    /** The issued boundaries of [key] (valid `start_cp` values of a `more` besides 0), sorted. */
    fun boundaries(key: CoverageKey): List<Int> =
        Collections.unmodifiableList(messages[key]?.boundaries.orEmpty())

    fun continuation(key: CoverageKey): Continuation? {
        val m = messages[key] ?: return null
        val at = m.frontier
        val interrupted = m.interrupted.firstOrNull { it.range.start == at }
        return Continuation(
            m.source.sha256,
            at,
            m.source.speakableEndCp,
            m.source.limited,
            interrupted?.range?.end,
            interrupted?.reason
        )
    }

    // -- issue ------------------------------------------------------------------------------------------------------------------------

    /** Records the range a `speak` answer issued for playback (`preparing`, `streaming`, `completed` or client `text`). Idempotent per speech id. */
    fun onIssued(conversation: ConversationScope, result: SpeechResult): CoverageStep {
        val message = result.messageId
        val source = result.source
        val range = result.range
        val playable =
            result.state == SpeechState.PREPARING || result.state == SpeechState.STREAMING ||
                result.state == SpeechState.COMPLETED || result.state == SpeechState.TEXT
        if (!playable || message == null || source == null ||
            range == null
        ) {
            return refused(CoverageRefusal.NOT_FOR_PLAYBACK)
        }
        val key = CoverageKey(conversation, message)
        val existing = messages[key]
        val changed = existing != null && existing.source.sha256 != source.sha256
        val base = when {
            existing == null -> {
                if (messages.size >=
                    VoiceLimits.COVERAGE_MESSAGES
                ) {
                    return limit(CoverageLimit.MESSAGES)
                }
                MessageCoverage(source, emptyList(), emptyList(), emptyList(), emptyList())
            }
            changed -> MessageCoverage(source, emptyList(), emptyList(), emptyList(), emptyList())
            existing.source.speakableEndCp != source.speakableEndCp ||
                existing.source.limited != source.limited ->
                return refused(CoverageRefusal.SOURCE_INCONSISTENT)
            else -> existing
        }
        val interval = Interval(range.startCp, range.endCp)
        base.attempts.firstOrNull { it.speech == result.speechId }?.let {
            return if (it.range.start == interval.start && it.range.end == interval.end) {
                CoverageStep(this, CoverageOutcome.Issued(false))
            } else {
                refused(CoverageRefusal.SPEECH_ID_CONFLICT)
            }
        }
        if (interval.start != 0 &&
            interval.start !in base.boundaries
        ) {
            return refused(CoverageRefusal.START_NOT_AN_ISSUED_BOUNDARY)
        }
        if (base.attempts.size >=
            VoiceLimits.COVERAGE_ATTEMPTS
        ) {
            return limit(CoverageLimit.ATTEMPTS)
        }
        val chunks = result.chunks?.map { it.startCp }
        val boundaries = (base.boundaries + interval.start + interval.end).filter {
            it > 0
        }.distinct().sorted()
        if (boundaries.size >
            VoiceLimits.COVERAGE_BOUNDARIES
        ) {
            return limit(CoverageLimit.BOUNDARIES)
        }
        val next = MessageCoverage(
            base.source,
            boundaries,
            base.played,
            base.attempts + Attempt(result.speechId, interval, chunks),
            base.interrupted
        )
        return CoverageStep(
            SpeechCoverage(messages + (key to next)),
            CoverageOutcome.Issued(changed)
        )
    }

    // -- play -------------------------------------------------------------------------------------------------------------------------

    /** One client-mode chunk finished playing. The range is covered only after the last one ([onPlayed]). */
    fun onChunkPlayed(
        conversation: ConversationScope,
        message: MessageId,
        speech: SpeechId,
        chunkStartCp: Int
    ): CoverageStep {
        val key = CoverageKey(conversation, message)
        val m = messages[key] ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        val attempt =
            m.attempts.firstOrNull { it.speech == speech }
                ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        val pending = attempt.chunksPending ?: return refused(CoverageRefusal.UNKNOWN_CHUNK)
        if (chunkStartCp !in pending) return refused(CoverageRefusal.UNKNOWN_CHUNK)
        val left = pending - chunkStartCp
        val replaced = m.attempts.map {
            if (it ===
                attempt
            ) {
                Attempt(attempt.speech, attempt.range, left)
            } else {
                it
            }
        }
        return CoverageStep(
            SpeechCoverage(
                messages +
                    (
                        key to
                            MessageCoverage(
                                m.source,
                                m.boundaries,
                                m.played,
                                replaced,
                                m.interrupted
                            )
                        )
            ),
            CoverageOutcome.ChunkPlayed(left.size)
        )
    }

    /**
     * The playback controller reports that the range of [speech] played COMPLETELY (END received and the audio drained; every chunk for client mode).
     * Only now is it covered. An interval that cannot be added without exceeding [VoiceLimits.COVERAGE_INTERVALS] is refused (typed), never merged away.
     */
    fun onPlayed(
        conversation: ConversationScope,
        message: MessageId,
        speech: SpeechId
    ): CoverageStep {
        val key = CoverageKey(conversation, message)
        val m = messages[key] ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        val attempt =
            m.attempts.firstOrNull { it.speech == speech }
                ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        if (attempt.chunksPending?.isNotEmpty() ==
            true
        ) {
            return refused(CoverageRefusal.CHUNKS_PENDING)
        }
        val merged = merge(m.played, attempt.range)
        if (merged.size > VoiceLimits.COVERAGE_INTERVALS) return limit(CoverageLimit.INTERVALS)
        val interrupted = m.interrupted.filterNot { inside(it.range, merged) }
        val next =
            MessageCoverage(m.source, m.boundaries, merged, m.attempts - attempt, interrupted)
        return CoverageStep(
            SpeechCoverage(messages + (key to next)),
            CoverageOutcome.Covered(next.frontier, next.frontier >= next.source.speakableEndCp)
        )
    }

    /** Stop, focus or route loss, abort, hide, a newer speech: the range stays UNCOVERED and is remembered as the one to resume. */
    fun onInterrupted(
        conversation: ConversationScope,
        message: MessageId,
        speech: SpeechId,
        reason: InterruptReason
    ): CoverageStep {
        val key = CoverageKey(conversation, message)
        val m = messages[key] ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        val attempt =
            m.attempts.firstOrNull { it.speech == speech }
                ?: return refused(CoverageRefusal.UNKNOWN_ATTEMPT)
        val same = m.interrupted.firstOrNull {
            it.range.start == attempt.range.start &&
                it.range.end == attempt.range.end
        }
        val remembered = when {
            same != null -> m.interrupted.map {
                if (it ===
                    same
                ) {
                    InterruptedRange(it.range, reason)
                } else {
                    it
                }
            }
            m.interrupted.size >= VoiceLimits.COVERAGE_INTERVALS -> return limit(
                CoverageLimit.INTERVALS
            )
            else -> (m.interrupted + InterruptedRange(attempt.range, reason)).sortedBy {
                it.range.start
            }
        }
        val next =
            MessageCoverage(m.source, m.boundaries, m.played, m.attempts - attempt, remembered)
        return CoverageStep(
            SpeechCoverage(messages + (key to next)),
            CoverageOutcome.Interrupted(attempt.range.start)
        )
    }

    // -- invalidation -------------------------------------------------------------------------------------------------------------------

    /** The message was deleted, revised away or is no longer readable: its coverage describes nothing. */
    fun forget(key: CoverageKey): SpeechCoverage = if (key in
        messages
    ) {
        SpeechCoverage(messages - key)
    } else {
        this
    }

    /** A retired enrollment, a replaced profile or a changed review epoch: the coverage of that scope is gone. */
    fun invalidate(scope: EnrollmentScope): SpeechCoverage {
        val keep = messages.filterKeys { it.conversation.enrollment != scope }
        return if (keep.size == messages.size) this else SpeechCoverage(keep)
    }

    /** A canonical delete or tombstone of one conversation. */
    fun invalidate(conversation: ConversationScope): SpeechCoverage {
        val keep = messages.filterKeys { it.conversation != conversation }
        return if (keep.size == messages.size) this else SpeechCoverage(keep)
    }

    // -- internals ---------------------------------------------------------------------------------------------------------------------

    private fun refused(reason: CoverageRefusal) =
        CoverageStep(this, CoverageOutcome.Refused(reason))

    private fun limit(limit: CoverageLimit) =
        CoverageStep(this, CoverageOutcome.Backpressure(limit))

    private fun merge(played: List<Interval>, added: Interval): List<Interval> {
        var start = added.start
        var end = added.end
        val out = ArrayList<Interval>(played.size + 1)
        var placed = false
        for (interval in played) {
            when {
                interval.end < start -> out.add(interval)
                interval.start > end -> {
                    if (!placed) {
                        out.add(Interval(start, end))
                        placed = true
                    }
                    out.add(interval)
                }
                else -> {
                    start = minOf(start, interval.start)
                    end = maxOf(end, interval.end)
                }
            }
        }
        if (!placed) out.add(Interval(start, end))
        return out
    }

    private fun inside(range: Interval, covered: List<Interval>): Boolean =
        covered.any { it.start <= range.start && range.end <= it.end }

    override fun toString(): String = "SpeechCoverage(messages=${messages.size})"

    companion object {
        fun empty(): SpeechCoverage = SpeechCoverage(emptyMap())
    }
}
