package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors

/** Shared fixtures: real captured Task1 vectors and tiny deterministic seams. Nothing here is server authority. */
internal object StateTestSupport {
    /** `relay.info` as the conversation bridge answers it (carries `server_time_ms`), captured. */
    val clockInfo: Info by lazy {
        Info.decode(
            RelayVectors.forMethod(RelayMethod.INFO).first {
                it.isSuccess && "server_time_ms" in it.result
            }.result
        )
    }

    /** `relay.info` as the O5-only service answers it (no `server_time_ms`), captured. */
    val o5OnlyInfo: Info by lazy {
        Info.decode(
            RelayVectors.forMethod(RelayMethod.INFO).first {
                it.isSuccess && "server_time_ms" !in it.result
            }.result
        )
    }

    /** The captured server time of [clockInfo]. */
    val serverTime: Long get() = clockInfo.serverTimeMs!!

    fun clockAt(monotonicNanos: Long = 1_000_000_000L): ServerClock =
        (ServerClock.fix(clockInfo, monotonicNanos) as ClockFix.Fixed).clock

    fun instant(clock: ServerClock, nanos: Long): ServerInstant =
        (clock.read(nanos) as? InstantRead.At)?.instant
            ?: throw AssertionError("clock refused $nanos")

    /** An instant [afterMs] milliseconds after the captured server time, fixed at anchor nanos 1e9. */
    fun at(afterMs: Long = 0): ServerInstant {
        val clock = clockAt(1_000_000_000L)
        return instant(clock, 1_000_000_000L + afterMs * 1_000_000L)
    }

    /** A bare instant for boundary tests that do not go through a clock. */
    fun raw(serverMs: Long, nanos: Long = 0): ServerInstant = ServerInstant(serverMs, nanos)

    val scope = RelayScope("endpoint-a", clockInfo.profileId)
    val enrollment = EnrollmentScope(scope, "phone-key-1", 0)

    fun attached(index: Int = 0): Binding = Binding.decode(
        RelayVectors.forMethod(RelayMethod.CONVERSATION_ATTACH).filter {
            it.isSuccess
        }[index].result
    )

    fun conversation(binding: Binding): ConversationScope =
        ConversationScope(enrollment, binding.conversationId)

    fun conversationId(text: String): ConversationId = ConversationId.of(text)!!

    fun correlation(text: String): CorrelationId = CorrelationId.of(text)!!

    /** Deterministic bytes that are never all zero. */
    fun entropy(seed: Int): RequestIdEntropy = RequestIdEntropy { bytes ->
        for (index in bytes.indices) bytes[index] = (seed * 31 + index * 7 + 1).toByte()
    }

    class CountingEntropy(private val seed: Int = 1) : RequestIdEntropy {
        var calls = 0
            private set
        private var next = seed

        override fun fill(destination: ByteArray) {
            calls++
            val bytes = entropy(next++)
            bytes.fill(destination)
        }
    }

    /** The aggregate snapshot of the given state; fails the test if a component is over its limit. */
    fun builtSnapshot(
        ledger: TurnLedger,
        histories: List<HistoryAssembly>,
        deliveries: List<EnrollmentDelivery>
    ): RelayStateSnapshot =
        (RelayStateSnapshot.of(ledger, histories, deliveries) as SnapshotBuild.Built).snapshot
}
