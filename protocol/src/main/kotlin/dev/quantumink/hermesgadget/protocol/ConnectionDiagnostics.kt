package dev.quantumink.hermesgadget.protocol

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class DiagnosticTransport { DIRECT, PHONE_RELAY, UNKNOWN }
enum class ConnectionStage {
    OFFLINE,
    CONNECTING,
    SOCKET_OPEN,
    AUTHENTICATING,
    PAIRING,
    READY,
    RETRYING,
    FAILED
}
enum class RequestStage {
    IDLE,
    RECORDING,
    UPLOADING,
    WAITING,
    TRANSCRIBED,
    RECEIVING,
    COMPLETE,
    CANCELLED,
    FAILED
}
enum class FailureCategory {
    NONE,
    TLS,
    ADDRESS_POLICY,
    REACHABILITY,
    AUTH,
    PROTOCOL,
    TIMEOUT,
    OVERLOAD,
    DISCONNECTED
}
enum class DiagnosticObservation {
    TRANSITION,
    LOCAL_SUBMISSION,
    PROGRESS,
    PROMPT,
    AUDIO_START,
    AUDIO_END
}
enum class DiagnosticAudioStage { IDLE, RECEIVING, COMPLETE, INTERRUPTED }

data class DiagnosticEvent(
    val elapsedMillis: Long,
    val connectionStage: ConnectionStage,
    val requestStage: RequestStage,
    val failureCategory: FailureCategory,
    val observation: DiagnosticObservation
)

/** Typed values only. Audio describes received protocol frames, not native playback. */
class ConnectionDiagnosticsSnapshot internal constructor(
    val transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN,
    val connectionStage: ConnectionStage = ConnectionStage.OFFLINE,
    val requestStage: RequestStage = RequestStage.IDLE,
    val failureCategory: FailureCategory = FailureCategory.NONE,
    elapsedMillis: Long = 0,
    connectionElapsedMillis: Long = 0,
    lastProgressAgeMillis: Long = 0,
    val stalled: Boolean = false,
    val audioStage: DiagnosticAudioStage = DiagnosticAudioStage.IDLE,
    events: List<DiagnosticEvent> = emptyList(),
    omittedEvents: Int = 0
) {
    val elapsedMillis: Long = elapsedMillis.coerceIn(0, ConnectionDiagnostics.MAX_MILLIS)
    val connectionElapsedMillis: Long = connectionElapsedMillis.coerceIn(
        0,
        ConnectionDiagnostics.MAX_MILLIS
    )
    val lastProgressAgeMillis: Long = lastProgressAgeMillis.coerceIn(
        0,
        ConnectionDiagnostics.MAX_MILLIS
    )
    val events: List<DiagnosticEvent> = Collections.unmodifiableList(
        events.takeLast(ConnectionDiagnostics.MAX_EVENTS).map {
            it.copy(elapsedMillis = it.elapsedMillis.coerceIn(0, ConnectionDiagnostics.MAX_MILLIS))
        }
    )
    val omittedEvents: Int = (omittedEvents.toLong() + events.size - this.events.size)
        .coerceIn(0, 1000000000).toInt()

    override fun equals(other: Any?): Boolean = other is ConnectionDiagnosticsSnapshot &&
        transport == other.transport && connectionStage == other.connectionStage &&
        requestStage == other.requestStage && failureCategory == other.failureCategory &&
        elapsedMillis == other.elapsedMillis &&
        connectionElapsedMillis == other.connectionElapsedMillis &&
        lastProgressAgeMillis == other.lastProgressAgeMillis && stalled == other.stalled &&
        audioStage == other.audioStage && events == other.events &&
        omittedEvents == other.omittedEvents

    override fun hashCode(): Int = listOf(
        transport, connectionStage, requestStage, failureCategory, elapsedMillis,
        connectionElapsedMillis, lastProgressAgeMillis, stalled, audioStage, events, omittedEvents
    ).hashCode()

    override fun toString(): String =
        "ConnectionDiagnosticsSnapshot(connection=$connectionStage, request=$requestStage)"
}

/** Connection-local, ordered telemetry. There are deliberately no String or map inputs. */
class ConnectionDiagnostics(
    private var transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN
) {
    companion object {
        const val MAX_EVENTS = 96
        const val MAX_MILLIS = 604800000L
        const val STALL_MILLIS = 30000L
        private const val MAX_COUNT = 1000000000
    }

    private var connection = ConnectionStage.OFFLINE
    private var request = RequestStage.IDLE
    private var failure = FailureCategory.NONE
    private var audio = DiagnosticAudioStage.IDLE
    private var origin: Long? = null
    private var current = Long.MIN_VALUE
    private var requestAt: Long? = null
    private var requestEndedAt: Long? = null
    val requestActive: Boolean get() = active
    private var progressAt = 0L
    private var active = false
    private var omitted = 0
    private val events = ArrayDeque<DiagnosticEvent>()

    fun reset(route: DiagnosticTransport, now: Long) {
        transport = route
        connection = ConnectionStage.OFFLINE
        request = RequestStage.IDLE
        failure = FailureCategory.NONE
        audio = DiagnosticAudioStage.IDLE
        origin = now
        current = now
        requestAt = null
        requestEndedAt = null
        progressAt = now
        active = false
        omitted = 0
        events.clear()
        connection(ConnectionStage.CONNECTING, now)
    }

    fun connection(
        stage: ConnectionStage,
        now: Long,
        category: FailureCategory = FailureCategory.NONE
    ) {
        val forward = advance(now)
        if (stage == connection && category == failure) return
        connection = stage
        failure = category
        if (stage in
            setOf(ConnectionStage.OFFLINE, ConnectionStage.RETRYING, ConnectionStage.FAILED)
        ) {
            if (active) {
                request = RequestStage.FAILED
                requestEndedAt = current
                active = false
            }
            audio = DiagnosticAudioStage.IDLE
        }
        event(DiagnosticObservation.TRANSITION, forward)
    }

    fun startRequest(
        stage: RequestStage,
        now: Long,
        observation: DiagnosticObservation = if (stage ==
            RequestStage.WAITING
        ) {
            DiagnosticObservation.LOCAL_SUBMISSION
        } else {
            DiagnosticObservation.TRANSITION
        }
    ) {
        val forward = advance(now)
        requestAt = current
        requestEndedAt = null
        progressAt = current
        active = true
        failure = FailureCategory.NONE
        audio = DiagnosticAudioStage.IDLE
        request = stage
        // This observes local submission only; stock frames do not prove durable core admission.
        event(observation, forward)
    }

    fun request(stage: RequestStage, now: Long) {
        val forward = advance(now)
        if (!active) return
        progressAt = current
        if (request == stage) return
        request = stage
        if (stage in setOf(RequestStage.COMPLETE, RequestStage.CANCELLED, RequestStage.FAILED)) {
            active = false
            requestEndedAt = current
        }
        event(DiagnosticObservation.TRANSITION, forward)
    }

    fun progress(now: Long, observation: DiagnosticObservation = DiagnosticObservation.PROGRESS) {
        val forward = advance(now)
        if (!active) return
        progressAt = current
        event(observation, forward)
    }

    fun audio(receiving: Boolean, now: Long, interrupted: Boolean = false) {
        val forward = advance(now)
        if (!receiving && audio != DiagnosticAudioStage.RECEIVING) return
        val stage = if (receiving) {
            DiagnosticAudioStage.RECEIVING
        } else if (interrupted) {
            DiagnosticAudioStage.INTERRUPTED
        } else {
            DiagnosticAudioStage.COMPLETE
        }
        if (audio == stage || (receiving && !active)) return
        audio = stage
        if (active) progressAt = current
        event(
            if (receiving) DiagnosticObservation.AUDIO_START else DiagnosticObservation.AUDIO_END,
            forward
        )
    }

    fun snapshot(now: Long): ConnectionDiagnosticsSnapshot {
        advance(now)
        val age = if (active) duration(current, progressAt) else 0
        return ConnectionDiagnosticsSnapshot(
            transport, connection, request, failure,
            requestAt?.let { duration(requestEndedAt ?: current, it) } ?: 0,
            origin?.let { duration(current, it) } ?: 0, age,
            active && request in setOf(RequestStage.WAITING, RequestStage.TRANSCRIBED) &&
                age >= STALL_MILLIS,
            audio, events.toList(), omitted
        )
    }

    private fun advance(now: Long): Boolean {
        if (now < current) return false
        current = now
        return true
    }

    private fun event(observation: DiagnosticObservation, forward: Boolean) {
        if (!forward) return
        val next =
            DiagnosticEvent(
                origin?.let {
                    duration(current, it)
                } ?: 0,
                connection,
                request,
                failure,
                observation
            )
        val last = events.lastOrNull()
        if (last != null && last.connectionStage == next.connectionStage &&
            last.requestStage == next.requestStage &&
            last.failureCategory == next.failureCategory &&
            last.observation == next.observation
        ) {
            return
        }
        if (events.size == MAX_EVENTS) {
            events.removeFirst()
            omitted = (omitted + 1).coerceAtMost(MAX_COUNT)
        }
        events.addLast(next)
    }

    private fun duration(end: Long, start: Long): Long {
        if (end <= start) return 0
        val difference = end - start
        return if (difference < 0) MAX_MILLIS else difference.coerceAtMost(MAX_MILLIS)
    }
}

/** Explicit share/export encoder; no raw connection or conversation state is accepted. */
object ConnectionDiagnosticsReport {
    const val SCHEMA = 1
    const val MAX_BYTES = 16 * 1024

    fun encode(snapshot: ConnectionDiagnosticsSnapshot): String {
        var selected = snapshot.events.takeLast(ConnectionDiagnostics.MAX_EVENTS)
        while (true) {
            val report = buildJsonObject {
                put("schema", SCHEMA)
                put("transport", snapshot.transport.name)
                put("connectionStage", snapshot.connectionStage.name)
                put("requestStage", snapshot.requestStage.name)
                put("failureCategory", snapshot.failureCategory.name)
                put("elapsedMillis", bounded(snapshot.elapsedMillis))
                put("connectionElapsedMillis", bounded(snapshot.connectionElapsedMillis))
                put("lastProgressAgeMillis", bounded(snapshot.lastProgressAgeMillis))
                put("stalled", snapshot.stalled)
                put("audioStage", snapshot.audioStage.name)
                put(
                    "omittedEvents",
                    (
                        snapshot.omittedEvents.toLong() + snapshot.events.size -
                            selected.size
                        ).coerceIn(0, 1000000000)
                )
                put(
                    "events",
                    JsonArray(
                        selected.map { event ->
                            buildJsonObject {
                                put("elapsedMillis", bounded(event.elapsedMillis))
                                put("connectionStage", event.connectionStage.name)
                                put("requestStage", event.requestStage.name)
                                put("failureCategory", event.failureCategory.name)
                                put("observation", event.observation.name)
                            }
                        }
                    )
                )
            }.toString()
            if (report.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return report
            selected = selected.drop(1)
        }
    }

    private fun bounded(value: Long): Long = value.coerceIn(0, ConnectionDiagnostics.MAX_MILLIS)
}
