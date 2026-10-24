package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayEncoded
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.session.CacheReviewConfirmation
import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.ControllerSection
import dev.quantumink.hermesgadget.protocol.relay.session.EncodeRefusal
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
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayStore
import dev.quantumink.hermesgadget.protocol.relay.session.RetainedHistory
import dev.quantumink.hermesgadget.protocol.relay.session.Standing
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
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
class PhoneHistoryFix1Test {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class Owner : RelayScheduler {
        val queue = ArrayDeque<Runnable>()
        override fun post(task: Runnable) {
            queue.add(task)
        }
        override fun postDelayed(delayMs: Long, task: Runnable): Cancelable = Cancelable { }
        fun run() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
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
        fun fail(code: String) {
            answered = true
            deliver(buildJsonObject { put("error", buildJsonObject { put("code", code) }) })
        }
    }

    private class Link : RelayTransport {
        override val generation = 1L
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

    private inner class Harness(val root: File, val chosenId: String? = null) {
        val owner = Owner()
        val link = Link()
        val log = ArrayList<String>()
        var cacheFailure = false
        var evictionFailure = false
        var failingTarget: String? = null
        var fail: (RelayDocument) -> StoreCommit? = { null }
        val disk = EncryptedRelayStore(File(root, "state"), { key })
        val store = object : RelayStore {
            override fun load() = disk.load()
            override fun currentSequence() = disk.currentSequence()
            override fun commit(document: RelayDocument, base: Long): StoreCommit {
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
            { 5_000_000_000L },
            SecureRequestIdEntropy(),
            retainedHistory = object : RetainedHistory by phone.retained() {
                override fun evict(namespace: HistoryNamespace, conversationId: String?) {
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
    private fun replace(h: Harness, section: ControllerSection): Harness {
        h.controller.hide()
        h.run()
        val original = h.document.snapshot
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
        return Harness(h.root, h.chosenId)
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

    @Test fun activeDeletionFailureIsFencedOnOfflineRestartAndRecoveredOnFreshReady() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val before = bytes(h)
        h.evictionFailure = true
        deleteSelected(h)
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertEquals(EvictionKind.DELETED, h.document.section.evictions.single().kind)
        assertArrayEquals(before, bytes(h))
        h.controller.hide()
        h.run()
        val restart = Harness(h.root)
        restart.controller.prepare(restart.identity)
        restart.run()
        restart.controller.openRetained(h.summary.conversationId.value)
        restart.run()
        assertTrue("decoded deletion must fence retained listing", restart.ui.retained.isEmpty())
        assertEquals(null, restart.ui.retainedSelected)
        assertTrue(restart.link.requests.isEmpty())
        ready(restart)
        assertTrue(restart.cache.absent(restart.namespace, h.summary.conversationId.value))
        assertTrue(restart.document.section.evictions.isEmpty())
        assertFalse(restart.ui.contentEvictionPending)
    }

    @Test fun reauthorizationCannotBypassFailedRevocationAndUsesTheActualNegotiatedFlow() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.editMetadata(h.summary.conversationId.value, "rename", null)
        h.run()
        h.evictionFailure = true
        h.link.next("relay.conversation.update").fail("permission_denied")
        h.run()
        val before = bytes(h)
        val sends = h.link.requests.size
        h.controller.reauthorize()
        h.run()
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertTrue("unfinished revocation must remain inaccessible", h.ui.retained.isEmpty())
        assertEquals(Standing.STOPPED, h.document.section.enrollments.single().standing)
        var accepted = true
        h.controller.send("forbidden") { accepted = it }
        h.run()
        assertFalse(accepted)
        assertEquals(sends, h.link.requests.size)
        assertArrayEquals(before, bytes(h))
        h.evictionFailure = false
        h.controller.reauthorize()
        h.run()
        assertTrue(h.cache.absent(h.namespace, null))
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertTrue(h.document.section.evictions.isEmpty())
        assertTrue(h.link.requests.drop(sends).any { it.method == "relay.conversations.list" })
    }

    @Test fun staleCacheOnlyDoesNotVetoIndependentRevocationOrDeleteUnrelatedChangedBytes() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(200), "B must be removed")
        val a = h.summary.conversationId.value
        val old = HistoryEviction(h.namespace, a, EvictionKind.CACHE_ONLY)
        val deletion = HistoryEviction(h.namespace, id(200), EvictionKind.DELETED)
        val section =
            ControllerSection(h.document.section.enrollments, emptyList(), listOf(old, deletion))
        val restart = replace(h, section)
        ready(restart)
        assertTrue(
            "independent DELETED must progress",
            restart.cache.absent(restart.namespace, id(200))
        )
        assertFalse(restart.cache.absent(restart.namespace, a))
        assertEquals(listOf(old), restart.document.section.evictions)
        val revoked = HistoryEviction(restart.namespace, null, EvictionKind.REVOKED)
        val stopped = restart.document.section.enrollments.map {
            it.copy(standing = Standing.STOPPED)
        }
        val again = replace(restart, ControllerSection(stopped, emptyList(), listOf(old, revoked)))
        ready(again)
        assertTrue(again.cache.absent(again.namespace, null))
        assertTrue(again.document.section.evictions.isEmpty())
    }

    @Test fun staleCacheOnlyWithoutIndependentAuthorityPreservesExactBytes() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val old =
            HistoryEviction(h.namespace, h.summary.conversationId.value, EvictionKind.CACHE_ONLY)
        val before = bytes(h)
        val restart =
            replace(h, ControllerSection(h.document.section.enrollments, emptyList(), listOf(old)))
        ready(restart)
        assertArrayEquals(before, bytes(restart))
        assertEquals(listOf(old), restart.document.section.evictions)
    }

    private fun retirement(selectedB: Boolean, atCapacity: Boolean, notFound: Boolean = false) {
        val capture = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/relay-task4-v1/retired.json")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
        ).jsonObject
        val a = capture["conversation_id"]!!.jsonPrimitive.content
        var h = Harness(temp.newFolder(), a)
        h.begin()
        h.page(message(1))
        val b = id(700)
        if (!selectedB) put(h, b, "B retained")
        val old = if (atCapacity) evictions(h) else emptyList()
        val pending = if (atCapacity) intents(h).dropLast(1) else emptyList()
        if (atCapacity) {
            h = replace(h, ControllerSection(h.document.section.enrollments, pending, old))
            h.begin()
            h.page(message(1))
        }
        h.controller.leave()
        h.run()
        h.controller.refreshConversations()
        h.run()
        val bSummary =
            ConversationSummary(
                ConversationId.of(b)!!,
                "B",
                1,
                false,
                requireNotNull(h.info.serverTimeMs),
                true
            )
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary, bSummary), null, emptyList(), null).toWire())
        h.run()
        if (selectedB) {
            h.controller.select(b)
            h.run()
            h.link.next("relay.conversation.attach").reply(h.binding(b))
            h.run()
            h.page(message(2, "B active"))
            h.controller.send("B pending typed")
            h.run()
        }
        val bBefore = h.cache.read(h.namespace).threads.single {
            it.id == b
        }.messages.map { it.text }
        val turnsBefore = h.document.snapshot.ledger.turns.map { it.text }
        val voiceBefore = h.document.snapshot.captures.records
        h.controller.searchRetained("text-1")
        h.run()
        h.controller.openRetained(a)
        h.run()
        assertEquals("same population must expose A before removal", a, h.ui.retainedSelected?.id)
        h.controller.closeRetained()
        h.run()
        h.evictionFailure = true
        h.controller.editMetadata(a, "Requested", null)
        h.run()
        val update = h.link.next("relay.conversation.update")
        if (notFound) {
            update.fail(
                "not_found"
            )
        } else {
            update.reply(
                capture["steps"]!!.jsonArray.last().jsonObject["response"]!!
                    .jsonObject["result"]!!.jsonObject
            )
        }
        h.run()
        assertTrue("retired immutable target must be durably pending", h.ui.contentEvictionPending)
        assertEquals(old, h.document.section.evictions.take(old.size))
        h.controller.searchRetained("text-1")
        h.run()
        h.controller.openRetained(a)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        assertEquals(
            bBefore,
            h.cache.read(h.namespace).threads.single {
                it.id == b
            }.messages.map { it.text }
        )
        assertEquals(turnsBefore, h.document.snapshot.ledger.turns.map { it.text })
        assertEquals(voiceBefore, h.document.snapshot.captures.records)
        if (selectedB) {
            assertEquals(b, h.ui.selected?.id)
            assertEquals(listOf("B active"), h.ui.transcript.map { it.text })
        }
        h.evictionFailure = false
        h.controller.retryStorage()
        h.run()
        assertTrue(h.cache.absent(h.namespace, a))
        assertFalse(h.cache.absent(h.namespace, b))
        h.controller.searchRetained("B active")
        h.run()
        if (selectedB) {
            h.controller.openRetained(b)
            h.run()
            assertEquals("B remains readable after A removal", b, h.ui.retainedSelected?.id)
            h.controller.closeRetained()
            h.run()
            assertEquals(b, h.ui.selected?.id)
        }
        assertEquals(old, h.document.section.evictions)
        assertEquals(pending, h.document.section.intents)
        assertEquals(turnsBefore, h.document.snapshot.ledger.turns.map { it.text })
        assertEquals(voiceBefore, h.document.snapshot.captures.records)
        assertTrue(h.link.requests.none { it.method == "relay.outbox.ack" })
    }

    @Test fun registeredRetirementOfUnselectedAIsConsumedWithoutWaitingForAnInbox() =
        retirement(false, false)

    @Test fun tombstonedNotFoundForUnselectedAPreservesSelectedB() = retirement(true, false, true)

    @Test fun registeredRetirementAt64ObligationsAnd16IntentsPreservesSelectedB() = retirement(
        true,
        true
    )

    @Test fun selectedDeletionAt64ObligationsPreservesUnrelatedBAndEveryPriorObligation() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B retained")
        val old = evictions(h)
        val pending = intents(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending, old))
        h.begin()
        h.page(message(1))
        val before = bytes(h)
        h.controller.searchRetained("text-1")
        h.run()
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(h.summary.conversationId.value, h.ui.retainedSelected?.id)
        h.controller.closeRetained()
        h.run()
        val typedBefore = h.document.snapshot.ledger.turns
        val voiceBefore = h.document.snapshot.captures.records
        h.evictionFailure = true
        var escaped: Throwable? = null
        try {
            deleteSelected(h)
        } catch (failure: Throwable) {
            escaped = failure
        }
        assertEquals("capacity must be handled by owner", null, escaped)
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertEquals(old, h.document.section.evictions)
        assertEquals(pending, h.document.section.intents)
        assertArrayEquals(before, bytes(h))
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        h.evictionFailure = false
        h.controller.retryStorage()
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
        h.controller.searchRetained("B retained")
        h.run()
        h.controller.openRetained(id(700))
        h.run()
        assertEquals(id(700), h.ui.retainedSelected?.id)
        assertEquals(old, h.document.section.evictions)
        assertEquals(pending, h.document.section.intents)
        assertEquals(typedBefore, h.document.snapshot.ledger.turns)
        assertEquals(voiceBefore, h.document.snapshot.captures.records)
    }

    @Test
    fun atCapRevocationWithdrawsAuthorityBeforeAllocationAndPreservesAllPendingWorkOnFailure() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val old = evictions(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, emptyList(), old))
        h.begin()
        h.page(message(1))
        h.controller.editMetadata(h.summary.conversationId.value, "pending", null)
        h.run()
        h.evictionFailure = true
        h.link.next("relay.conversation.update").fail("permission_denied")
        var escaped: Throwable? = null
        try {
            h.run()
        } catch (failure: Throwable) {
            escaped = failure
        }
        assertEquals("valid at-cap denial must not escape owner", null, escaped)
        assertEquals(Standing.STOPPED, h.document.section.enrollments.single().standing)
        assertEquals(old, h.document.section.evictions)
        var accepted = true
        h.controller.send("future forbidden") { accepted = it }
        h.run()
        assertFalse(accepted)
        assertFalse(h.ui.canSend)
        assertTrue(h.link.requests.none { it.method == "relay.turn.submit" })
    }

    @Test fun seventeenthEditHasTypedBackpressureAndDoesNotReplaceExistingIntents() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val pending = intents(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending))
        h.begin()
        h.page(message(1))
        var escaped: Throwable? = null
        try {
            h.controller.editMetadata(h.summary.conversationId.value, "17", null)
            h.run()
        } catch (
            failure: Throwable
        ) {
            escaped =
                failure
        }
        assertEquals("intent quota must be typed", null, escaped)
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, h.ui.notice)
        assertEquals(pending, h.document.section.intents)
        assertTrue(h.link.requests.none { it.method == "relay.conversation.update" })
    }

    @Test fun cacheReviewAppendAt64RefusesBeforeEraseAndKeepsExactReviewAndBytes() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val old = evictions(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, emptyList(), old))
        h.begin()
        h.page(message(1))
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        val rendered = h.ui.cacheReview!!
        val before = bytes(h)
        var escaped: Throwable? = null
        try {
            h.controller.confirmCacheReview(h.ui.cacheReviewConfirmation!!)
            h.run()
        } catch (
            failure: Throwable
        ) {
            escaped =
                failure
        }
        assertEquals("review append quota must be typed", null, escaped)
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, h.ui.notice)
        assertEquals(rendered, h.ui.cacheReview)
        assertArrayEquals(before, bytes(h))
        assertEquals(old, h.document.section.evictions)
    }

    @Test fun renderedACallbackCannotConfirmReplacementGlobalBButFreshBWorks() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700))
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        val renderedA = h.ui.cacheReview!!
        val confirmationA = h.ui.cacheReviewConfirmation!!
        val oldRenderedCallback = { h.controller.confirmCacheReview(confirmationA) }
        h.controller.setHistoryRetention(0)
        h.run()
        val renderedB = h.ui.cacheReview!!
        assertFalse(renderedA == renderedB)
        val before = bytes(h)
        val sequence = h.disk.currentSequence()
        oldRenderedCallback()
        h.run()
        assertArrayEquals("old A callback must not erase global B", before, bytes(h))
        assertEquals(sequence, h.disk.currentSequence())
        assertEquals(renderedB, h.ui.cacheReview)
        h.controller.confirmCacheReview(h.ui.cacheReviewConfirmation!!)
        h.run()
        assertEquals(0, h.cache.read(h.namespace).retentionDays)
        assertTrue(h.cache.read(h.namespace).threads.isEmpty())
        // Retention preserves content-free rebuild markers; absent() deliberately includes those markers.
        assertFalse(h.cache.absent(h.namespace, null))
    }

    @Test fun currentFreshReviewAndBelowLimitMetadataRemainValidControls() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.editMetadata(h.summary.conversationId.value, "valid", true)
        h.run()
        assertEquals(
            "valid",
            h.link.next("relay.conversation.update").params["title"]!!.jsonPrimitive.content
        )
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        h.controller.confirmCacheReview(h.ui.cacheReviewConfirmation!!)
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
    }

    @Test fun anExistingIntentStillRetriesAtTheSixteenIntentBoundary() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val pending = intents(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending))
        h.begin()
        h.page(message(1))
        h.controller.refreshConversations()
        h.run()
        val b =
            ConversationSummary(
                ConversationId.of(id(700))!!,
                "B",
                1,
                false,
                requireNotNull(h.info.serverTimeMs),
                true
            )
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary, b), null, emptyList(), null).toWire())
        h.run()
        val before = h.document.snapshot.captures.records
        h.controller.retryMetadata(pending.first().requestId.value)
        h.run()
        val sent = h.link.next("relay.conversation.update")
        assertEquals(
            pending.first().requestId.value,
            sent.params["request_id"]!!.jsonPrimitive.content
        )
        assertEquals(16, h.document.section.intents.size)
        assertEquals(before, h.document.snapshot.captures.records)
    }

    private fun deletionCommitRefusal(refusal: StoreCommit, land: Boolean = false) {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B retained")
        val old = evictions(h)
        val pending = intents(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending, old))
        h.begin()
        h.page(message(1))
        val cacheBefore = bytes(h)
        val typedBefore = h.document.snapshot.ledger.turns
        val voiceBefore = h.document.snapshot.captures.records
        h.fail = { doc ->
            if (doc.section.emergencyDeletion != null) {
                if (land) {
                    check(
                        h.disk.commit(doc, h.disk.currentSequence()) is StoreCommit.Committed
                    )
                }
                refusal
            } else {
                null
            }
        }
        deleteSelected(h)
        assertArrayEquals(cacheBefore, bytes(h))
        h.controller.searchRetained("text-1")
        h.run()
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        assertTrue(h.ui.unsaved)
        assertEquals(old, h.document.section.evictions)
        assertEquals(pending, h.document.section.intents)
        assertEquals(typedBefore, h.document.snapshot.ledger.turns)
        assertEquals(voiceBefore, h.document.snapshot.captures.records)
        if (land) {
            val restart = Harness(h.root)
            restart.controller.prepare(restart.identity)
            restart.controller.searchRetained("text-1")
            restart.run()
            restart.controller.openRetained(h.summary.conversationId.value)
            restart.run()
            assertEquals(null, restart.ui.retainedSelected)
            assertTrue(restart.link.requests.isEmpty())
        }
        h.fail = { null }
        h.controller.retryStorage()
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
        assertEquals(old, h.document.section.evictions)
        assertEquals(null, h.document.section.emergencyDeletion)
        assertEquals(pending, h.document.section.intents)
        assertEquals(typedBefore, h.document.snapshot.ledger.turns)
        assertEquals(voiceBefore, h.document.snapshot.captures.records)
    }

    @Test fun failedEmergencyCommitCannotDeleteOrExposeA() =
        deletionCommitRefusal(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun refusedEmergencyCommitCannotDeleteOrExposeA() = deletionCommitRefusal(
        StoreCommit.Refused(
            EncodeRefusal.STORAGE_CAP,
            "document"
        )
    )

    @Test fun staleEmergencyCommitCannotDeleteOrExposeA() = deletionCommitRefusal(StoreCommit.Stale)

    @Test fun uncertainPromotedEmergencyCommitIsFencedOnOfflineRestartWithoutClaimingSuccess() =
        deletionCommitRefusal(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true), true)

    @Test fun failedFinalEmergencyClearRecoversIdempotentlyAndKeepsBReadable() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B retained")
        val old = evictions(h)
        val pending = intents(h)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending, old))
        h.begin()
        h.page(message(1))
        var owed = false
        h.fail = { doc ->
            if (doc.section.emergencyDeletion != null) owed = true
            if (owed &&
                doc.section.emergencyDeletion == null
            ) {
                StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
            } else {
                null
            }
        }
        deleteSelected(h)
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertTrue(h.document.section.emergencyDeletion != null)
        assertTrue(h.ui.contentEvictionPending)
        h.controller.hide()
        h.run()
        val restart = Harness(h.root)
        restart.controller.prepare(restart.identity)
        restart.controller.searchRetained("B retained")
        restart.run()
        restart.controller.openRetained(id(700))
        restart.run()
        assertEquals(id(700), restart.ui.retainedSelected?.id)
        ready(restart)
        assertEquals(null, restart.document.section.emergencyDeletion)
        assertEquals(old, restart.document.section.evictions)
        assertEquals(pending, restart.document.section.intents)
        assertFalse(restart.cache.absent(restart.namespace, id(700)))
    }

    @Test fun cancelledHiddenForegroundReplacedAndDuplicateReviewConfirmationsCannotMutate() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        fun token(): CacheReviewConfirmation {
            h.controller.reviewCache(h.summary.conversationId.value)
            h.run()
            return h.ui.cacheReviewConfirmation!!
        }
        val cancelled = token()
        val original = bytes(h)
        h.controller.cancelCacheReview()
        h.controller.confirmCacheReview(cancelled)
        h.run()
        assertArrayEquals(original, bytes(h))
        val hidden = token()
        h.controller.hide()
        h.controller.confirmCacheReview(hidden)
        h.run()
        assertArrayEquals(original, bytes(h))
        h.controller.prepare(h.identity)
        h.run()
        val replaced = token()
        h.controller.prepare(h.identity, foregroundSession = 9)
        h.controller.confirmCacheReview(replaced)
        h.run()
        assertArrayEquals(original, bytes(h))
        val fresh = token()
        h.controller.confirmCacheReview(fresh)
        h.run()
        val once = bytes(h)
        val sequence = h.disk.currentSequence()
        h.controller.confirmCacheReview(fresh)
        h.run()
        assertArrayEquals(once, bytes(h))
        assertEquals(sequence, h.disk.currentSequence())
    }

    @Test fun olderScopeDeletionIsRecoveredWithoutErasingTheSameIdInTheCurrentEpoch() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        val oldNamespace = h.namespace
        val record = h.document.section.enrollments.single().copy(
            reviewEpoch = 1,
            selectedConversationId = null
        )
        val deletion =
            HistoryEviction(oldNamespace, h.summary.conversationId.value, EvictionKind.DELETED)
        val restart = replace(h, ControllerSection(listOf(record), evictions = listOf(deletion)))
        put(restart, h.summary.conversationId.value, "new epoch A")
        put(restart, id(700), "new epoch B")
        restart.controller.prepare(restart.identity)
        restart.controller.searchRetained("new epoch A")
        restart.run()
        restart.controller.openRetained(h.summary.conversationId.value)
        restart.run()
        assertEquals(h.summary.conversationId.value, restart.ui.retainedSelected?.id)
        ready(restart)
        assertTrue(restart.cache.absent(oldNamespace, h.summary.conversationId.value))
        assertFalse(restart.cache.absent(restart.namespace, h.summary.conversationId.value))
        assertFalse(restart.cache.absent(restart.namespace, id(700)))
        assertTrue(restart.document.section.evictions.isEmpty())
    }

    @Test fun oneFailingDeletionDoesNotStarveAnotherIndependentDeletion() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B exact target")
        val a = HistoryEviction(h.namespace, h.summary.conversationId.value, EvictionKind.DELETED)
        val b = HistoryEviction(h.namespace, id(700), EvictionKind.DELETED)
        val restart =
            replace(h, ControllerSection(h.document.section.enrollments, evictions = listOf(a, b)))
        restart.failingTarget = a.conversationId
        ready(restart)
        assertFalse(restart.cache.absent(restart.namespace, a.conversationId))
        assertTrue(restart.cache.absent(restart.namespace, b.conversationId))
        assertEquals(listOf(a), restart.document.section.evictions)
    }

    @Test fun occupiedEmergencySlotHasTypedExactTargetBackpressureWithoutBroadErasure() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B retained")
        put(h, id(800), "E pending deletion")
        val old = evictions(h)
        val pending = intents(h)
        val emergency = HistoryEviction(h.namespace, id(800), EvictionKind.DELETED)
        h = replace(h, ControllerSection(h.document.section.enrollments, pending, old, emergency))
        h.evictionFailure = true
        h.begin()
        h.page(message(1))
        val before = bytes(h)
        val aggregate = h.document.snapshot
        deleteSelected(h)
        assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, h.ui.notice)
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertEquals(old, h.document.section.evictions)
        assertEquals(emergency, h.document.section.emergencyDeletion)
        assertEquals(pending, h.document.section.intents)
        assertEquals(aggregate.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(aggregate.captures.records, h.document.snapshot.captures.records)
        assertArrayEquals(before, bytes(h))
        h.controller.searchRetained("text-1")
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        h.controller.searchRetained("B retained")
        h.controller.openRetained(id(700))
        h.run()
        assertEquals(id(700), h.ui.retainedSelected?.id)
        assertFalse(h.cache.absent(h.namespace, h.summary.conversationId.value))
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        val rendered = h.ui.cacheReviewConfirmation!!
        h.controller.confirmCacheReview(rendered)
        h.run()
        assertEquals(rendered, h.ui.cacheReviewConfirmation)
        assertArrayEquals(before, bytes(h))
        assertEquals(old, h.document.section.evictions)
        assertEquals(emergency, h.document.section.emergencyDeletion)
    }
}
