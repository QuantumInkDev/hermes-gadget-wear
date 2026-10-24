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
import dev.quantumink.hermesgadget.protocol.relay.session.RelayTiming
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
class PhoneHistoryFix2Test {
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
            timing,
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

    private fun occupied(): Harness {
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
        ready(h)
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary), null, emptyList(), null).toWire())
        h.run()
        h.controller.searchRetained("text-1")
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

    @Test fun occupiedSlotCannotRedisplayAnObservedDeletionAfterOfflineReplacement() {
        val h = occupied()
        val before = bytes(h)
        val original = h.document
        h.controller.select(h.summary.conversationId.value)
        h.run()
        val attach = h.link.requests.lastOrNull {
            !it.answered &&
                it.method == "relay.conversation.attach"
        }
        val observed = attach != null
        if (attach != null) {
            attach.fail("not_found")
            h.run()
            h.controller.searchRetained("text-1")
            h.controller.openRetained(h.summary.conversationId.value)
            h.run()
            assertEquals(null, h.ui.retainedSelected)
        } else {
            assertEquals(RelayNotice.STORAGE_FULL_PENDING_ITEMS, h.ui.notice)
            assertEquals(HistoryProblem.CAPACITY, h.ui.retainedProblem)
        }
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertEquals(original.section.evictions, h.document.section.evictions)
        assertEquals(original.section.emergencyDeletion, h.document.section.emergencyDeletion)
        assertEquals(original.section.intents, h.document.section.intents)
        assertEquals(original.snapshot.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(original.snapshot.captures.records, h.document.snapshot.captures.records)
        assertArrayEquals(before, bytes(h))
        h.controller.hide()
        h.run()
        val restart = Harness(h.root)
        restart.controller.prepare(restart.identity)
        restart.controller.searchRetained("B retained")
        restart.controller.openRetained(id(700))
        restart.run()
        assertEquals(id(700), restart.ui.retainedSelected?.id)
        restart.controller.searchRetained("text-1")
        restart.controller.openRetained(restart.summary.conversationId.value)
        restart.run()
        if (observed) {
            assertEquals(
                "known-deleted A must stay fenced after process replacement",
                null,
                restart.ui.retainedSelected
            )
        } else {
            // No canonical deletion was observed; the refused attachment cannot invent erasure authority.
            assertEquals(restart.summary.conversationId.value, restart.ui.retainedSelected?.id)
        }
        assertTrue(restart.link.requests.isEmpty())
    }

    @Test fun occupiedSlotUsesItsPersistedSelectionAndRefusesToOverwriteDeletedIdentity() {
        val h = occupied()
        h.controller.select(h.summary.conversationId.value)
        h.run()
        assertTrue(h.link.requests.any { it.method == "relay.conversation.attach" })
        val selected = h.document.section.enrollments.single()
        assertEquals(h.summary.conversationId, selected.selectedConversationId)
        assertFalse(selected.selectionDeleted)
        h.link.next("relay.conversation.attach").fail("not_found")
        h.run()
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        val requests = h.link.requests.size
        h.controller.select(id(700))
        h.controller.create("must wait for exact removal")
        h.controller.leave()
        h.controller.setEnabled(false)
        h.controller.setEnabled(true)
        h.controller.reauthorize()
        h.controller.acceptReview()
        h.run()
        assertEquals(requests, h.link.requests.size)
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(
            h.summary.conversationId,
            h.document.section.enrollments.single().selectedConversationId
        )
        assertEquals(HistoryProblem.CAPACITY, h.ui.retainedProblem)
        assertFalse(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
    }

    private fun authority(code: String, retries: Int) {
        val h = Harness(temp.newFolder(), id(700), RelayTiming(maxRetries = retries))
        h.begin()
        h.page(message(1, "B current text"))
        h.controller.searchOnline("needle", true)
        h.run()
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary), "tail", emptyList(), null).toWire())
        h.run()
        h.controller.loadMoreConversations()
        h.run()
        val tail = h.link.next("relay.conversations.list")
        val withdrawals = h.link.withdrawals
        tail.fail(code, true)
        h.run()
        val expected = if (code ==
            "permission_denied"
        ) {
            Standing.STOPPED
        } else {
            Standing.REVIEW_REQUIRED
        }
        assertEquals(
            "authority verdict must outrank the cursor flag",
            expected,
            h.document.section.enrollments.single().standing
        )
        assertTrue(h.link.withdrawals > withdrawals)
        assertFalse(h.ui.canSend)
        h.controller.searchRetained("B current text")
        h.controller.openRetained(id(700))
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        assertEquals(code == "permission_denied", h.cache.absent(h.namespace, id(700)))
        var accepted = true
        h.controller.send("must refuse after current authority verdict") { accepted = it }
        h.run()
        assertFalse(accepted)
        val sequence = h.disk.currentSequence()
        tail.reply(ConversationPage(listOf(h.summary), "obsolete", emptyList(), null).toWire())
        h.run()
        assertEquals(sequence, h.disk.currentSequence())
        assertEquals(expected, h.document.section.enrollments.single().standing)
        assertEquals("needle", h.ui.onlineQuery)
        assertEquals(true, h.ui.bookmarkFilter)
        h.controller.hide()
        h.run()
        val restart = Harness(h.root, id(700))
        restart.controller.prepare(restart.identity)
        restart.controller.searchRetained("B current text")
        restart.controller.openRetained(id(700))
        restart.run()
        assertEquals(null, restart.ui.retainedSelected)
        assertEquals(expected, restart.document.section.enrollments.single().standing)
    }

    @Test fun cursoredDenialFlagStopsAndEvictsSelectedB() = authority("permission_denied", 6)

    @Test fun exhaustedDenialFlagStillStopsAndEvictsSelectedB() = authority("permission_denied", 0)

    @Test fun cursoredProfileFlagRequiresReviewAndFencesSelectedB() = authority(
        "profile_changed",
        6
    )

    @Test fun exhaustedProfileFlagStillRequiresReviewAndFencesSelectedB() =
        authority("profile_changed", 0)

    @Test fun normalSnapshotRefreshAndCurrentCapacityRemainUsable() {
        val h = Harness(temp.newFolder(), id(700))
        h.begin()
        h.page(message(1, "B current text"))
        h.controller.searchOnline("needle", true)
        h.run()
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary), "tail", emptyList(), null).toWire())
        h.run()
        h.controller.loadMoreConversations()
        h.run()
        h.link.next("relay.conversations.list").fail("snapshot_required")
        h.run()
        val fresh = h.link.next("relay.conversations.list")
        assertEquals(null, fresh.params["cursor"])
        assertEquals("needle", fresh.params["query"]?.jsonPrimitive?.content)
        assertEquals("true", fresh.params["bookmarked"]?.jsonPrimitive?.content)
        fresh.reply(ConversationPage(listOf(h.summary), null, emptyList(), null).toWire())
        h.run()
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertTrue(h.ui.canSend)
        assertFalse(h.cache.absent(h.namespace, id(700)))
        var accepted = false
        h.controller.send("valid current B") { accepted = it }
        h.run()
        assertTrue(accepted)
        assertEquals(1, h.link.requests.count { it.method == "relay.turn.submit" })
    }
    private fun occupiedCommitFailure(refusal: StoreCommit, promoted: Boolean = false) {
        val h = occupied()
        h.controller.select(h.summary.conversationId.value)
        h.run()
        val current = h.link.next("relay.conversation.attach")
        val before = h.document
        val retainedBytes = bytes(h)
        h.fail = { doc ->
            if (doc.section.enrollments.single().selectionDeleted) {
                if (promoted) {
                    check(
                        h.disk.commit(doc, h.disk.currentSequence()) is StoreCommit.Committed
                    )
                }
                refusal
            } else {
                null
            }
        }
        current.fail("not_found")
        h.run()
        assertTrue(h.ui.unsaved)
        assertArrayEquals(retainedBytes, bytes(h))
        assertEquals(promoted, h.document.section.enrollments.single().selectionDeleted)
        assertEquals(before.section.evictions, h.document.section.evictions)
        assertEquals(before.section.emergencyDeletion, h.document.section.emergencyDeletion)
        assertEquals(before.section.intents, h.document.section.intents)
        assertEquals(before.snapshot.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(before.snapshot.captures.records, h.document.snapshot.captures.records)
        h.controller.searchRetained("text-1")
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        val sequence = h.disk.currentSequence()
        // A delayed old attach cannot resurrect the deleted selection.
        current.reply(h.binding())
        h.run()
        assertEquals(sequence, h.disk.currentSequence())
        assertEquals(null, h.ui.selected)
        if (promoted) {
            val restart = Harness(h.root)
            restart.controller.prepare(restart.identity)
            restart.controller.searchRetained("text-1")
            restart.controller.openRetained(h.summary.conversationId.value)
            restart.run()
            assertEquals(null, restart.ui.retainedSelected)
            assertTrue(restart.link.requests.isEmpty())
        }
        h.fail = { null }
        h.evictionFailure = false
        h.failingTarget = id(800) // E still fails; exact A must independently complete.
        h.controller.retryStorage()
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.cache.absent(h.namespace, id(700)))
        assertFalse(h.cache.absent(h.namespace, id(800)))
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(before.section.evictions, h.document.section.evictions)
        assertEquals(before.section.emergencyDeletion, h.document.section.emergencyDeletion)
        assertEquals(before.section.intents, h.document.section.intents)
        assertEquals(before.snapshot.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(before.snapshot.captures.records, h.document.snapshot.captures.records)
    }

    @Test fun fullOccupiedFailedMarkerWriteIsUnsavedAndRecoversWithoutErasingB() =
        occupiedCommitFailure(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun fullOccupiedRefusedMarkerWriteIsUnsavedAndRecoversWithoutErasingB() =
        occupiedCommitFailure(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun fullOccupiedStaleMarkerWriteIsUnsavedAndRecoversWithoutErasingB() =
        occupiedCommitFailure(StoreCommit.Stale)

    @Test fun fullOccupiedUncertainPromotedMarkerFencesOfflineAndRecovers() =
        occupiedCommitFailure(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true), true)

    @Test fun pendingCreateAndLateSuccessCannotReplaceTheDeletionMarker() {
        val h = occupied()
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").reply(h.binding())
        h.run()
        h.page(message(1))
        h.controller.create("already pending before deletion")
        h.run()
        val create = h.link.next("relay.conversation.create")
        val intent = h.document.section.enrollments.single().createIntent
        deleteSelected(h)
        create.reply(
            ConversationSummary(
                ConversationId.of(id(900))!!,
                "C",
                1,
                false,
                requireNotNull(h.info.serverTimeMs),
                true
            ).toWire()
        )
        h.run()
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(
            h.summary.conversationId,
            h.document.section.enrollments.single().selectedConversationId
        )
        assertEquals(intent, h.document.section.enrollments.single().createIntent)
        assertTrue(
            h.link.requests.none {
                it.method == "relay.conversation.attach" &&
                    it.params["conversation_id"]?.jsonPrimitive?.content == id(900)
            }
        )
        assertFalse(h.cache.absent(h.namespace, id(700)))
    }

    @Test fun unselectedRetirementAndCacheConsentCannotConsumeAnActiveSelectionRepresentation() {
        val h = occupied()
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").reply(h.binding())
        h.run()
        h.page(message(1))
        assertTrue(h.ui.canSend)
        // Deliberately free one immutable operation slot; no old obligation is dropped.
        h.controller.abandonMetadata(h.document.section.intents.last().requestId.value)
        h.run()
        val original = h.document
        val c =
            ConversationSummary(
                ConversationId.of(id(900))!!,
                "C",
                1,
                false,
                requireNotNull(h.info.serverTimeMs),
                true
            )
        put(h, id(900), "C retained")
        h.controller.refreshConversations()
        h.run()
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary, c), null, emptyList(), null).toWire())
        h.run()
        h.controller.reviewCache(id(900))
        h.run()
        val review = h.ui.cacheReviewConfirmation!!
        h.controller.confirmCacheReview(review)
        h.run()
        assertEquals(review, h.ui.cacheReviewConfirmation)
        assertTrue(h.ui.canSend)
        h.controller.editMetadata(id(900), "requested C", null)
        h.run()
        h.link.next("relay.conversation.update").fail("not_found")
        h.run()
        val retired = h.document.section.intents.last()
        assertEquals(
            dev.quantumink.hermesgadget.protocol.relay.session.MetadataStatus.RETIRED,
            retired.status
        )
        assertTrue("B/current selection remains admitted with a fixed reservation", h.ui.canSend)
        h.controller.abandonMetadata(retired.requestId.value)
        h.run()
        assertTrue(h.document.section.intents.contains(retired))
        assertEquals(original.section.evictions, h.document.section.evictions)
        assertEquals(original.section.emergencyDeletion, h.document.section.emergencyDeletion)
        deleteSelected(h)
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(original.snapshot.ledger.turns, h.document.snapshot.ledger.turns)
        assertEquals(original.snapshot.captures.records, h.document.snapshot.captures.records)
        h.controller.hide()
        h.run()
        val restart = Harness(h.root)
        restart.controller.prepare(restart.identity)
        for ((query, id) in listOf(
            "text-1" to h.summary.conversationId.value,
            "C retained" to id(900)
        )) {
            restart.controller.searchRetained(query)
            restart.controller.openRetained(id)
            restart.run()
            assertEquals(null, restart.ui.retainedSelected)
        }
        restart.controller.searchRetained("B retained")
        restart.controller.openRetained(id(700))
        restart.run()
        assertEquals(id(700), restart.ui.retainedSelected?.id)
    }

    @Test fun profileReviewCannotReplaceAnUnfinishedMarkerNamespace() {
        val h = occupied()
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").fail("not_found")
        h.run()
        val record = h.document.section.enrollments.single()
        val offered =
            Info(
                dev.quantumink.hermesgadget.protocol.relay.ProfileId.of(
                    "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d"
                )!!,
                h.info.grantGeneration,
                h.info.expiresAtMs,
                h.info.serverTimeMs,
                h.info.methods,
                h.info.limits
            )
        h.controller.onChannel(
            RelayChannelState.Ready(
                offered,
                (ServerClock.fix(offered, 5_000_000_000L) as ClockFix.Fixed).clock,
                2
            ),
            1
        )
        h.run()
        assertEquals(Standing.REVIEW_REQUIRED, h.document.section.enrollments.single().standing)
        h.controller.acceptReview()
        h.run()
        val held = h.document.section.enrollments.single()
        assertEquals(record.pinnedProfileId, held.pinnedProfileId)
        assertEquals(record.reviewEpoch, held.reviewEpoch)
        assertEquals(record.selectedConversationId, held.selectedConversationId)
        assertTrue(held.selectionDeleted)
        assertFalse(h.cache.absent(h.namespace, id(700)))
    }

    @Test fun staleCombinedAuthorityReplyCannotReplaceADeliberateQueryOrItsCurrentB() {
        val h = Harness(temp.newFolder(), id(700))
        h.begin()
        h.page(message(1, "B retained"))
        h.controller.searchOnline("old needle", true)
        h.run()
        h.link.next(
            "relay.conversations.list"
        ).reply(ConversationPage(listOf(h.summary), "old-tail", emptyList(), null).toWire())
        h.run()
        h.controller.loadMoreConversations()
        h.run()
        val obsolete = h.link.next("relay.conversations.list")
        h.controller.searchOnline("replacement", false)
        h.run()
        val fresh = h.link.requests.last { !it.answered && it.method == "relay.conversations.list" }
        val withdrawals = h.link.withdrawals
        obsolete.fail("permission_denied", true)
        h.run()
        assertEquals(Standing.ACTIVE, h.document.section.enrollments.single().standing)
        assertEquals(withdrawals, h.link.withdrawals)
        assertEquals("replacement", h.ui.onlineQuery)
        assertEquals(false, h.ui.bookmarkFilter)
        fresh.reply(ConversationPage(listOf(h.summary), null, emptyList(), null).toWire())
        h.run()
        h.controller.refreshConversations()
        h.run()
        val deliberate = h.link.next("relay.conversations.list")
        assertEquals(fresh.params, deliberate.params)
        deliberate.reply(ConversationPage(listOf(h.summary), null, emptyList(), null).toWire())
        h.run()
        assertTrue(h.ui.canSend)
        assertFalse(h.cache.absent(h.namespace, id(700)))
    }

    @Test fun acceptedCacheAllocationAndReattachCannotConsumeTheFixedSelectionByte() {
        var h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        put(h, id(700), "B retained")
        put(h, id(800), "E pending")
        val old = evictions(h).dropLast(1)
        val emergency = HistoryEviction(h.namespace, id(800), EvictionKind.DELETED)
        h =
            replace(
                h,
                ControllerSection(
                    h.document.section.enrollments,
                    evictions = old,
                    emergencyDeletion = emergency
                )
            )
        h.failingTarget = id(800)
        h.begin()
        h.page(message(1))
        val selected = h.document.section.enrollments.single().selectedConversationId
        var allocated = false
        h.fail = { doc ->
            if (doc.section.evictions.size == 64) allocated = true
            null
        }
        h.controller.reviewCache(id(64))
        h.run()
        val rendered = h.ui.cacheReviewConfirmation!!
        h.cacheFailure = true
        h.controller.confirmCacheReview(rendered)
        h.run()
        assertTrue(allocated)
        assertEquals(64, h.document.section.evictions.size)
        assertEquals(selected, h.document.section.enrollments.single().selectedConversationId)
        assertFalse(h.document.section.enrollments.single().selectionDeleted)
        h.cacheFailure = false
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").reply(h.binding())
        h.run()
        h.page(message(1))
        assertTrue(h.ui.canSend)
        h.evictionFailure = true
        deleteSelected(h)
        assertTrue(h.document.section.enrollments.single().selectionDeleted)
        assertEquals(64, h.document.section.evictions.size)
        assertEquals(emergency, h.document.section.emergencyDeletion)
        assertFalse(h.cache.absent(h.namespace, id(700)))
        h.controller.hide()
        h.run()
        val restart = Harness(h.root)
        restart.controller.prepare(restart.identity)
        restart.controller.searchRetained("text-1")
        restart.controller.openRetained(h.summary.conversationId.value)
        restart.run()
        assertEquals(null, restart.ui.retainedSelected)
    }
}
