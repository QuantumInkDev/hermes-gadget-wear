package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import java.util.concurrent.atomic.AtomicLong

/** Why a relay call was refused locally, before anything reached the socket. The handler is still called exactly once. */
enum class RelayRefusal {
    /** Relay is not negotiated on this socket generation (or `relay.info` has not been answered yet). */
    NOT_READY,

    /** `relay.info` did not advertise the method: nothing but `relay.info` is callable before it, and only listed methods after. */
    NOT_ADVERTISED,

    /** The authority or foreground scope that queued this call was replaced before dispatch. */
    SCOPE_REPLACED,

    /** The request frame could not be encoded within the wire bounds. */
    FRAME_REFUSED,

    /** The bounded number of outstanding calls is reached. */
    TOO_MANY_OUTSTANDING,

    /** The correlation was issued for another socket generation. */
    STALE_GENERATION,

    /** The correlation was already used (ids are never reused). */
    CORRELATION_REUSED,

    /** The bounded socket write queue is full or the socket refused the frame. */
    SOCKET_BACKPRESSURE,

    /** The connection's serial event queue is full or the connection is closed. */
    QUEUE_FULL
}

/** What a relay call ended with. Exactly one of these is delivered per call. */
sealed interface RelayCallResult<out R : Any> {
    class Outcome<R : Any>(val outcome: RelayOutcome<R>) : RelayCallResult<R> {
        override fun toString(): String = "Outcome($outcome)"
    }

    /** No answer within the deadline. A late answer is ignored (it names no outstanding call). */
    data object TimedOut : RelayCallResult<Nothing>

    /** The socket generation ended (close, failure, disconnect, cancel) while the call was outstanding. */
    data object Closed : RelayCallResult<Nothing>

    class Refused(val reason: RelayRefusal, val encoding: RelayRejection? = null) :
        RelayCallResult<Nothing> {
        override fun toString(): String = "Refused($reason${encoding?.let { ", $it" } ?: ""})"
    }
}

enum class RelayInvalid {
    /** The welcome carried a `relay` member that is not exactly `{version: 1}`. */
    MALFORMED_WELCOME,

    /** `relay` was negotiated for a device that is not paired: the server never does that. */
    NEGOTIATED_WITHOUT_PAIRING
}

enum class RelayFailure {
    /** A `relay.response` larger than 65,536 UTF-8 bytes, or text that cannot be UTF-8: the server never sends one. */
    OVERSIZE_RESPONSE,
    INFO_REFUSED,
    INFO_TIMED_OUT,

    /** The `relay.info` answer is the O5-only form: no `server_time_ms`, so no clock and no conversations. */
    NO_SERVER_TIME,
    CLOCK_REFUSED,
    SEND_REFUSED
}

/** The per-socket Relay state. Relay is OFF unless the connection was built with [RelayOptIn]. */
sealed interface RelayChannelState {
    data object NotRequested : RelayChannelState
    data object Closed : RelayChannelState
    data object AwaitingWelcome : RelayChannelState

    /** The welcome had no `relay` member: Relay is unavailable here and the stock connection continues unchanged. */
    data object NotOffered : RelayChannelState
    data class Invalid(val reason: RelayInvalid) : RelayChannelState
    data object LoadingInfo : RelayChannelState

    /** [error] is the server's answer when `relay.info` itself was refused (e.g. `permission_denied`): never flattened away. */
    data class Failed(val reason: RelayFailure, val error: RelayError? = null) : RelayChannelState

    /** Negotiated and `relay.info` answered. [serial] changes on every accepted `relay.info`. */
    class Ready(val info: Info, val clock: ServerClock, val serial: Long) : RelayChannelState {
        override fun toString(): String = "Ready(serial=$serial, ${info.methods.size} methods)"
    }
}

/** Called on the connection's serial event thread. Implementations must post and return; they must not block. */
fun interface RelayListener {
    fun relayState(state: RelayChannelState, generation: Long)
}

/**
 * The explicit, per-connection opt-in. Without it a [dev.quantumink.hermesgadget.protocol.DirectConnection] advertises
 * no `relay` capability and routes every frame exactly as before. [monotonicNanos] must be a NON-NEGATIVE monotonic
 * source that keeps counting across sleep (Android: `SystemClock.elapsedRealtimeNanos()`).
 */
class RelayOptIn(val listener: RelayListener, val monotonicNanos: () -> Long) {
    override fun toString(): String = "RelayOptIn"
}

/** What the controller sees of the connection's single Relay owner. Thread-safe except where documented. */
interface RelayTransport {
    /** The socket generation currently served; every correlation names the generation it was issued for. */
    val generation: Long

    /** A fresh, never-reused correlation for the current generation. Safe from any thread. */
    fun correlation(): CorrelationId

    /**
     * Queues [call] on the connection's serial event thread. [handler] is invoked exactly once on that thread. It is
     * called with [RelayCallResult.Refused] when the call is not allowed (not negotiated, not advertised, bounds).
     */
    fun <R : Any> call(
        correlation: CorrelationId,
        call: RelayCall<R>,
        timeoutMs: Long,
        handler: (RelayCallResult<R>) -> Unit
    )

    /**
     * Re-reads `relay.info` (grant or method changes): the answer replaces the channel's state and clock fix, and is also
     * passed to [handler] exactly once on the connection's event thread.
     */
    fun refreshInfo(handler: (RelayCallResult<Info>) -> Unit)

    /** Invalidate delayed frames of the previous controller scope before selection, leave, hide or replacement. */
    fun invalidatePending() = Unit
}

/**
 * The ONE owner of raw `relay.request` / `relay.response` traffic of a connection. It is not thread-safe: every
 * method except [correlation] runs on the connection's serial event thread, next to the socket queue and the
 * generation counter that [dev.quantumink.hermesgadget.protocol.DirectConnection] owns.
 *
 * - Negotiation: only a welcome that carries exactly `relay: {version: 1}` for a paired device starts Relay, and the
 *   first call is `relay.info`. Nothing else is callable before it, and afterwards only listed methods.
 * - Routing: a frame is judged BEFORE the permissive stock parser (see [RelayFrameSniffer]); every `relay.response`
 *   is bounded by bytes (65,536), depth (8) and correlation here and never reaches the stock conversation, however
 *   late, unknown or oversize it is.
 * - Correlations are generation-tagged, bounded in number, never reused, answered once, and ended with an explicit
 *   [RelayCallResult] on timeout, close or refusal. A late answer names no outstanding call and is ignored.
 * - Voice (V5) adds a binary sink and backpressure to this same owner, not a second router.
 */
class RelayChannel internal constructor(
    private val listener: RelayListener,
    private val monotonicNanos: () -> Long,
    private val maxOutstanding: Int = MAX_OUTSTANDING,
    private val infoTimeoutMs: Long = INFO_TIMEOUT_MS
) {
    private class Pending(
        val deadlineMs: Long,
        val valid: () -> Boolean,
        val deliver: (RelayFrame) -> Unit,
        val end: (RelayCallResult<Nothing>) -> Unit
    )

    @Volatile private var generation = 0L
    private val counter = AtomicLong()
    private val pending = LinkedHashMap<String, Pending>()
    private val used = ArrayDeque<String>()
    private val usedSet = HashSet<String>()

    /** Encoded requests waiting for a pacing token (oldest first); each has its `pending` entry and deadline. */
    private class Waiting(val id: String, val text: String, val send: (String) -> Boolean)
    private val waiting = ArrayDeque<Waiting>()
    private var infoSerial = 0L

    /** Every assignment, including same-generation/equal-state replacement, supersedes older continuations. */
    private var transition = 0L
    private var tokens = RATE_CAPACITY
    private var tokensAtMs = 0L

    /** Counted for tests and diagnostics; never printed with content. */
    var ignoredFrames = 0L
        private set
    var handlerFailures = 0L
        private set

    var state: RelayChannelState = RelayChannelState.Closed
        private set

    val currentGeneration: Long get() = generation

    /** Safe from any thread. */
    fun correlation(): CorrelationId {
        val value =
            "r" + generation.toString(RADIX) + "-" + counter.incrementAndGet().toString(RADIX)
        return requireNotNull(CorrelationId.of(value))
    }

    /** A new socket generation: nothing of an older one survives. */
    @Synchronized
    fun open(generation: Long) {
        this.generation = generation
        tokens = RATE_CAPACITY
        tokensAtMs = 0L
        setState(RelayChannelState.AwaitingWelcome) { endAll(RelayCallResult.Closed) }
    }

    @Synchronized
    fun welcome(message: Message, paired: Boolean, nowMs: Long, send: (String) -> Boolean) {
        when (RelayNegotiation.welcome(message)) {
            RelayWelcome.NOT_OFFERED -> setState(RelayChannelState.NotOffered)
            RelayWelcome.INVALID -> setState(
                RelayChannelState.Invalid(RelayInvalid.MALFORMED_WELCOME)
            )
            RelayWelcome.NEGOTIATED -> if (!paired) {
                setState(RelayChannelState.Invalid(RelayInvalid.NEGOTIATED_WITHOUT_PAIRING))
            } else {
                if (setState(RelayChannelState.LoadingInfo)) requestInfo(nowMs, send, null)
            }
        }
    }

    /**
     * Re-reads `relay.info` (grant or method changes, authority refresh). The answer replaces [state] and is also
     * passed to [done]. Requires a negotiated socket.
     */
    @Synchronized
    fun refreshInfo(nowMs: Long, send: (String) -> Boolean, done: (RelayCallResult<Info>) -> Unit) {
        requestInfo(nowMs, send, done)
    }

    private fun requestInfo(
        nowMs: Long,
        send: (String) -> Boolean,
        done: ((RelayCallResult<Info>) -> Unit)?
    ) {
        val call = RelayCalls.info()
        val correlation = correlation()
        call(correlation, call, infoTimeoutMs, nowMs, send) { result ->
            handleInfo(result)
            done?.invoke(result)
        }
    }

    private fun handleInfo(result: RelayCallResult<Info>) {
        when (result) {
            is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
                is RelayOutcome.Success -> adopt(outcome.value)
                is RelayOutcome.Failed ->
                    setState(RelayChannelState.Failed(RelayFailure.INFO_REFUSED, outcome.error))
                is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid ->
                    setState(RelayChannelState.Failed(RelayFailure.INFO_REFUSED))
            }
            RelayCallResult.TimedOut -> setState(
                RelayChannelState.Failed(RelayFailure.INFO_TIMED_OUT)
            )
            RelayCallResult.Closed -> Unit
            is RelayCallResult.Refused -> setState(
                RelayChannelState.Failed(RelayFailure.SEND_REFUSED)
            )
        }
    }

    private fun adopt(info: Info) {
        if (info.serverTimeMs == null) {
            setState(RelayChannelState.Failed(RelayFailure.NO_SERVER_TIME))
            return
        }
        when (val fix = ServerClock.fix(info, monotonicNanos())) {
            is ClockFix.Refused -> setState(RelayChannelState.Failed(RelayFailure.CLOCK_REFUSED))
            is ClockFix.Fixed -> {
                infoSerial = Math.addExact(infoSerial, 1L)
                setState(RelayChannelState.Ready(info, fix.clock, infoSerial))
            }
        }
    }

    /**
     * Queues [call] for [correlation]. [handler] runs exactly once: with the typed outcome, a timeout, a close, or a
     * local refusal.
     */
    @Synchronized
    fun <R : Any> call(
        correlation: CorrelationId,
        call: RelayCall<R>,
        timeoutMs: Long,
        nowMs: Long,
        send: (String) -> Boolean,
        handler: (RelayCallResult<R>) -> Unit
    ) {
        callScoped(correlation, call, timeoutMs, nowMs, send, { true }, handler)
    }

    @Synchronized
    fun <R : Any> callScoped(
        correlation: CorrelationId,
        call: RelayCall<R>,
        timeoutMs: Long,
        nowMs: Long,
        send: (String) -> Boolean,
        scopeCurrent: () -> Boolean,
        handler: (RelayCallResult<R>) -> Unit
    ) {
        fun refuse(reason: RelayRefusal, encoding: RelayRejection? = null) =
            guarded { handler(RelayCallResult.Refused(reason, encoding)) }
        val current = state
        if (!scopeCurrent()) return refuse(RelayRefusal.SCOPE_REPLACED)
        val allowed = when {
            call.method == RelayMethod.INFO ->
                current is RelayChannelState.LoadingInfo || current is RelayChannelState.Ready
            current is RelayChannelState.Ready -> current.info.advertises(call.method)
            else -> false
        }
        if (!allowed) {
            val ready = current is RelayChannelState.Ready
            return refuse(if (ready) RelayRefusal.NOT_ADVERTISED else RelayRefusal.NOT_READY)
        }
        if (generationOf(correlation) != generation) return refuse(RelayRefusal.STALE_GENERATION)
        if (correlation.value in usedSet || correlation.value in pending) {
            return refuse(RelayRefusal.CORRELATION_REUSED)
        }
        if (pending.size >= maxOutstanding) return refuse(RelayRefusal.TOO_MANY_OUTSTANDING)
        val frame = when (val encoded = RelayEnvelope.encodeRequest(correlation, call)) {
            is RelayEncoded.Refused -> return refuse(RelayRefusal.FRAME_REFUSED, encoded.reason)
            is RelayEncoded.Frame -> encoded
        }
        remember(correlation.value)
        val socket = generation
        val authority = (current as? RelayChannelState.Ready)?.serial
        pending[correlation.value] = Pending(
            nowMs + timeoutMs,
            {
                val ready = state as? RelayChannelState.Ready
                scopeCurrent() && socket == generation && if (call.method == RelayMethod.INFO) {
                    state is RelayChannelState.LoadingInfo || ready != null
                } else {
                    ready != null && ready.serial == authority && ready.info.advertises(call.method)
                }
            },
            { reply ->
                guarded { handler(RelayCallResult.Outcome(call.interpret(correlation, reply))) }
            },
            { ended -> guarded { handler(ended) } }
        )
        // Pacing: with no token the request WAITS (in order, under its own deadline); it is never refused for the rate.
        if (waiting.isNotEmpty() || !takeToken(nowMs)) {
            waiting.addLast(Waiting(correlation.value, frame.text, send))
            return
        }
        if (!send(frame.text)) {
            // A failing send may already have ended every call in flight (exactly one result per call).
            if (pending.remove(correlation.value) != null) refuse(RelayRefusal.SOCKET_BACKPRESSURE)
        }
    }

    /**
     * One raw text frame that [RelayFrameSniffer] called a `relay.response`. Returns without side effects on any
     * stock state: the caller never passes the frame on.
     */
    @Synchronized
    fun frame(text: String) {
        val bytes = RelayJson.utf8Length(text, RelayLimits.FRAME_BYTES)
        if (bytes == RelayJson.LONE_SURROGATE || bytes > RelayLimits.FRAME_BYTES) {
            fail(RelayFailure.OVERSIZE_RESPONSE)
            return
        }
        when (val reply = RelayEnvelope.decodeFrame(text)) {
            is RelayFrame.Result -> take(reply.id.value, reply)
            is RelayFrame.Failure -> {
                val id = reply.id
                if (id != null) take(id.value, reply) else everyone(reply)
            }
            // A frame that cannot be attributed: nothing may be settled by it, and every call in flight is suspect.
            is RelayFrame.Invalid -> everyone(reply)
        }
    }

    private fun take(id: String, reply: RelayFrame) {
        val call = pending.remove(id)
        if (call == null) {
            ignoredFrames++
        } else {
            call.deliver(reply)
        }
    }

    private fun everyone(reply: RelayFrame) {
        val all = pending.values.toList()
        pending.clear()
        if (all.isEmpty()) ignoredFrames++
        for (call in all) call.deliver(reply)
    }

    @Synchronized
    fun tick(nowMs: Long, @Suppress("UNUSED_PARAMETER") send: (String) -> Boolean) {
        if (pending.isEmpty()) return
        val expired = pending.filterValues { nowMs >= it.deadlineMs }
        for (id in expired.keys) {
            pending.remove(id)
            waiting.removeAll { it.id == id }
        }
        for (call in expired.values) call.end(RelayCallResult.TimedOut)
        refuseObsolete()
        while (waiting.isNotEmpty() && takeToken(nowMs)) {
            val next = waiting.removeFirst()
            val call = pending[next.id] ?: continue
            if (!call.valid()) {
                pending.remove(next.id)?.end(RelayCallResult.Refused(RelayRefusal.SCOPE_REPLACED))
                continue
            }
            if (!next.send(next.text)) {
                pending.remove(
                    next.id
                )?.end(RelayCallResult.Refused(RelayRefusal.SOCKET_BACKPRESSURE))
            }
        }
    }

    /**
     * The socket generation ended (close, failure, disconnect, cancel); [next] is the connection's new generation, so
     * every correlation issued before is stale. Every call in flight ends [RelayCallResult.Closed].
     */
    @Synchronized
    fun close(next: Long) {
        generation = next
        setState(RelayChannelState.Closed) { endAll(RelayCallResult.Closed) }
    }

    val outstanding: Int @Synchronized get() = pending.size

    private fun fail(reason: RelayFailure) {
        setState(RelayChannelState.Failed(reason)) { endAll(RelayCallResult.Closed) }
    }

    private fun endAll(result: RelayCallResult<Nothing>) {
        val all = pending.values.toList()
        pending.clear()
        waiting.clear()
        for (call in all) call.end(result)
    }

    /** Remove the entire obsolete set before completions: a handler may reenter tick/call/close. */
    @Synchronized
    fun refuseObsolete() {
        val obsolete = pending.filterValues { !it.valid() }
        for (id in obsolete.keys) pending.remove(id)
        waiting.removeAll { it.id in obsolete }
        for (call in obsolete.values) call.end(RelayCallResult.Refused(RelayRefusal.SCOPE_REPLACED))
    }

    private fun setState(next: RelayChannelState, complete: () -> Unit = {}): Boolean {
        val revision = ++transition
        val socket = generation
        state = next
        complete()
        if (transition != revision || generation != socket) return false
        refuseObsolete()
        if (transition != revision || generation != socket) return false
        guarded { listener.relayState(next, socket) }
        // A listener may itself close/replace: welcome must not continue into an obsolete info request.
        return transition == revision && generation == socket
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            handlerFailures++
        }
    }

    private fun remember(id: String) {
        used.addLast(id)
        usedSet.add(id)
        while (used.size > MAX_REMEMBERED) usedSet.remove(used.removeFirst())
    }

    private fun takeToken(nowMs: Long): Boolean {
        if (tokensAtMs == 0L) tokensAtMs = nowMs
        val elapsed = (nowMs - tokensAtMs).coerceAtLeast(0)
        tokens = minOf(RATE_CAPACITY, tokens + elapsed * RATE_PER_MS)
        tokensAtMs = maxOf(tokensAtMs, nowMs)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private fun generationOf(correlation: CorrelationId): Long? =
        correlation.value.substringAfter('r', "").substringBefore('-').toLongOrNull(RADIX)

    companion object {
        const val MAX_OUTSTANDING = 4
        const val INFO_TIMEOUT_MS = 10_000L
        const val MAX_REMEMBERED = 256

        /** Pacing below the server's own bucket (20 tokens, 10 per second): a burst of 15, then 9 requests a second. */
        const val RATE_CAPACITY = 15.0
        const val RATE_PER_MS = 9.0 / 1000.0
        private const val RADIX = 36
    }
}
