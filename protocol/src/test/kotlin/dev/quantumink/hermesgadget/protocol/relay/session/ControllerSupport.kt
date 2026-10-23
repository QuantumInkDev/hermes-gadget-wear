package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.ExecutionState
import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.InfoLimits
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayEvent
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Deterministic single-thread scheduler with virtual time: the controller thread of a test. */
internal class ManualScheduler : RelayScheduler {
    private class Timer(val at: Long, val order: Long, val task: Runnable) : Comparable<Timer> {
        @Volatile var cancelled = false
        override fun compareTo(other: Timer): Int =
            compareValuesBy(this, other, { it.at }, { it.order })
    }

    private val queue = ArrayDeque<Runnable>()
    private val timers = PriorityQueue<Timer>()
    private var order = 0L
    var now = 0L
        private set

    @Synchronized override fun post(task: Runnable) {
        queue.addLast(task)
    }

    @Synchronized override fun postDelayed(delayMs: Long, task: Runnable): Cancelable {
        val timer = Timer(now + delayMs, order++, task)
        timers.add(timer)
        return Cancelable { timer.cancelled = true }
    }

    val pendingTimers: Int @Synchronized get() = timers.count { !it.cancelled }

    @Synchronized private fun next(): Runnable? = if (queue.isEmpty()) null else queue.removeFirst()

    fun runAll() {
        while (true) (next() ?: return).run()
    }

    /** Moves virtual time forward, running every timer that falls due (in order) and everything they post. */
    fun advance(ms: Long) {
        val target = now + ms
        runAll()
        while (true) {
            val due = synchronized(this) { timers.peek()?.takeIf { it.at <= target } } ?: break
            synchronized(this) {
                timers.remove(due)
                now = maxOf(now, due.at)
            }
            if (!due.cancelled) due.task.run()
            runAll()
        }
        synchronized(this) { now = target }
    }
}

/** A real encode/decode round trip around an in-memory byte array; failures and unreadable states are injectable. */
internal class MemoryStore(var log: MutableList<String>? = null) : RelayStore {
    var bytes: ByteArray? = null
    var sequence = 0L
    var initialised = false
    var commits = 0
    var attempts = 0
    var unreadable: StoreProblem? = null
    var failures = ArrayDeque<StoreCommit>()
    var failAll: StoreCommit? = null
    var lastDocument: RelayDocument? = null

    override fun load(): StoreLoad {
        unreadable?.let { return StoreLoad.Unreadable(it) }
        val stored = bytes ?: return if (initialised) {
            StoreLoad.Unreadable(StoreProblem.RECORD_MISSING)
        } else {
            StoreLoad.Empty
        }
        return when (val decoded = SnapshotCodec.decode(stored)) {
            is DecodeResult.Decoded -> StoreLoad.Loaded(decoded.document, sequence)
            is DecodeResult.Rejected -> StoreLoad.Unreadable(
                if (decoded.reason == DecodeRefusal.UNSUPPORTED_VERSION) {
                    StoreProblem.UNSUPPORTED_VERSION
                } else {
                    StoreProblem.CORRUPT
                }
            )
        }
    }

    override fun commit(document: RelayDocument, base: Long): StoreCommit {
        attempts++
        log?.add("commit")
        val injected = failAll ?: failures.removeFirstOrNull()
        if (injected != null) {
            if (landOnFailure && injected is StoreCommit.Failed && injected.uncertain) {
                (SnapshotCodec.encode(document) as? EncodeResult.Encoded)?.let {
                    bytes = it.bytes
                    initialised = true
                    sequence++
                    lastDocument = document
                }
            }
            return injected
        }
        if (base != sequence) return StoreCommit.Stale
        return when (val encoded = SnapshotCodec.encode(document)) {
            is EncodeResult.Refused -> StoreCommit.Refused(encoded.reason, encoded.path)
            is EncodeResult.Encoded -> {
                bytes = encoded.bytes
                initialised = true
                sequence++
                commits++
                lastDocument = document
                StoreCommit.Committed(sequence)
            }
        }
    }

    /** What a failure "after the replace" leaves behind: the new record IS in place although the commit reported failure. */
    var landOnFailure = false

    override fun currentSequence(): Long = if (unreadable != null) -1 else sequence

    /** What a restart would read: the committed document, decoded again from the bytes. */
    fun reread(): RelayDocument = (load() as StoreLoad.Loaded).document
}

/** A transport the test answers by hand; every reply goes through the REAL envelope decode and the call's own decoder. */
internal class ScriptedTransport(val log: MutableList<String>) : RelayTransport {
    class Sent(
        val correlation: CorrelationId,
        val call: RelayCall<*>,
        val deliver: (RelayCallResult<Nothing>) -> Unit,
        val frame: (kotlinx.serialization.json.JsonObject) -> Unit
    ) {
        var answered = false
        val method: String get() = call.method.wire
        val params: JsonObject get() = paramsOf(call)
    }

    override var generation = 1L
    private var counter = 0L
    val sent = ArrayList<Sent>()
    var refreshes = 0
    var refreshHandler: ((RelayCallResult<Info>) -> Unit)? = null

    override fun correlation(): CorrelationId = CorrelationId.of("t${generation}-${++counter}")!!

    override fun <R : Any> call(
        correlation: CorrelationId,
        call: RelayCall<R>,
        timeoutMs: Long,
        handler: (RelayCallResult<R>) -> Unit
    ) {
        log.add("send:" + call.method.wire)
        sent.add(
            Sent(
                correlation,
                call,
                { result -> handler(result) },
                { body ->
                    val text = RelayVectors.asciiJson(
                        JsonObject(mapOf("type" to JsonPrimitive("relay.response"), "v" to JsonPrimitive(1)) + body)
                    )
                    handler(RelayCallResult.Outcome(call.interpret(correlation, RelayEnvelope.decodeFrame(text))))
                }
            )
        )
    }

    override fun refreshInfo(handler: (RelayCallResult<Info>) -> Unit) {
        log.add("send:relay.info")
        refreshes++
        refreshHandler = handler
    }

    fun methods(): List<String> = sent.map { it.method }

    fun open(method: String): List<Sent> = sent.filter { !it.answered && it.method == method }

    fun next(method: String): Sent = open(method).firstOrNull()
        ?: error("No open $method; sent=${methods()}")

    fun reply(call: Sent, result: JsonObject) {
        call.answered = true
        call.frame(buildJsonObject { put("id", call.correlation.value); put("result", result) })
    }

    fun fail(call: Sent, code: String, snapshotRequired: Boolean = false) {
        call.answered = true
        call.frame(
            buildJsonObject {
                put("id", call.correlation.value)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        if (snapshotRequired) put("snapshot_required", true)
                    }
                )
            }
        )
    }

    fun timeout(call: Sent) {
        call.answered = true
        call.deliver(RelayCallResult.TimedOut)
    }

    /** The socket ended: every call in flight ends Closed (what the real router does). */
    fun closeAll() {
        for (call in sent.filter { !it.answered }) {
            call.answered = true
            call.deliver(RelayCallResult.Closed)
        }
    }

    fun clear() {
        sent.clear()
    }

    companion object {
        fun paramsOf(call: RelayCall<*>): JsonObject {
            val frame = RelayEnvelope.encodeRequest(CorrelationId.of("p")!!, call)
            val text = (frame as dev.quantumink.hermesgadget.protocol.relay.RelayEncoded.Frame).text
            return kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["params"]!!.jsonObject
        }
    }
}

/** DTO builders over the captured conventions (profile, server time) plus the ids a scripted session needs. */
internal object Dto {
    val profile: ProfileId = ProfileId.of("9e8af651-47eb-411f-b28c-ed292bbfd305")!!
    val otherProfile: ProfileId = ProfileId.of("0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d")!!
    const val SERVER_TIME = 1_792_300_000_000L
    const val EXPIRES = 1_794_892_000_000L

    val conversation: ConversationId = ConversationId.of("3ff4ef74-af70-4efc-a3d4-e797b98a4dea")!!
    val otherConversation: ConversationId = ConversationId.of("150a3e5a-3f41-48b5-85b6-b63879fa6274")!!
    val admission: AdmissionId = AdmissionId.of("b51c880f-2574-4650-99fb-cd685725b5b3")!!

    val ALL_METHODS = listOf(
        "relay.info",
        "relay.conversation.create",
        "relay.conversation.attach",
        "relay.conversation.detach",
        "relay.turn.submit",
        "relay.turn.receipt",
        "relay.history.page",
        "relay.events.read",
        "relay.conversations.list",
        "relay.tasks.list",
        "relay.task.get"
    )

    fun info(
        methods: List<String> = ALL_METHODS,
        grant: Long = 2,
        profile: ProfileId = Dto.profile,
        serverTimeMs: Long = SERVER_TIME,
        expiresAtMs: Long = EXPIRES
    ): Info = Info(
        profile,
        grant,
        expiresAtMs,
        serverTimeMs,
        methods,
        InfoLimits(false, 50, 16_384, 8_192, 6, 65_536, 32_768, null)
    )

    fun binding(
        n: Int,
        conversation: ConversationId = Dto.conversation,
        epoch: Long = 1,
        lastSequence: Long = 0,
        replay: String = "replay-1"
    ): Binding = Binding(
        BindingId.of("00000000-0000-4000-8000-%012d".format(n))!!,
        conversation,
        epoch,
        0,
        1,
        replay,
        lastSequence,
        ExecutionState.IDLE
    )

    fun summary(conversation: ConversationId = Dto.conversation, title: String? = "Planning") =
        ConversationSummary(conversation, title, 1, false, 1_792_300_000_000L, true)

    fun page(vararg items: ConversationSummary, next: String? = null) =
        ConversationPage(items.toList(), next, emptyList(), null)

    fun receipt(
        requestId: RequestId,
        status: ReceiptStatus,
        outcome: String? = null,
        generation: Long? = null,
        conversation: ConversationId = Dto.conversation,
        epoch: Long = 1,
        admission: AdmissionId = Dto.admission
    ) = Receipt(requestId, admission, conversation, status, outcome, epoch, generation)

    fun message(n: Int, role: HistoryRole, text: String, at: Long = 1_792_300_000_000L + n) =
        HistoryMessage(
            MessageId.of("00000000-0000-4000-9000-%012d".format(n))!!,
            role,
            text,
            at,
            0,
            1
        )

    fun history(vararg messages: HistoryMessage, next: String? = null) =
        HistoryPage(messages.toList(), next)

    fun event(
        n: Long,
        binding: Binding,
        payload: EventPayload,
        replay: String = binding.replayEpoch
    ) = RelayEvent(
        EventId.of("00000000-0000-4000-a000-%012d".format(n))!!,
        binding.conversationId,
        binding.bindingId,
        n,
        replay,
        payload
    )

    fun events(
        binding: Binding,
        vararg events: RelayEvent,
        snapshotRequired: Boolean = false
    ) = EventsPage(
        events.toList(),
        binding.replayEpoch,
        events.lastOrNull()?.sequence ?: binding.lastSequence,
        snapshotRequired
    )

    fun messageEvent(
        n: Int,
        role: HistoryRole,
        text: String,
        createdAt: Long
    ): EventPayload.Message = EventPayload.Message(
        MessageId.of("00000000-0000-4000-9000-%012d".format(n))!!,
        role,
        text,
        createdAt
    )

    val scriptedEntropy = RequestIdEntropy { bytes ->
        for (index in bytes.indices) bytes[index] = (index * 13 + 5).toByte()
    }
}

/** Everything a controller test needs: one scheduler, one store, one scripted transport, one shared event log. */
internal class ControllerFixture(
    val identity: EnrollmentIdentity = EnrollmentIdentity(
        "wss://relay.example.invalid/gadget",
        "phone:hg-0011223344556677"
    ),
    val store: MemoryStore = MemoryStore(),
    val timing: RelayTiming = RelayTiming()
) {
    val log = ArrayList<String>().also { store.log = it }
    val scheduler = ManualScheduler()
    val transport = ScriptedTransport(log)
    val nanos = AtomicLong(5_000_000_000L)
    val states = ArrayList<RelayUiState>()
    private val entropySeed = AtomicLong(1)
    private var infoSerial = 0L
    private var socket = 0L

    val controller = RelayController(
        store,
        scheduler,
        nanos::get,
        { bytes -> Dto.scriptedEntropy.fill(bytes); bytes[1] = (entropySeed.getAndIncrement()).toByte() },
        timing
    ) { states.add(it) }

    val ui: RelayUiState get() = controller.currentState

    fun run() = scheduler.runAll()

    fun advanceTime(ms: Long) {
        nanos.addAndGet(ms * 1_000_000L)
        scheduler.advance(ms)
    }

    fun prepare() {
        controller.prepare(identity)
        run()
    }

    fun enable() {
        prepare()
        controller.setEnabled(true)
        run()
    }

    /** A new connection that negotiated Relay and answered `relay.info`. */
    fun connect(info: Info = Dto.info()) {
        socket++
        transport.generation = socket
        controller.attachTransport(transport)
        controller.onChannel(RelayChannelState.AwaitingWelcome, socket)
        controller.onChannel(RelayChannelState.LoadingInfo, socket)
        run()
        ready(info)
    }

    fun ready(info: Info) {
        val fixed = ServerClock.fix(info, nanos.get()) as ClockFix.Fixed
        controller.onChannel(RelayChannelState.Ready(info, fixed.clock, ++infoSerial), socket)
        run()
    }

    /** The socket ended: calls in flight end Closed, then the channel reports Closed. */
    fun disconnect() {
        transport.closeAll()
        controller.onChannel(RelayChannelState.Closed, socket)
        run()
    }

    fun answer(method: String, result: JsonObject): ScriptedTransport.Sent {
        val call = transport.next(method)
        transport.reply(call, result)
        run()
        return call
    }

    fun answerError(method: String, code: String, snapshotRequired: Boolean = false) {
        transport.fail(transport.next(method), code, snapshotRequired)
        run()
    }

    /** Answers the list, then the attach/history/tasks of a conversation selection, in the order they are asked. */
    fun listAnd(vararg summaries: ConversationSummary) {
        answer("relay.conversations.list", Dto.page(*summaries).toWire())
    }

    fun attachTo(binding: Binding, history: HistoryPage = Dto.history(), withTasks: Boolean = true) {
        answer("relay.conversation.attach", binding.toWire())
        // Receipt lookups of unresolved turns come first; the caller answers those before calling this when present.
        answer("relay.history.page", history.toWire())
        if (withTasks) {
            answer(
                "relay.tasks.list",
                dev.quantumink.hermesgadget.protocol.relay.TaskPage(emptyList(), null).toWire()
            )
        }
    }

    fun frames(method: String): List<ScriptedTransport.Sent> = transport.sent.filter { it.method == method }
}
