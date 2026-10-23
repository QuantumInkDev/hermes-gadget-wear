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

/** The production controller over the production connection, driven by a [RecordedServer]. */
internal class OwnReplayHarness(
    scenario: String,
    val store: MemoryStore = MemoryStore(),
    identity: EnrollmentIdentity? = null,
    val timing: RelayTiming = RelayTiming(),
    startAt: Int = 0,
    mintStart: Int = 0
) : AutoCloseable {
    val session = RecordedSession(
        Json.parseToJsonElement(
            OwnReplayHarness::class.java.getResourceAsStream(
                "/relay-own-native-v1/$scenario.json"
            )!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        ).jsonObject
    )
    val fixture = RelaySocketFixture(relayMember = session.welcome)
    val server = RecordedServer(session, fixture, startAt)
    val scheduler = ManualScheduler()
    private val offset = AtomicLong(0)

    /** The connection's monotonic milliseconds: real time plus what a test moved on (so pacing refills as it would live). */
    private fun connectionNow(): Long = System.nanoTime() / 1_000_000L + offset.get()
    val nanos = AtomicLong(5_000_000_000L)
    val identity =
        identity
            ?: EnrollmentIdentity("wss://relay.example.invalid/gadget", "phone:hg-0011223344556677")
    private var mint = mintStart
    val states = CopyOnWriteArrayList<RelayUiState>()
    val events = ArrayList<String>()
    var durableSubmits = 0
        private set

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
            RelayListener { state: RelayChannelState, generation: Long ->
                controller.onChannel(state, generation)
            }
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
            for (peer in fixture.peers) {
                for (request in peer.relayRequests) {
                    if (request["method"]!!.jsonPrimitive.content == "relay.turn.submit") {
                        val params = request["params"]!!.jsonObject
                        val turn = store.reread().snapshot.ledger.turns.single()
                        check(turn.sent && turn.text == params["text"]!!.jsonPrimitive.content)
                        check(
                            turn.expiresAtMs ==
                                params["expires_at_ms"]!!.jsonPrimitive.content.toLong()
                        )
                        durableSubmits++
                    }
                }
            }
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
