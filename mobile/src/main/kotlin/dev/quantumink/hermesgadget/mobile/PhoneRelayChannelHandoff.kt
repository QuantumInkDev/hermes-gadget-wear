package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayFailure
import dev.quantumink.hermesgadget.protocol.relay.RelayListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The actual channel-to-foreground monitor boundary used by PhoneConversation. The transport only CAS-enqueues and posts;
 * only the posted drain enters the owner monitor. [post] must enqueue, never run inline (production: main Handler).
 * One drain is outstanding; at most 16 queued + 16 draining events. Overflow seals normal input and reports unavailable,
 * retaining already queued authority verdicts and allowing only two stronger terminal verdict upgrades (review, denial).
 */
internal class PhoneRelayChannelHandoff(
    private val owner: Any,
    private val post: (Runnable) -> Unit,
    private val current: () -> Boolean,
    private val consume: (RelayChannelState, Long) -> Unit
) : RelayListener {
    private data class Event(val state: RelayChannelState, val generation: Long)
    private data class Batch(val events: List<Event> = emptyList(), val overflowRank: Int = -1)
    private val pending = AtomicReference(Batch())
    private val scheduled = AtomicBoolean()

    override fun relayState(state: RelayChannelState, generation: Long) {
        val event = Event(state, generation)
        while (true) {
            val before = pending.get()
            val rank = verdictRank(state)
            val after = when {
                before.overflowRank >= 0 -> {
                    if (rank <= before.overflowRank) return
                    // Never flatten a later real permission_denied/profile_changed into generic backpressure.
                    Batch(before.events.take(MAX_QUEUED - 1) + event, rank)
                }
                before.events.size < MAX_QUEUED - 1 -> Batch(before.events + event)
                else -> Batch(
                    before.events + if (rank > 0) {
                        event
                    } else {
                        Event(RelayChannelState.Failed(RelayFailure.SEND_REFUSED), generation)
                    },
                    maxOf(rank, before.events.maxOf { verdictRank(it.state) })
                )
            }
            if (pending.compareAndSet(before, after)) break
        }
        schedule()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) post(Runnable(::drain))
    }

    private fun drain() {
        val batch = pending.getAndUpdate { Batch(overflowRank = it.overflowRank) }
        try {
            synchronized(owner) {
                for (event in batch.events) {
                    // Epoch AND foreground lease are captured per connection and rechecked under its actual owner lock.
                    if (current()) consume(event.state, event.generation)
                }
            }
        } finally {
            scheduled.set(false)
            if (pending.get().events.isNotEmpty()) schedule()
        }
    }

    private fun verdictRank(state: RelayChannelState): Int =
        when ((state as? RelayChannelState.Failed)?.error?.action) {
            RelayErrorAction.STOP_AND_EVICT_SCOPE -> 2
            RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> 1
            else -> 0
        }

    companion object {
        private const val MAX_QUEUED = 16
    }
}
