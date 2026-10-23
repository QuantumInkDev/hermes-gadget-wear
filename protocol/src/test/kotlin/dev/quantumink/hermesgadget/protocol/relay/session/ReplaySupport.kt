package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayListener
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One captured phone-controller session: the exact requests the registered adapter was driven with and the exact
 * `relay.response` frames it produced (see `relay-task3-v1/README.md`). A REPLAY: the server side is the recording; the
 * client side under test is the production controller over the production [DirectConnection].
 */
internal class RecordedSession(private val root: JsonObject) {
    class Step(val connection: Int, val request: JsonObject, val response: JsonObject, val note: String) {
        val method: String get() = request["method"]!!.jsonPrimitive.content
        val params: JsonObject get() = request["params"]!!.jsonObject
        val ok: Boolean get() = "result" in response
        override fun toString(): String = "Step($method, $note)"
    }

    val steps: List<Step> = root["steps"]!!.jsonArray.map {
        val o = it.jsonObject
        Step(o["connection"]!!.jsonPrimitive.content.toInt(), o["request"]!!.jsonObject, o["response"]!!.jsonObject, o["note"]!!.jsonPrimitive.content)
    }
    val mintPlan: List<String> = root["mint_plan"]!!.jsonArray.map { it.jsonPrimitive.content }
    val serverTimeMs: Long = root["server_time_ms"]!!.jsonPrimitive.content.toLong()
    val conversationId: String = root["conversation_id"]!!.jsonPrimitive.content
    val welcome: JsonObject = root["welcomes"]!!.jsonArray.first().jsonObject["relay"]!!.jsonObject
    val scenario: String = root["scenario"]!!.jsonPrimitive.content

    companion object {
        fun load(name: String): RecordedSession = RecordedSession(
            Json.parseToJsonElement(
                RecordedSession::class.java.getResourceAsStream("/relay-task3-v1/$name")!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            ).jsonObject
        )

        /** The 80 random bits that make [RequestIds.mint] produce exactly [id] (its timestamp must be the clock estimate). */
        fun entropyFor(id: String): ByteArray {
            val hex = id.replace("-", "")
            val randomA = hex.substring(13, 16).toInt(16)
            val random62 = java.lang.Long.parseUnsignedLong(hex.substring(16), 16) and ((1L shl 62) - 1)
            val bytes = ByteArray(10)
            bytes[0] = (randomA shr 8).toByte()
            bytes[1] = randomA.toByte()
            for (index in 0 until 8) bytes[2 + index] = (random62 shr (8 * (7 - index))).toByte()
            return bytes
        }
    }
}

/**
 * The recorded server behind a [RelaySocketFixture]: every request the client writes must equal the next recorded request
 * (everything but the correlation id) and is answered with the recorded response frame, re-addressed to the client's
 * correlation id (the only member that changes). A request that differs is a mismatch (the test fails); one beyond the end of
 * the recording is counted and unanswered; a request can be swallowed (lost in transit) and a response withheld (lost) or
 * delivered late.
 */
internal class RecordedServer(val session: RecordedSession, val fixture: RelaySocketFixture, startAt: Int = 0) {
    class Mismatch(val index: Int, val expected: JsonObject?, val actual: JsonObject) {
        override fun toString(): String = "Mismatch(#$index expected=${expected?.get("method")} ${expected?.get("params")} actual=${actual["method"]} ${actual["params"]})"
    }

    val mismatches = CopyOnWriteArrayList<Mismatch>()
    val consumed = CopyOnWriteArrayList<RecordedSession.Step>()
    val extra = CopyOnWriteArrayList<JsonObject>()
    val seen = CopyOnWriteArrayList<JsonObject>()
    private val held = HashMap<Int, Pair<RelaySocketFixture.Peer, JsonObject>>()
    private val holdSteps = HashSet<Int>()
    private val swallow = CopyOnWriteArrayList<String>()
    private val holdMethods = CopyOnWriteArrayList<String>()
    private var next = startAt
    val delivered = CopyOnWriteArrayList<JsonObject>()

    val consumedCount: Int get() = consumed.size

    /** The next request whose method is [method] is lost in transit: unanswered, and it consumes no recorded step. */
    fun swallowNext(method: String) {
        swallow.add(method)
    }

    /** The response to recorded step [index] is withheld (a lost response) until [release]. */
    fun holdResponse(index: Int) {
        holdSteps.add(index)
    }

    /** The response to the next recorded step with this [method] is withheld (and answered by [release] later). */
    fun holdNextResponse(method: String) {
        holdMethods.add(method)
    }

    fun release(index: Int, onPeer: RelaySocketFixture.Peer? = null, correlation: String? = null) {
        val (peer, frame) = held.remove(index) ?: error("Step $index is not held")
        val target = onPeer ?: peer
        target.deliver(if (correlation == null) frame else JsonObject(frame + ("id" to JsonPrimitive(correlation))))
    }

    fun heldIndexes(): Set<Int> = held.keys.toSet()

    /** Call from the test thread to feed requests that arrived since the last call. Returns how many were handled. */
    @Synchronized fun pump(): Int {
        var handled = 0
        for (peer in fixture.peers) {
            while (true) {
                val request = peer.relayRequests.poll() ?: break
                handle(peer, request)
                handled++
            }
        }
        return handled
    }

    private fun handle(peer: RelaySocketFixture.Peer, request: JsonObject) {
        seen.add(request)
        val method = request["method"]!!.jsonPrimitive.content
        val swallowIndex = swallow.indexOf(method)
        if (swallowIndex >= 0) {
            swallow.removeAt(swallowIndex)
            return
        }
        if (next >= session.steps.size) {
            extra.add(request)
            return
        }
        val step = session.steps[next]
        val same = step.request["method"] == request["method"] && step.request["params"] == request["params"]
        if (!same) {
            mismatches.add(Mismatch(next, step.request, request))
            return
        }
        val index = next++
        consumed.add(step)
        val frame = JsonObject(step.response + ("id" to request["id"]!!))
        if (index in holdSteps || method in holdMethods) {
            holdMethods.remove(method)
            held[index] = peer to frame
            return
        }
        delivered.add(frame)
        peer.deliver(RelayVectors.asciiJson(frame))
    }
}

/** The production controller over the production connection, driven by a [RecordedServer]. */
internal class ReplayHarness(
    scenario: String,
    val store: MemoryStore = MemoryStore(),
    identity: EnrollmentIdentity? = null,
    val timing: RelayTiming = RelayTiming(),
    startAt: Int = 0,
    mintStart: Int = 0
) : AutoCloseable {
    val session = RecordedSession.load(scenario)
    val fixture = RelaySocketFixture(relayMember = session.welcome)
    val server = RecordedServer(session, fixture, startAt)
    val scheduler = ManualScheduler()
    private val offset = AtomicLong(0)

    /** The connection's monotonic milliseconds: real time plus what a test moved on (so pacing refills as it would live). */
    private fun connectionNow(): Long = System.nanoTime() / 1_000_000L + offset.get()
    val nanos = AtomicLong(5_000_000_000L)
    val identity = identity ?: EnrollmentIdentity("wss://relay.example.invalid/gadget", "phone:hg-0011223344556677")
    private var mint = mintStart
    val states = CopyOnWriteArrayList<RelayUiState>()
    val events = ArrayList<String>()

    val controller = RelayController(
        store,
        scheduler,
        nanos::get,
        RequestIdEntropy { bytes ->
            val id = session.mintPlan[mint++]
            RecordedSession.entropyFor(id).copyInto(bytes)
        },
        timing
    ) { states.add(it) }

    var connection: DirectConnection = newConnection()
        private set

    private fun newConnection(): DirectConnection = DirectConnection(
        Endpoint.parse(identity.endpointUrl),
        DeviceIdentity.generate(),
        object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) = Unit
            override fun effect(effect: ConversationEffect, generation: Long) = Unit
        },
        now = ::connectionNow,
        relay = RelayOptIn(
            RelayListener { state: RelayChannelState, generation: Long -> controller.onChannel(state, generation) }
        ) { nanos.get() },
        webSockets = fixture
    )

    val ui: RelayUiState get() = controller.currentState

    /** The socket drops and the SAME connection object dials again: a new socket generation, a new recorded connection. */
    fun reconnect() {
        drop()
        connection.connect()
        settle()
    }

    /** The socket drops (what really loses a response); nothing dials again. */
    fun drop() {
        connection.disconnect()
        settle()
    }

    fun prepareAndEnable() {
        controller.prepare(identity)
        controller.setEnabled(true)
        settle()
    }

    fun connect() {
        controller.attachTransport(connection.relayTransport)
        connection.connect()
        settle()
    }

    /**
     * Runs the controller thread and the recorded server until nothing changes for a while: the connection thread is real,
     * so quiescence is observed (requests, queue, state) rather than assumed.
     */
    fun settle() {
        var stable = 0
        var previous = -1L
        val deadline = System.nanoTime() + 10_000_000_000L
        while (stable < 4 && System.nanoTime() < deadline) {
            scheduler.runAll()
            server.pump()
            scheduler.runAll()
            val fingerprint = fixture.peers.sumOf { it.relayTexts.size.toLong() } * 1_000_003L +
                server.delivered.size * 31L + states.size + scheduler.pendingTimers
            if (fingerprint == previous) stable++ else stable = 0
            previous = fingerprint
            Thread.sleep(15)
        }
        check(System.nanoTime() < deadline) { "No quiescence" }
    }

    /** The connection clock moves (channel timeouts, handshake timers); the controller's virtual time is separate. */
    fun advanceConnection(ms: Long) {
        offset.addAndGet(ms)
        Thread.sleep(250)
        settle()
    }

    /** Controller timers (polling, retries) fall due; nothing else moves. */
    fun advanceController(ms: Long) {
        scheduler.advance(ms)
        settle()
    }

    fun requests(method: String): List<JsonObject> =
        server.seen.filter { it["method"]!!.jsonPrimitive.content == method }

    override fun close() {
        connection.close()
    }
}
