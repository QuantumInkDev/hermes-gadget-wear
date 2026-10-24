package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.EventId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayEncoded
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayEvent
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.ControllerSection
import dev.quantumink.hermesgadget.protocol.relay.session.EncodeRefusal
import dev.quantumink.hermesgadget.protocol.relay.session.EncodeResult
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.EvictionKind
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryEviction
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import dev.quantumink.hermesgadget.protocol.relay.session.MetadataIntent
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.RelayNotice
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayStore
import dev.quantumink.hermesgadget.protocol.relay.session.RelayTiming
import dev.quantumink.hermesgadget.protocol.relay.session.RetainedHistory
import dev.quantumink.hermesgadget.protocol.relay.session.SnapshotCodec
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.ObligationSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnDraft
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.BeginOutcome
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real controller, assembly, encrypted aggregate and encrypted cache. Only transport/clock/filesystem cuts are synthetic. */
class PhoneHistoryFix3Test {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class Owner : RelayScheduler {
        private class Delayed(val due: Long, val task: Runnable) {
            var canceled = false
        }
        val queue = ArrayDeque<Runnable>()
        private val delayed = ArrayList<Delayed>()
        var millis = 0L
        val nanos get() = 5_000_000_000L + millis * 1_000_000L
        override fun post(task: Runnable) {
            queue.add(task)
        }
        override fun postDelayed(delayMs: Long, task: Runnable): Cancelable {
            val item = Delayed(millis + delayMs, task)
            delayed.add(item)
            return Cancelable { item.canceled = true }
        }
        fun run() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
        fun advance(ms: Long) {
            val target = millis + ms
            var steps = 0
            while (true) {
                val next =
                    delayed.filter { !it.canceled && it.due <= target }.minByOrNull { it.due }
                        ?: break
                check(++steps < 1000) { "unbounded scheduler work" }
                delayed.remove(next)
                millis = next.due
                next.task.run()
                run()
            }
            millis = target
            run()
        }
    }

    private class Pending(
        val method: String,
        val params: JsonObject,
        val deliver: (JsonObject) -> Unit
    ) {
        var answered = false
        fun reply(result: JsonObject) {
            answered = true
            deliver(buildJsonObject { put("result", result) })
        }
        fun fail(code: String, fresh: Boolean = false) {
            answered = true
            deliver(
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", code)
                            if (fresh) put("snapshot_required", true)
                        }
                    )
                }
            )
        }
    }

    private class Link : RelayTransport {
        override val generation = 1L
        var withdrawals = 0
        override fun invalidatePending() {
            withdrawals++
        }
        var counter = 0
        val requests = ArrayList<Pending>()
        override fun correlation() = CorrelationId.of("t-${++counter}")!!
        override fun <R : Any> call(
            correlation: CorrelationId,
            call: RelayCall<R>,
            timeoutMs: Long,
            handler: (RelayCallResult<R>) -> Unit
        ) {
            val encoded = RelayEnvelope.encodeRequest(correlation, call) as RelayEncoded.Frame
            val params = Json.parseToJsonElement(encoded.text).jsonObject["params"]!!.jsonObject
            requests.add(
                Pending(call.method.wire, params) { body ->
                    val response =
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("relay.response"),
                                "v" to JsonPrimitive(1),
                                "id" to JsonPrimitive(correlation.value)
                            ) + body
                        )
                    handler(
                        RelayCallResult.Outcome(
                            call.interpret(
                                correlation,
                                RelayEnvelope.decodeFrame(response.toString())
                            )
                        )
                    )
                }
            )
        }
        override fun refreshInfo(handler: (RelayCallResult<Info>) -> Unit) = Unit
        fun next(method: String) = requests.first { !it.answered && it.method == method }
    }

    private inner class Harness(
        val root: File,
        val chosenId: String? = null,
        val timing: RelayTiming = RelayTiming()
    ) {
        val owner = Owner()
        val link = Link()
        val log = ArrayList<String>()
        val attempts = ArrayList<RelayDocument>()
        val removalMarkers = ArrayList<Boolean>()
        var cacheFailure = false
        var evictionFailure = false
        var failingTarget: String? = null
        var fail: (RelayDocument) -> StoreCommit? = { null }
        val disk = EncryptedRelayStore(File(root, "state"), { key })
        val store = object : RelayStore {
            override fun load() = disk.load()
            override fun currentSequence() = disk.currentSequence()
            override fun commit(document: RelayDocument, base: Long): StoreCommit {
                attempts.add(document)
                log.add(
                    "aggregate:${document.snapshot.histories.maxOfOrNull {
                        it.committedOrdinal
                    } ?: 0}:${document.section.evictions.size}"
                )
                return fail(document) ?: disk.commit(document, base)
            }
        }
        val cache = EncryptedHistoryCache(File(root, "history"), {
            if (cacheFailure) error("owned key refusal") else key
        }, { 1_800_000_000_000L }, { log.add("cache-promoted") })
        val phone = PhoneHistoryCache { cache }
        val resource = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/relay-task4-v1/integrated.json")!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        ).jsonObject
        val steps = resource["steps"]!!.jsonArray.map { it.jsonObject }
        fun result(method: String) = steps.first {
            it["request"]!!.jsonObject["method"]!!.jsonPrimitive.content ==
                method
        }["response"]!!.jsonObject["result"]!!.jsonObject
        val info = decode(RelayCalls.info(), result("relay.info"))
        val creation = steps.first {
            it["request"]!!.jsonObject["method"]!!.jsonPrimitive.content ==
                "relay.conversation.create"
        }
        val originalSummary =
            decode(
                RelayCalls.createConversation(
                    RequestId.of(
                        creation["request"]!!.jsonObject["params"]!!.jsonObject["request_id"]!!
                            .jsonPrimitive.content
                    )!!
                ),
                result("relay.conversation.create")
            )
        val summary = chosenId?.let {
            ConversationSummary(
                ConversationId.of(it)!!,
                "A",
                1,
                false,
                requireNotNull(info.serverTimeMs),
                true
            )
        } ?: originalSummary
        fun binding(id: String = summary.conversationId.value): JsonObject = JsonObject(
            result("relay.conversation.attach") + ("conversation_id" to JsonPrimitive(id))
        )
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:hg-public-label")
        val controller = RelayController(
            store,
            owner,
            { owner.nanos },
            SecureRequestIdEntropy(),
            timing,
            retainedHistory = object : RetainedHistory by phone.retained() {
                override fun evict(namespace: HistoryNamespace, conversationId: String?) {
                    if (conversationId == summary.conversationId.value) {
                        removalMarkers.add(document.section.enrollments.single().selectionDeleted)
                    }
                    if (evictionFailure ||
                        (failingTarget != null && conversationId == failingTarget)
                    ) {
                        throw HistoryFailure(HistoryProblem.STORAGE)
                    }
                    phone.retained().evict(namespace, conversationId)
                }
            },
            listener = {}
        )
        val ui get() = controller.currentState
        val namespace get() = HistoryNamespace.from(
            (disk.load() as StoreLoad.Loaded).document.section.enrollments.single()
        )!!
        val document get() = (disk.load() as StoreLoad.Loaded).document
        fun run() = owner.run()
        fun begin() {
            controller.prepare(identity)
            controller.setEnabled(true)
            controller.attachTransport(link)
            controller.onChannel(
                RelayChannelState.Ready(
                    info,
                    (ServerClock.fix(info, 5_000_000_000L) as ClockFix.Fixed).clock,
                    1
                ),
                1
            )
            run()
            link.next(
                "relay.conversations.list"
            ).reply(ConversationPage(listOf(summary), null, emptyList(), null).toWire())
            run()
            controller.select(summary.conversationId.value)
            run()
            link.next("relay.conversation.attach").reply(binding())
            run()
        }
        fun page(
            vararg messages: dev.quantumink.hermesgadget.protocol.relay.HistoryMessage,
            cursor: String? = null
        ) {
            link.next("relay.history.page").reply(HistoryPage(messages.toList(), cursor).toWire())
            run()
        }
    }

    private fun <R : Any> decode(call: RelayCall<R>, result: JsonObject): R {
        val id = CorrelationId.of("fixture")!!
        val response =
            buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", id.value)
                put("result", result)
            }
        return (
            call.interpret(
                id,
                RelayEnvelope.decodeFrame(response.toString())
            ) as RelayOutcome.Success<R>
            ).value
    }
    private fun message(
        n: Int,
        text: String = "text-$n",
        index: Int = 0,
        count: Int = 1,
        time: Long = 100
    ) = dev.quantumink.hermesgadget.protocol.relay.HistoryMessage(
        MessageId.of("00000000-0000-4000-9000-%012d".format(n))!!,
        dev.quantumink.hermesgadget.protocol.relay.HistoryRole.USER,
        text,
        time,
        index,
        count
    )

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun put(h: Harness, conversation: String, text: String = "owned-$conversation") {
        val snapshot = h.cache.read(h.namespace)
        h.cache.put(
            h.namespace,
            HistoryThread(
                conversation,
                1,
                "Retained",
                false,
                listOf(HistoryMessage(id(900), HistoryRole.USER, text, 100)),
                true
            ),
            snapshot.revision,
            HistoryAnchor.Beginning,
            "fixture-$conversation"
        )
    }
    private fun bytes(h: Harness) = File(h.root, "history/history.v1").readBytes()
    private fun replace(
        h: Harness,
        section: ControllerSection,
        count: Int,
        oldestAt: Long?
    ): Harness {
        h.controller.hide()
        h.run()
        val raw = h.document.snapshot
        val current = raw.deliveries.single()
        val at = requireNotNull(h.info.serverTimeMs)
        val delivery = DeliverySnapshot(
            current.scope,
            37,
            (0 until count).map {
                SeenKeySnapshot(
                    "canonical",
                    h.summary.conversationId.value,
                    id(10_000 + it),
                    if (it == 0) oldestAt ?: at else at
                )
            },
            current.obligations,
            listOf(id(9900))
        )
        val foreign =
            DeliverySnapshot(
                current.scope.copy(enrollmentKey = "unrelated-enrollment", reviewEpoch = 2),
                41,
                listOf(SeenKeySnapshot("opaque", "foreign-conversation", "foreign-event", at - 77)),
                listOf(ObligationSnapshot(null, "HISTORY", "OUTBOX_GAP")),
                listOf(id(9901))
            )
        val original =
            RelayStateSnapshot(
                raw.version,
                raw.ledger,
                raw.histories,
                listOf(delivery, foreign),
                raw.captures
            )
        val snapshot = if (section.intents.isNotEmpty()) {
            val scope = h.document.section.enrollments.single().scope()!!
            val bound = decode(RelayCalls.attach(ConversationId.of(id(700))!!), h.binding(id(700)))
            val authority = RelayAuthority.fresh().attach(
                scope,
                bound
            )
            val clock = (ServerClock.fix(h.info, 5_000_000_000L) as ClockFix.Fixed).clock
            val at = (
                clock.read(
                    5_000_000_000L
                ) as InstantRead.At
                ).instant
            val ledger = TurnLedger.empty().prepare(
                TurnDraft(
                    authority.token.conversation,
                    "B prepared typed"
                ),
                at,
                SecureRequestIdEntropy()
            ).ledger
            val voice = VoiceCaptureLedger.empty().begin(
                ledger,
                authority.authority,
                authority.token,
                at,
                SecureRequestIdEntropy(),
                AggregateShape.EMPTY
            )
            check(
                voice.result is BeginOutcome.Began
            )
            RelayStateSnapshot(
                original.version,
                ledger.snapshot(),
                original.histories,
                original.deliveries,
                voice.ledger.snapshot()
            )
        } else {
            original
        }
        check(
            h.disk.commit(
                RelayDocument(section, snapshot),
                h.disk.currentSequence()
            ) is StoreCommit.Committed
        )
        return Harness(h.root, h.chosenId, h.timing)
    }
    private fun ready(h: Harness) {
        h.controller.prepare(h.identity)
        h.controller.attachTransport(h.link)
        h.controller.onChannel(
            RelayChannelState.Ready(
                h.info,
                (ServerClock.fix(h.info, 5_000_000_000L) as ClockFix.Fixed).clock,
                1
            ),
            1
        )
        h.run()
    }
    private fun evictions(h: Harness): List<HistoryEviction> = (1..64).map {
        val conversation = id(it)
        put(h, conversation)
        HistoryEviction(h.namespace, conversation, EvictionKind.CACHE_ONLY)
    }
    private fun intents(h: Harness): List<MetadataIntent> = (1..16).map {
        MetadataIntent(
            h.namespace,
            ConversationId.of(if (it == 1) id(700) else id(100 + it))!!,
            RequestId.of("01a14d67-a300-7000-8000-%012d".format(it))!!,
            1,
            "pending-$it",
            null,
            requireNotNull(h.info.serverTimeMs)
        )
    }
    private fun deleteSelected(h: Harness) {
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").fail("not_found")
        h.run()
    }

    private fun occupied(count: Int = 4096, oldestAt: Long? = null): Harness {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1, "A retained"))
        put(h, id(700), "B retained")
        put(h, id(800), "E pending deletion")
        val old = evictions(h)
        val pending = intents(h)
        val emergency = HistoryEviction(h.namespace, id(800), EvictionKind.DELETED)
        h =
            replace(
                h,
                ControllerSection(h.document.section.enrollments, pending, old, emergency),
                count,
                oldestAt
            )
        h.evictionFailure = true
        ready(h)
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary), null, emptyList(), null).toWire())
        h.run()
        h.controller.searchRetained("A retained")
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(
            "same population must contain A before observation",
            h.summary.conversationId.value,
            h.ui.retainedSelected?.id
        )
        h.controller.closeRetained()
        h.run()
        return h
    }

    private fun startRead(h: Harness): Pending {
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").reply(h.binding())
        h.run()
        h.page(message(1, "A retained"))
        if (h.info.advertises(dev.quantumink.hermesgadget.protocol.relay.RelayMethod.TASKS_LIST)) {
            h.link.next("relay.tasks.list").reply(TaskPage(emptyList(), null).toWire())
        } else {
            assertTrue(h.link.requests.none { it.method == "relay.tasks.list" })
        }
        h.run()
        assertEquals(RelayPhase.SELECTED, h.ui.phase)
        h.owner.advance(8_000)
        return h.link.next("relay.events.read")
    }

    // Matches the unchanged registered Metadata._invalidate -> Binding.tombstone/read DTO, not a new capture.
    private fun tombstone(
        h: Harness,
        eventId: String = id(30_000),
        target: String = h.summary.conversationId.value
    ): EventsPage {
        val bound = decode(RelayCalls.attach(h.summary.conversationId), h.binding())
        val payload = EventPayload.ConversationChanged(
            ConversationSummary(
                ConversationId.of(target)!!,
                null,
                2,
                false,
                requireNotNull(h.info.serverTimeMs),
                false
            ),
            true
        )
        return EventsPage(
            listOf(
                RelayEvent(
                    EventId.of(eventId)!!,
                    bound.conversationId,
                    bound.bindingId,
                    bound.lastSequence + 7,
                    bound.replayEpoch,
                    payload
                )
            ),
            bound.replayEpoch,
            bound.lastSequence + 7,
            true
        )
    }

    private fun ordinary(h: Harness): EventsPage {
        val bound = decode(RelayCalls.attach(h.summary.conversationId), h.binding())
        val event = RelayEvent(
            EventId.of(id(30_001))!!,
            bound.conversationId,
            bound.bindingId,
            bound.lastSequence + 1,
            bound.replayEpoch,
            EventPayload.Message(
                MessageId.of(id(30_002))!!,
                dev.quantumink.hermesgadget.protocol.relay.HistoryRole.ASSISTANT,
                "ordinary recovery",
                200
            )
        )
        return EventsPage(listOf(event), bound.replayEpoch, event.sequence, false)
    }

    private fun delivery(h: Harness, document: RelayDocument): DeliverySnapshot =
        document.snapshot.deliveries.single {
            it.scope.endpointKey == h.identity.endpointKey &&
                it.scope.enrollmentKey == h.identity.enrollmentLabel
        }
    private fun encoded(document: RelayDocument) =
        (SnapshotCodec.encode(document) as EncodeResult.Encoded).bytes
    private fun sameAggregate(before: RelayDocument, after: RelayDocument) =
        assertArrayEquals(encoded(before), encoded(RelayDocument(before.section, after.snapshot)))
    private fun noEffects(h: Harness) = assertTrue(
        h.link.requests.none {
            it.method in
                setOf("relay.turn.submit", "relay.outbox.ack", "relay.conversation.create")
        }
    )
    private fun offline(h: Harness): Harness {
        h.controller.hide()
        h.run()
        return Harness(h.root).also {
            it.controller.prepare(it.identity)
            it.run()
        }
    }
    private fun query(h: Harness, text: String, conversation: String): String? {
        h.controller.searchRetained(text)
        h.controller.openRetained(conversation)
        h.run()
        return h.ui.retainedSelected?.id
    }

    @Test fun fullRecentTableCannotRedisplayADeletedTargetAfterOfflineReplacement() {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        assertEquals(4096, delivery(h, before).seen.size)
        assertTrue(encoded(before).size < 8 * 1024 * 1024)
        read.reply(tombstone(h).toWire())
        h.run()
        val replacement = offline(h)
        assertEquals(
            "A must not be redisplayed from the same population after a current tombstone",
            null,
            query(replacement, "A retained", h.summary.conversationId.value)
        )
        assertEquals(id(700), query(replacement, "B retained", id(700)))
        val after = h.document
        assertTrue(after.section.enrollments.single().selectionDeleted)
        assertEquals(before.section.evictions, after.section.evictions)
        assertEquals(before.section.emergencyDeletion, after.section.emergencyDeletion)
        assertEquals(before.section.intents, after.section.intents)
        sameAggregate(before, after)
        assertTrue(h.ui.contentEvictionPending)
        noEffects(h)
    }

    @Test fun ordinaryFullReplyRecoversThroughTheControllerWhenOneKeyBecomesAgeEligible() {
        val source = Harness(temp.newFolder())
        val at = requireNotNull(source.info.serverTimeMs)
        val h = occupied(oldestAt = at - StateLimits.ID_RETENTION_MS + 8_001)
        val read = startRead(h)
        val before = h.document
        read.reply(ordinary(h).toWire())
        h.run()
        assertFalse(h.ui.transcript.any { it.text == "ordinary recovery" })
        assertEquals(delivery(h, before).seen, delivery(h, h.document).seen)
        h.owner.advance(8_000)
        assertTrue(
            "the retained answer must be restaged by the actual polling owner",
            h.ui.transcript.any {
                it.text ==
                    "ordinary recovery"
            }
        )
        val after = delivery(h, h.document).seen
        assertEquals(4096, after.size)
        assertFalse(after.any { it.event == id(10_000) })
        assertEquals(delivery(h, before).seen.drop(1), after.filter { it.event != id(30_001) })
        assertTrue(after.any { it.event == id(30_001) })
        noEffects(h)
    }

    @Test fun ordinaryBelowCapacityStillCommitsTheActualEvent() {
        val h = occupied(count = 4095)
        startRead(h).reply(ordinary(h).toWire())
        h.run()
        assertTrue(h.ui.transcript.any { it.text == "ordinary recovery" })
        assertEquals(4096, delivery(h, h.document).seen.size)
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        noEffects(h)
    }

    @Test fun terminalBelowCapacityKeepsAllExistingSeenAgesAndUnrelatedB() {
        val h = occupied(count = 4095)
        val read = startRead(h)
        val before = h.document
        read.reply(tombstone(h).toWire())
        h.run()
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        sameAggregate(before, h.document)
        assertEquals(id(700), query(offline(h), "B retained", id(700)))
        noEffects(h)
    }

    @Test fun fullOrdinaryWithNoSafeRoomIsExplicitUnconsumedCapacity() {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        read.reply(ordinary(h).toWire())
        h.run()
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, h.ui.notice)
        sameAggregate(before, h.document)
        assertFalse(h.ui.transcript.any { it.text == "ordinary recovery" })
        val writes = h.attempts.size
        h.owner.advance(60_000)
        assertEquals(
            "no repeated no-op aggregate commits while legitimately full",
            writes,
            h.attempts.size
        )
        assertEquals(1, h.link.requests.count { it.method == "relay.events.read" })
        assertEquals(id(700), query(offline(h), "B retained", id(700)))
        noEffects(h)
    }

    @Test fun foreignNestedTombstonePayloadCannotDeleteTheBoundA() {
        val h = occupied(count = 4095)
        val read = startRead(h)
        val before = h.document
        read.reply(tombstone(h, target = id(700)).toWire())
        h.run()
        assertFalse(
            "outer A cannot lend authority to B's payload",
            h.document.section.enrollments.single().selectionDeleted
        )
        sameAggregate(before, h.document)
        val replacement = offline(h)
        assertEquals(
            h.summary.conversationId.value,
            query(replacement, "A retained", h.summary.conversationId.value)
        )
        assertEquals(id(700), query(replacement, "B retained", id(700)))
        noEffects(h)
    }

    @Test fun mixedTerminalPageCannotEraseAOrConsumeItsOrdinaryPrefix() {
        val h = occupied(count = 4093)
        val read = startRead(h)
        val before = h.document
        val terminal = tombstone(h)
        val mixed =
            EventsPage(
                ordinary(h).events + terminal.events,
                terminal.replayEpoch,
                terminal.lastSequence,
                true
            )
        read.reply(mixed.toWire())
        h.run()
        assertFalse(
            "the registered tombstone replaces the backlog with ONE event",
            h.document.section.enrollments.single().selectionDeleted
        )
        assertFalse(h.ui.transcript.any { it.text == "ordinary recovery" })
        sameAggregate(before, h.document)
        assertEquals(
            h.summary.conversationId.value,
            query(offline(h), "A retained", h.summary.conversationId.value)
        )
        noEffects(h)
    }

    @Test fun duplicateSeenTerminalIdentityKeepsTheExistingPageLevelDeletionContract() {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        read.reply(tombstone(h, eventId = id(10_000)).toWire())
        h.run()
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        sameAggregate(before, h.document)
        assertEquals(null, query(offline(h), "A retained", h.summary.conversationId.value))
        noEffects(h)
    }

    private fun sectionsPreserved(before: RelayDocument, after: RelayDocument) {
        assertEquals(before.section.evictions, after.section.evictions)
        assertEquals(before.section.emergencyDeletion, after.section.emergencyDeletion)
        assertEquals(before.section.intents, after.section.intents)
        assertEquals(
            before.section.enrollments.single().standing,
            after.section.enrollments.single().standing
        )
        sameAggregate(before, after)
    }

    private fun failedMarker(result: StoreCommit, promoted: Boolean = false) {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        val retained = bytes(h)
        h.fail = { doc ->
            if (doc.section.enrollments.single().selectionDeleted) {
                if (promoted) {
                    check(
                        h.disk.commit(doc, h.disk.currentSequence()) is StoreCommit.Committed
                    )
                }
                result
            } else {
                null
            }
        }
        read.reply(tombstone(h).toWire())
        h.run()
        assertTrue(h.ui.unsaved)
        assertArrayEquals(retained, bytes(h))
        assertEquals(promoted, h.document.section.enrollments.single().selectionDeleted)
        sectionsPreserved(before, h.document)
        assertEquals(null, query(h, "A retained", h.summary.conversationId.value))
        val sequence = h.disk.currentSequence()
        read.reply(ordinary(h).toWire())
        h.run()
        assertEquals(sequence, h.disk.currentSequence())
        if (promoted) {
            val replacement = Harness(h.root)
            replacement.controller.prepare(replacement.identity)
            replacement.run()
            assertEquals(null, query(replacement, "A retained", h.summary.conversationId.value))
            assertEquals(id(700), query(replacement, "B retained", id(700)))
        }
        h.fail = { null }
        h.evictionFailure = false
        h.failingTarget = id(800)
        h.controller.retryStorage()
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
        assertFalse(h.cache.absent(h.namespace, id(800)))
        assertTrue(h.removalMarkers.isNotEmpty() && h.removalMarkers.all { it })
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(null, h.document.section.enrollments.single().selectedConversationId)
        // A's own history resume is removed, unrelated pending text/captures/delivery are exact.
        assertEquals(before.snapshot.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(before.snapshot.captures.records, h.document.snapshot.captures.records)
        assertEquals(delivery(h, before).seen, delivery(h, h.document).seen)
        assertEquals(before.section.intents, h.document.section.intents)
        assertEquals(before.section.evictions, h.document.section.evictions)
        assertEquals(before.section.emergencyDeletion, h.document.section.emergencyDeletion)
        noEffects(h)
    }

    @Test fun fullSeenFailedMarkerWaitsForConfirmationBeforeRemoval() =
        failedMarker(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun fullSeenRefusedMarkerPreservesObservedFenceAndRecovers() =
        failedMarker(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun fullSeenStaleMarkerWaitsForConfirmationBeforeRemoval() = failedMarker(
        StoreCommit.Stale
    )

    @Test fun fullSeenPromotedUncertainMarkerFencesOfflineWithoutPrematureRemoval() =
        failedMarker(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true), true)

    @Test fun failedFinalClearKeepsExactMarkerUntilConfirmedAndPreservesB() {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        h.evictionFailure = false
        h.failingTarget = id(800)
        h.fail = { doc ->
            if (!doc.section.enrollments.single().selectionDeleted &&
                doc.section.enrollments.single().selectedConversationId == null
            ) {
                StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
            } else {
                null
            }
        }
        read.reply(tombstone(h).toWire())
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(
            h.summary.conversationId,
            h.document.section.enrollments.single().selectedConversationId
        )
        assertTrue(h.ui.contentEvictionPending)
        assertFalse(
            "later independent obligation reconfirmed the durable marked runtime",
            h.ui.unsaved
        )
        assertTrue(
            h.attempts.any {
                !it.section.enrollments.single().selectionDeleted &&
                    it.section.enrollments.single().selectedConversationId == null
            }
        )
        assertTrue(h.removalMarkers.isNotEmpty() && h.removalMarkers.all { it })
        sectionsPreserved(before, h.document)
        val replacement = offline(h)
        assertEquals(null, query(replacement, "A retained", h.summary.conversationId.value))
        assertEquals(id(700), query(replacement, "B retained", id(700)))
        replacement.failingTarget = id(800)
        ready(replacement)
        assertFalse(replacement.document.section.enrollments.single().selectionDeleted)
        assertEquals(null, replacement.document.section.enrollments.single().selectedConversationId)
        assertEquals(delivery(h, before).seen, delivery(replacement, replacement.document).seen)
        assertEquals(before.section.evictions, replacement.document.section.evictions)
        assertEquals(before.section.intents, replacement.document.section.intents)
        noEffects(replacement)
    }

    private fun invalidTerminal(transform: (JsonObject) -> JsonObject) {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        read.reply(transform(tombstone(h).toWire()))
        h.run()
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        sectionsPreserved(before, h.document)
        val replacement = offline(h)
        assertEquals(
            h.summary.conversationId.value,
            query(replacement, "A retained", h.summary.conversationId.value)
        )
        assertEquals(id(700), query(replacement, "B retained", id(700)))
        noEffects(h)
    }
    private fun eventField(page: JsonObject, name: String, value: JsonPrimitive): JsonObject {
        val event = page["events"]!!.jsonArray.single().jsonObject
        return JsonObject(
            page +
                (
                    "events" to
                        kotlinx.serialization.json.JsonArray(
                            listOf(JsonObject(event + (name to value)))
                        )
                    )
        )
    }

    @Test fun foreignOuterConversationCannotBorrowTerminalAuthority() =
        invalidTerminal { eventField(it, "conversation_id", JsonPrimitive(id(700))) }

    @Test fun foreignBindingCannotBorrowTerminalAuthority() =
        invalidTerminal { eventField(it, "binding_id", JsonPrimitive(id(900))) }

    @Test fun foreignEventEpochCannotBorrowTerminalAuthority() =
        invalidTerminal { eventField(it, "replay_epoch", JsonPrimitive("foreign-epoch")) }

    @Test fun foreignPageEpochCannotBorrowTerminalAuthority() =
        invalidTerminal { JsonObject(it + ("replay_epoch" to JsonPrimitive("foreign-epoch"))) }

    @Test fun staleSequenceCannotBorrowTerminalAuthority() = invalidTerminal {
        val bound = it["events"]!!.jsonArray.single().jsonObject
        val stale = bound["sequence"]!!.jsonPrimitive.content.toLong() - 7
        JsonObject(
            eventField(it, "sequence", JsonPrimitive(stale)) +
                ("last_sequence" to JsonPrimitive(stale))
        )
    }

    @Test fun replacedSelectionRejectsOldTerminalCallbackAndKeepsBothCaches() {
        val h = occupied()
        val read = startRead(h)
        h.controller.leave()
        h.run()
        val before = h.document
        read.reply(tombstone(h).toWire())
        h.run()
        sectionsPreserved(before, h.document)
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(
            h.summary.conversationId.value,
            query(offline(h), "A retained", h.summary.conversationId.value)
        )
        noEffects(h)
    }

    @Test fun deferredReplyCannotBeReplacedBySecondCallbackAndClearsCapacityAfterProgress() {
        val source = Harness(temp.newFolder())
        val h = occupied(
            oldestAt =
            requireNotNull(source.info.serverTimeMs) - StateLimits.ID_RETENTION_MS + 8_001
        )
        val read = startRead(h)
        read.reply(ordinary(h).toWire())
        h.run()
        read.reply(tombstone(h).toWire())
        h.run()
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        h.owner.advance(8_000)
        assertTrue(h.ui.transcript.any { it.text == "ordinary recovery" })
        assertFalse(h.ui.notice == RelayNotice.STORAGE_FULL_PENDING_ITEMS)
        noEffects(h)
    }

    @Test fun sameInfoRefreshDiscardsOldDeferredReplyAndStartsANewCurrentRead() {
        val h = occupied()
        val old = startRead(h)
        old.reply(ordinary(h).toWire())
        h.run()
        h.controller.onChannel(RelayChannelState.LoadingInfo, 1)
        h.run()
        h.controller.onChannel(
            RelayChannelState.Ready(
                h.info,
                (ServerClock.fix(h.info, h.owner.nanos) as ClockFix.Fixed).clock,
                1
            ),
            1
        )
        h.run()
        val before = h.document
        old.reply(tombstone(h).toWire())
        h.run()
        h.owner.advance(8_000)
        assertEquals(
            "the canceled old read must not strand the admitted binding",
            2,
            h.link.requests.count { it.method == "relay.events.read" }
        )
        assertFalse(h.ui.transcript.any { it.text == "ordinary recovery" })
        sectionsPreserved(before, h.document)
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        h.link.next("relay.events.read").reply(tombstone(h).toWire())
        h.run()
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        noEffects(h)
    }

    private fun failedPrune(result: StoreCommit) {
        val source = Harness(temp.newFolder())
        val h = occupied(
            oldestAt =
            requireNotNull(source.info.serverTimeMs) - StateLimits.ID_RETENTION_MS + 8_001
        )
        val read = startRead(h)
        val before = h.document
        read.reply(ordinary(h).toWire())
        h.run()
        h.fail = { doc -> if (delivery(h, doc).seen.size < 4096) result else null }
        h.owner.advance(8_000)
        assertEquals(delivery(h, before).seen, delivery(h, h.document).seen)
        assertFalse(h.ui.transcript.any { it.text == "ordinary recovery" })
        assertTrue(h.ui.canSend)
        h.fail = { null }
        h.owner.advance(30_000)
        assertTrue(h.ui.transcript.any { it.text == "ordinary recovery" })
        assertEquals(4096, delivery(h, h.document).seen.size)
        assertEquals(1, h.link.requests.count { it.method == "relay.events.read" })
        noEffects(h)
    }

    @Test fun failedPruneKeepsTheOriginalReplyForPacedRecovery() =
        failedPrune(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun refusedPruneKeepsTheOriginalReplyForPacedRecovery() =
        failedPrune(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun foreignReplyCorrelationCannotSupplyTerminalEvidence() {
        val h = occupied()
        val read = startRead(h)
        val before = h.document
        read.deliver(
            buildJsonObject {
                put("id", "foreign-correlation")
                put("result", tombstone(h).toWire())
            }
        )
        h.run()
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        sectionsPreserved(before, h.document)
        assertEquals(
            h.summary.conversationId.value,
            query(offline(h), "A retained", h.summary.conversationId.value)
        )
        noEffects(h)
    }

    @Test fun hiddenControllerCannotApplyItsOldTerminalReply() {
        val h = occupied()
        val read = startRead(h)
        val replacement = offline(h)
        val before = h.document
        read.reply(tombstone(h).toWire())
        h.run()
        sectionsPreserved(before, h.document)
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(
            h.summary.conversationId.value,
            query(replacement, "A retained", h.summary.conversationId.value)
        )
        noEffects(h)
    }
}
