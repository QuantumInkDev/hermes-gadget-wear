package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
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
import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayStore
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
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
class PhoneCanonicalHistoryTest {
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

    private inner class Harness(val root: File) {
        val owner = Owner()
        val link = Link()
        val log = ArrayList<String>()
        var cacheFailure = false
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
        val summary =
            decode(
                RelayCalls.createConversation(
                    RequestId.of(
                        creation["request"]!!.jsonObject["params"]!!.jsonObject["request_id"]!!
                            .jsonPrimitive.content
                    )!!
                ),
                result("relay.conversation.create")
            )
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:hg-public-label")
        val controller = RelayController(
            store,
            owner,
            { 5_000_000_000L },
            SecureRequestIdEntropy(),
            retainedHistory = phone.retained(),
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
            link.next("relay.conversation.attach").reply(result("relay.conversation.attach"))
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

    @Test fun completeSegmentsEnterTheRealCacheBeforeAggregateResumeAndRestartAsReadOnlyText() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1, "first ", 0, 2), cursor = "segment-cursor")
        assertTrue(h.cache.read(h.namespace).threads.flatMap { it.messages }.isEmpty())
        h.log.clear()
        h.page(message(1, "message", 1, 2), message(2, time = 100), message(3, time = 50))
        val view = h.cache.read(h.namespace).threads.single()
        assertEquals(listOf("first message", "text-2", "text-3"), view.messages.map { it.text })
        assertTrue(view.complete)
        assertEquals(3L, h.document.snapshot.histories.single().committedOrdinal)
        assertTrue(
            h.log.indexOf("cache-promoted") < h.log.indexOfFirst {
                it.startsWith("aggregate:3:")
            }
        )
        h.controller.hide()
        h.run()
        val reopened = Harness(h.root)
        reopened.controller.prepare(reopened.identity)
        reopened.run()
        reopened.controller.openRetained(reopened.summary.conversationId.value)
        reopened.run()
        assertEquals(3, reopened.ui.retainedSelected?.messages?.size)
        assertFalse(reopened.ui.canSend)
        assertEquals(InputRoute.UNAVAILABLE, PhoneRelay.typedRoute(reopened.ui))
        assertEquals(InputRoute.UNAVAILABLE, PhoneRelay.voiceRoute(reopened.ui))
        assertTrue(reopened.link.requests.isEmpty())
    }

    @Test fun replacementListingTraversesItsOwnPredecessorsAndRejectsAnOlderUnreadTail() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1), message(2), message(3))
        fun restart() {
            h.controller.select(h.summary.conversationId.value)
            h.run()
            h.link.next("relay.conversation.attach").reply(h.result("relay.conversation.attach"))
            h.run()
        }
        restart()
        h.page(message(1), cursor = "B-tail")
        assertFalse(h.cache.read(h.namespace).threads.single().complete)
        // Actual controller supplies After(m1), not the unread m2 from A. A skipped m2 conflicts.
        h.page(message(3))
        assertEquals(HistoryProblem.SEQUENCE_CONFLICT, h.ui.retainedProblem)
        assertFalse(h.cache.read(h.namespace).threads.single().complete)
        restart()
        h.page(message(1), cursor = "C-tail")
        h.page(message(2), message(3))
        assertTrue(h.cache.read(h.namespace).threads.single().complete)
    }

    @Test fun lostMetadataReplyInvalidatesTheOldHistoryCursorBeforeItsLatePageArrives() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1), cursor = "old-tail")
        val late = h.link.next("relay.history.page")
        h.controller.editMetadata(h.summary.conversationId.value, "Requested", null)
        h.run()
        assertTrue(h.link.requests.any { it.method == "relay.conversation.update" })
        assertTrue(h.document.snapshot.histories.isEmpty())
        late.reply(HistoryPage(listOf(message(2)), null).toWire())
        h.run()
        val view = h.cache.read(h.namespace).threads.single()
        assertEquals(1, view.messages.size)
        assertFalse(view.complete)
        assertTrue(h.ui.historyIncomplete)
    }

    @Test fun retainedSelectionRefusesTypedSendUntilTheAuthenticatedConversationViewIsRestored() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertFalse(h.ui.canSend)
        var accepted = true
        h.controller.send("must stay read only") { accepted = it }
        h.run()
        assertFalse(accepted)
        assertTrue(h.link.requests.none { it.method == "relay.turn.submit" })
        h.controller.closeRetained()
        h.run()
        assertTrue(h.ui.canSend)
        h.controller.send("deliberate continuation") { accepted = it }
        h.run()
        assertTrue(accepted)
        val sent = h.link.next("relay.turn.submit")
        assertEquals(
            h.result("relay.conversation.attach")["binding_id"]!!.jsonPrimitive.content,
            sent.params["binding_id"]!!.jsonPrimitive.content
        )
    }

    @Test fun disablingRelayClearsTheRetainedViewAndRestoresStockInputRoutes() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.openRetained(h.summary.conversationId.value)
        h.run()
        assertEquals(InputRoute.UNAVAILABLE, PhoneRelay.typedRoute(h.ui))
        h.controller.setEnabled(false)
        h.run()
        assertEquals(null, h.ui.retainedSelected)
        assertTrue(h.ui.retained.isEmpty())
        assertEquals(InputRoute.STOCK, PhoneRelay.typedRoute(h.ui))
        assertEquals(InputRoute.STOCK, PhoneRelay.voiceRoute(h.ui))
    }

    @Test fun failedAggregateCommitAfterCacheWriteDoesNotAdvanceDurableResume() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.fail =
            {
                if (it.snapshot.histories.any { history ->
                        history.committedOrdinal > 0
                    }
                ) {
                    StoreCommit.Stale
                } else {
                    null
                }
            }
        h.page(message(1))
        assertEquals(1, h.cache.read(h.namespace).threads.single().messages.size)
        assertTrue(h.document.snapshot.histories.all { it.committedOrdinal == 0L })
        assertTrue(h.ui.historyIncomplete)
        assertTrue(h.link.requests.none { it.method == "relay.turn.submit" })
    }

    @Test fun cacheFailureCannotAdvanceResumeAndLatePageCannotResurrectText() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.cacheFailure = true
        h.page(message(1))
        assertEquals(HistoryProblem.KEY_UNAVAILABLE, h.ui.retainedProblem)
        assertTrue(h.document.snapshot.histories.all { it.committedOrdinal == 0L })
        assertTrue(h.link.requests.none { it.method == "relay.turn.submit" })
        val replacement = Harness(temp.newFolder())
        replacement.begin()
        val old = replacement.link.next("relay.history.page")
        replacement.controller.hide()
        replacement.controller.prepare(
            EnrollmentIdentity("wss://other.invalid/gadget", "phone:hg-other")
        )
        replacement.run()
        old.reply(HistoryPage(listOf(message(1)), null).toWire())
        replacement.run()
        assertTrue(replacement.ui.retained.isEmpty())
        assertTrue(replacement.ui.transcript.isEmpty())
        assertTrue(replacement.cache.read(replacement.namespace).threads.isEmpty())
    }

    @Test fun invalidSegmentNeverBecomesAFalseBeginningButAValidRestartTraversesTheWholeListing() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1, "orphan tail", 1, 2))
        assertTrue(h.cache.read(h.namespace).threads.isEmpty())
        assertTrue(h.ui.historyIncomplete)
        h.controller.select(h.summary.conversationId.value)
        h.run()
        h.link.next("relay.conversation.attach").reply(h.result("relay.conversation.attach"))
        h.run()
        h.page(message(1), cursor = "tail")
        assertFalse(h.cache.read(h.namespace).threads.single().complete)
        h.page(message(2), message(3))
        assertEquals(
            listOf("text-1", "text-2", "text-3"),
            h.cache.read(h.namespace).threads.single().messages.map {
                it.text
            }
        )
        assertTrue(h.cache.read(h.namespace).threads.single().complete)
    }

    @Test fun denialPersistsEvictionBeforeRemovalAndFailedClearRepeatsSafelyAfterRestart() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.editMetadata(h.summary.conversationId.value, "name", null)
        h.run()
        val update = h.link.next("relay.conversation.update")
        h.fail = { doc -> if (doc.section.evictions.isNotEmpty()) StoreCommit.Stale else null }
        update.fail("permission_denied")
        h.run()
        assertFalse(h.cache.absent(h.namespace, null))
        assertTrue(h.ui.contentEvictionPending)
        var owedWasCommitted = false
        h.fail = { doc ->
            if (doc.section.evictions.isNotEmpty()) owedWasCommitted = true
            if (owedWasCommitted &&
                doc.section.evictions.isEmpty()
            ) {
                StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
            } else {
                null
            }
        }
        h.log.clear()
        h.controller.retryStorage()
        h.run()
        assertTrue(owedWasCommitted)
        assertTrue(h.cache.absent(h.namespace, null))
        assertTrue(h.ui.contentEvictionPending)
        assertTrue(h.document.section.evictions.isNotEmpty())
        val firstOwed = h.log.indexOfFirst { it.startsWith("aggregate:") && it.endsWith(":1") }
        assertTrue(firstOwed >= 0 && firstOwed < h.log.indexOf("cache-promoted"))
        h.controller.hide()
        h.run()
        val restarted = Harness(h.root)
        restarted.controller.prepare(restarted.identity)
        restarted.controller.attachTransport(restarted.link)
        restarted.controller.onChannel(
            RelayChannelState.Ready(
                restarted.info,
                (ServerClock.fix(restarted.info, 5_000_000_000L) as ClockFix.Fixed).clock,
                1
            ),
            1
        )
        restarted.run()
        assertFalse(restarted.ui.contentEvictionPending)
        assertTrue(restarted.document.section.evictions.isEmpty())
        assertTrue(restarted.cache.absent(restarted.namespace, null))
    }

    @Test fun staleReviewedEraseLeavesChangedBytesAndDoesNotBecomeAutomaticRemovalAuthority() {
        val h = Harness(temp.newFolder())
        h.begin()
        h.page(message(1))
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        val review = h.ui.cacheReview!!
        val snapshot = h.cache.read(h.namespace)
        h.cache.setRetention(h.namespace, 365, snapshot.revision)
        val before = File(h.root, "history/history.v1").readBytes()
        h.controller.confirmCacheReview()
        h.run()
        assertEquals(HistoryProblem.STALE_REVIEW, h.ui.retainedProblem)
        h.controller.retryStorage()
        h.run()
        assertArrayEquals(before, File(h.root, "history/history.v1").readBytes())
        assertTrue(h.ui.contentEvictionPending)
        assertTrue(h.cache.read(h.namespace).threads.single().messages.isNotEmpty())
        assertTrue(review.recordHash.isNotEmpty())
        h.controller.reviewCache(h.summary.conversationId.value)
        h.run()
        h.controller.confirmCacheReview()
        h.run()
        assertTrue(h.cache.absent(h.namespace, h.summary.conversationId.value))
        assertFalse(h.ui.contentEvictionPending)
    }
}
