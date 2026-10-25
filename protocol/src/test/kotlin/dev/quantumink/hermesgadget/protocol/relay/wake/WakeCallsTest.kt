package dev.quantumink.hermesgadget.protocol.relay.wake

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayEncoded
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayNegotiation
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayProblem
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.MintResult
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The two wake calls against the exact server shapes of `relay_wake.WakeService` and `tests/test_relay_wake.py`. */
class WakeCallsTest {
    private val target = WakeTargetId.of("42f07b48-4fba-457e-9a9e-31ba5eff5dfa")!!
    private val serverTime = 1_792_300_000_000L
    private val entropy = RequestIdEntropy { bytes -> bytes.fill(0x5a) }

    /** The O5 `relay.info` of a wake-enabled server: the stock O5 result plus the additive clock and wake methods. */
    private val o5WakeInfo = obj(
        "profile_id" to JsonPrimitive("9e8af651-47eb-411f-b28c-ed292bbfd305"),
        "grant_generation" to JsonPrimitive(1),
        "expires_at_ms" to JsonPrimitive(serverTime + 3_600_000),
        "server_time_ms" to JsonPrimitive(serverTime),
        "methods" to JsonArray(
            listOf(
                "relay.info",
                "relay.outbox.read",
                "relay.outbox.ack",
                "wake.targets",
                "wake.request"
            )
                .map(::JsonPrimitive)
        ),
        "limits" to obj(
            "outbox_cursor" to JsonPrimitive(false),
            "page_items" to JsonPrimitive(50),
            "text_bytes" to JsonPrimitive(16384)
        )
    )

    private fun obj(vararg members: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*members))

    private fun clockAt(nanos: Long): ServerClock =
        (ServerClock.fix(Info.decode(o5WakeInfo), nanos) as ClockFix.Fixed).clock

    private fun instant(clock: ServerClock, nanos: Long): ServerInstant =
        (clock.read(nanos) as InstantRead.At).instant

    private fun mint(at: ServerInstant): RequestId =
        (RequestIds.mint(at, entropy) as MintResult.Minted).id

    private fun params(call: RelayCall<*>): JsonObject {
        val text = (
            RelayEnvelope.encodeRequest(
                CorrelationId.of("w1")!!,
                call
            ) as RelayEncoded.Frame
            ).text
        val frame = Json.parseToJsonElement(text).jsonObject
        assertEquals(call.method.wire, frame["method"]!!.jsonPrimitive.content)
        return frame["params"]!!.jsonObject
    }

    private fun <R : Any> reply(
        call: RelayCall<R>,
        body: Pair<String, JsonElement>
    ): RelayOutcome<R> {
        val frame = obj(
            "type" to JsonPrimitive("relay.response"),
            "v" to JsonPrimitive(1),
            "id" to JsonPrimitive("w1"),
            body
        )
        return call.interpret(
            CorrelationId.of("w1")!!,
            RelayEnvelope.decodeFrame(RelayVectors.asciiJson(frame))
        )
    }

    private fun row(
        label: String = "Synthetic box",
        id: String = target.value,
        backend: String = "wol",
        available: Boolean = true,
        reason: String? = null
    ): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "target_id" to JsonPrimitive(id),
            "label" to JsonPrimitive(label),
            "backend" to JsonPrimitive(backend),
            "available" to JsonPrimitive(available)
        )
        reason?.let { members["reason"] = JsonPrimitive(it) }
        return JsonObject(members)
    }

    private fun targets(vararg rows: JsonObject) =
        "result" to obj("targets" to JsonArray(rows.toList()))

    @Test fun targetsSendsAnEmptyObjectAndDecodesTheServerShape() {
        val call = WakeCalls.targets()
        assertEquals(RelayMethod.WAKE_TARGETS, call.method)
        assertEquals(JsonObject(emptyMap()), params(call))
        val ok =
            reply(
                call,
                targets(
                    row(),
                    row(
                        id = "4bb9b3a4-3f0a-4a51-9b2e-a1d04b0b9e1c",
                        backend = "vm_start_resume",
                        available = false,
                        reason = "unsupported"
                    )
                )
            )
        val decoded = (ok as RelayOutcome.Success).value.targets
        assertEquals(2, decoded.size)
        assertEquals("Synthetic box", decoded[0].label)
        assertEquals(WakeBackend.WOL, decoded[0].backend)
        assertTrue(decoded[0].available)
        assertNull(decoded[0].reason)
        assertEquals(WakeUnavailableReason.UNSUPPORTED, decoded[1].reason)
        assertEquals(WakeBackend.VM_START_RESUME, decoded[1].backend)
        assertEquals(
            obj("targets" to JsonArray(listOf(row()))),
            WakeTargets(listOf(decoded[0])).toWire()
        )
        assertTrue((reply(call, targets()) as RelayOutcome.Success).value.targets.isEmpty())
        assertTrue(RelayCalls.fromWire(RelayMethod.WAKE_TARGETS, JsonObject(emptyMap())) != null)
        assertNull(RelayCalls.fromWire(RelayMethod.WAKE_TARGETS, obj("x" to JsonPrimitive(1))))
    }

    @Test fun everyDeviationOfATargetRowIsInvalidNeverASuccess() {
        val call = WakeCalls.targets()
        val other = "4bb9b3a4-3f0a-4a51-9b2e-a1d04b0b9e1c"
        val bad = listOf(
            "unknown member" to
                targets(JsonObject(row() + ("mac" to JsonPrimitive("02:00:00:00:00:01")))),
            "missing label" to targets(JsonObject(row() - "label")),
            "unavailable without reason" to targets(row(available = false)),
            "reason while available" to targets(row(reason = "unavailable")),
            "unknown reason" to targets(row(available = false, reason = "asleep")),
            "unknown backend" to targets(row(backend = "ipmi")),
            "uppercase id" to targets(row(id = target.value.uppercase())),
            "duplicate id" to targets(row(), row()),
            "empty label" to targets(row(label = "")),
            "81 scalars" to targets(row(label = "a".repeat(81))),
            "control character" to targets(row(label = "box\u0007")),
            "format character" to targets(row(label = "box​")),
            "17 targets" to targets(
                *(0 until 17).map {
                    row(id = "00000000-0000-4000-8000-%012d".format(it))
                }.toTypedArray()
            ),
            "extra result member" to
                ("result" to obj("targets" to JsonArray(emptyList()), "more" to JsonPrimitive(1))),
            "wrong type" to ("result" to obj("targets" to JsonPrimitive("none")))
        )
        for ((name, body) in bad) {
            val outcome = reply(call, body)
            assertTrue("$name: $outcome", outcome is RelayOutcome.Invalid)
            assertEquals(
                name,
                RelayProblem.INVALID_RESULT,
                (outcome as RelayOutcome.Invalid).problem
            )
        }
        // A bounded mixed set stays valid: one unavailable row (non-macOS sender) and a distinct id.
        assertTrue(
            reply(
                call,
                targets(row(), row(id = other, available = false, reason = "unavailable"))
            ) is RelayOutcome.Success
        )
    }

    @Test fun sixteenTargetsOfEightyAstralScalarsFitTheWakeBoundButNotTheDtoBound() {
        val label = "🚀".repeat(80)
        assertTrue(WakeCalls.validLabel(label))
        assertTrue(!WakeCalls.validLabel(label + "a"))
        val rows = (0 until 16).map {
            row(
                label = label,
                id = "00000000-0000-4000-8000-%012d".format(it),
                backend = "vm_start_resume",
                available = false,
                reason = "unsupported"
            )
        }
        val text = RelayVectors.asciiJson(obj("targets" to JsonArray(rows)))
        assertTrue("escaped size ${text.length}", text.length > 8_192)
        val outcome = reply(WakeCalls.targets(), "result" to obj("targets" to JsonArray(rows)))
        assertEquals(16, (outcome as RelayOutcome.Success).value.targets.size)
        assertEquals(65_536, RelayMethod.WAKE_TARGETS.responseBytes)
    }

    @Test fun theExpiryComesFromTheServerClockEstimateNeverTheDeviceClock() {
        val clock = clockAt(2_000_000_000L)
        // Five seconds of local monotonic time after the relay.info sample; the device wall clock is never read.
        val at = instant(clock, 7_000_000_000L)
        assertEquals(serverTime + 5_000, at.serverMs)
        val requestId = mint(at)
        val sent = params(WakeCalls.request(target, requestId, at))
        assertEquals(setOf("target_id", "request_id", "expires_at_ms"), sent.keys)
        assertEquals(target.value, sent["target_id"]!!.jsonPrimitive.content)
        assertEquals(requestId.value, sent["request_id"]!!.jsonPrimitive.content)
        assertEquals(
            serverTime + 5_000 + WakeCalls.DEFAULT_TTL_MS,
            sent["expires_at_ms"]!!.jsonPrimitive.content.toLong()
        )
        val capped = params(WakeCalls.request(target, requestId, at, WakeCalls.MAX_TTL_MS))
        assertEquals(
            serverTime + 5_000 + 30_000,
            capped["expires_at_ms"]!!.jsonPrimitive.content.toLong()
        )
        // The parsed wire form is the same call.
        val parsed = RelayCalls.fromWire(RelayMethod.WAKE_REQUEST, sent)
        assertNotNull(parsed)
        assertEquals(sent, params(parsed!!))
    }

    @Test fun theThirtySecondCapAndTheExpiryBoundsAreRefusedBeforeAnythingIsBuilt() {
        assertEquals(serverTime + 30_000, WakeCalls.expiry(serverTime, 30_000))
        assertEquals(serverTime + 1, WakeCalls.expiry(serverTime, 1))
        assertNull(WakeCalls.expiry(serverTime, 30_001))
        assertNull(WakeCalls.expiry(serverTime, 0))
        assertNull(WakeCalls.expiry(serverTime, -1))
        assertNull(WakeCalls.expiry(-1, 20_000))
        assertNull(WakeCalls.expiry(Long.MAX_VALUE - 10, 20_000))
        val at = instant(clockAt(1L), 1L)
        for (ttl in listOf(0L, 30_001L, Long.MAX_VALUE)) {
            try {
                WakeCalls.request(target, mint(at), at, ttl)
                fail("ttl $ttl was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun theRequestIdIsAFreshCanonicalLowercaseUuidVersionSevenOnTheServerClock() {
        val at = instant(clockAt(1L), 3_000_000L)
        val id = mint(at)
        assertTrue(
            id.value,
            Regex(
                "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ).matches(id.value)
        )
        assertEquals(at.serverMs, RequestIds.timestampMs(id))
        // The server's canonical_uuid refuses anything else; so does the typed id, before a call can exist.
        assertNull(RequestId.of(id.value.uppercase()))
        assertNull(RequestId.of("42f07b48-4fba-457e-9a9e-31ba5eff5dfa"))
        assertNull(WakeTargetId.of("42F07B48-4FBA-457E-9A9E-31BA5EFF5DFA"))
        assertNull(WakeTargetId.of("{42f07b48-4fba-457e-9a9e-31ba5eff5dfa}"))
        // Wire params with any other member (a MAC, a command) or a non-canonical id never parse into a call.
        val good = params(WakeCalls.request(target, id, at))
        assertNull(
            RelayCalls.fromWire(
                RelayMethod.WAKE_REQUEST,
                JsonObject(good + ("mac" to JsonPrimitive("x")))
            )
        )
        assertNull(
            RelayCalls.fromWire(
                RelayMethod.WAKE_REQUEST,
                JsonObject(good + ("request_id" to JsonPrimitive(id.value.uppercase())))
            )
        )
        assertNull(
            RelayCalls.fromWire(RelayMethod.WAKE_REQUEST, JsonObject(good - "expires_at_ms"))
        )
    }

    @Test fun resultsDecodeEveryStatusAndRefuseAForeignRequestId() {
        val at = instant(clockAt(1L), 1L)
        val id = mint(at)
        val call = WakeCalls.request(target, id, at)
        fun result(status: String, requestId: String = id.value) = reply(
            call,
            "result" to
                obj("request_id" to JsonPrimitive(requestId), "status" to JsonPrimitive(status))
        )
        val accepted = result("accepted")
        assertEquals(WakeResult(id, WakeStatus.ACCEPTED), (accepted as RelayOutcome.Success).value)
        assertEquals(WakeAnswer.ACCEPTED, WakeCalls.answer(RelayCallResult.Outcome(accepted)))
        assertEquals(
            WakeAnswer.UNAVAILABLE,
            WakeCalls.answer(RelayCallResult.Outcome(result("unavailable")))
        )
        assertEquals(
            WakeAnswer.UNSUPPORTED_TARGET,
            WakeCalls.answer(RelayCallResult.Outcome(result("unsupported")))
        )
        // Not this request's answer, or an unknown status: invalid, and the user is told it is unconfirmed.
        val other = mint(instant(clockAt(1L), 9_000_000_000L))
        for (invalid in listOf(
            result("accepted", other.value),
            result("booted"),
            result("accepted", "x")
        )) {
            assertTrue(invalid is RelayOutcome.Invalid)
            assertEquals(
                WakeAnswer.NOT_CONFIRMED,
                WakeCalls.answer(RelayCallResult.Outcome(invalid))
            )
        }
        assertTrue(
            reply(
                call,
                "result" to
                    obj(
                        "request_id" to JsonPrimitive(id.value),
                        "status" to JsonPrimitive("accepted"),
                        "x" to JsonPrimitive(1)
                    )
            )
                is RelayOutcome.Invalid
        )
    }

    @Test fun errorsUseTheNineStableCodesAndClassifyPlainly() {
        val at = instant(clockAt(1L), 1L)
        val call = WakeCalls.request(target, mint(at), at)
        fun error(code: String) = WakeCalls.answer(
            RelayCallResult.Outcome(reply(call, "error" to obj("code" to JsonPrimitive(code))))
        )
        val expected = mapOf(
            "permission_denied" to WakeAnswer.NOT_PERMITTED,
            "expired" to WakeAnswer.EXPIRED,
            "rate_limited" to WakeAnswer.TOO_SOON,
            "not_found" to WakeAnswer.NOT_FOUND,
            "busy" to WakeAnswer.BUSY,
            "temporarily_unavailable" to WakeAnswer.BUSY,
            "unsupported" to WakeAnswer.TURNED_OFF,
            "invalid_request" to WakeAnswer.REFUSED,
            "conflict" to WakeAnswer.REFUSED
        )
        for ((code, answer) in expected) assertEquals(code, answer, error(code))
        // A conversation-only code is never a wake answer: the frame is invalid, the outcome unconfirmed.
        val stale = reply(call, "error" to obj("code" to JsonPrimitive("stale_binding")))
        assertEquals(RelayProblem.UNEXPECTED_ERROR_CODE, (stale as RelayOutcome.Invalid).problem)
        assertEquals(WakeAnswer.NOT_CONFIRMED, error("profile_changed"))
        assertEquals(WakeAnswer.NOT_CONFIRMED, WakeCalls.answer(RelayCallResult.TimedOut))
        assertEquals(WakeAnswer.NOT_CONFIRMED, WakeCalls.answer(RelayCallResult.Closed))
        assertEquals(
            WakeAnswer.NOT_OFFERED,
            WakeCalls.answer(RelayCallResult.Refused(RelayRefusal.NOT_ADVERTISED))
        )
        assertEquals(
            WakeAnswer.NOT_SENT,
            WakeCalls.answer(RelayCallResult.Refused(RelayRefusal.NOT_READY))
        )
        assertEquals(
            WakeAnswer.NOT_SENT,
            WakeCalls.answer(RelayCallResult.Refused(RelayRefusal.SOCKET_BACKPRESSURE))
        )
    }

    @Test fun onlyAListedWakeMethodMayBeCalledAndTheWakeEnabledO5InfoDecodes() {
        val o5 = Info.decode(o5WakeInfo)
        assertEquals(serverTime, o5.serverTimeMs)
        assertEquals(RelayNegotiation.Gate.ALLOWED, RelayNegotiation.gate(o5, WakeCalls.targets()))
        assertEquals(
            RelayNegotiation.Gate.ALLOWED,
            RelayNegotiation.gate(o5, RelayMethod.WAKE_REQUEST)
        )
        assertTrue(o5.unknownMethods.isEmpty())
        // Without the wake methods (flag off, or no wake:read / wake:request in the grant) nothing wake is callable.
        val stock = Info.decode(
            JsonObject(
                o5WakeInfo - "server_time_ms" +
                    (
                        "methods" to
                            JsonArray(
                                listOf(
                                    "relay.info",
                                    "relay.outbox.read",
                                    "relay.outbox.ack"
                                ).map(::JsonPrimitive)
                            )
                        )
            )
        )
        assertNull(stock.serverTimeMs)
        assertEquals(
            RelayNegotiation.Gate.NOT_ADVERTISED,
            RelayNegotiation.gate(stock, RelayMethod.WAKE_TARGETS)
        )
        assertEquals(
            RelayNegotiation.Gate.NOT_ADVERTISED,
            RelayNegotiation.gate(stock, RelayMethod.WAKE_REQUEST)
        )
        // Read-only permission lists only the read.
        val readOnly = Info.decode(
            JsonObject(
                o5WakeInfo + (
                    "methods" to JsonArray(
                        listOf(
                            "relay.info",
                            "relay.outbox.read",
                            "relay.outbox.ack",
                            "wake.targets"
                        ).map(::JsonPrimitive)
                    )
                    )
            )
        )
        assertEquals(
            RelayNegotiation.Gate.ALLOWED,
            RelayNegotiation.gate(readOnly, RelayMethod.WAKE_TARGETS)
        )
        assertEquals(
            RelayNegotiation.Gate.NOT_ADVERTISED,
            RelayNegotiation.gate(readOnly, RelayMethod.WAKE_REQUEST)
        )
        // The conversation form still requires its clock.
        val conversation = RelayVectors.forMethod(RelayMethod.INFO).first {
            it.isSuccess && "server_time_ms" in it.result
        }.result
        assertNotNull(Info.decode(conversation).serverTimeMs)
        val call =
            RelayCall(
                RelayMethod.INFO,
                JsonObject(emptyMap()),
                RelayCalls.resultDecoder(RelayMethod.INFO)
            )
        assertTrue(
            reply(
                call,
                "result" to JsonObject(conversation - "server_time_ms")
            ) is RelayOutcome.Invalid
        )
        assertTrue(RelayMethod.WAKE_TARGETS.isWake && RelayMethod.WAKE_REQUEST.isWake)
        assertEquals(
            setOf(RelayMethod.WAKE_TARGETS, RelayMethod.WAKE_REQUEST),
            RelayMethod.entries.filter {
                it.isWake
            }.toSet()
        )
    }
}
