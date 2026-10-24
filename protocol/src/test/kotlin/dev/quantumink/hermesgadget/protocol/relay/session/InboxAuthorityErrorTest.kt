package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.ExistingConversation
import dev.quantumink.hermesgadget.protocol.relay.ExistingConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ExistingTaskPage
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayConnectionHarness
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correlated production getters/answer and actual queued DirectConnection SEND, no TCP. */
class InboxAuthorityErrorTest {
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        var beforeCommit: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        override fun load(): InboxSnapshot {
            beforeRead()
            return InboxCodec.decode(InboxCodec.encode(state))
        }
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            beforeCommit()
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class Owner(
        val existing: Boolean = false,
        val observeExisting: Boolean = false,
        val infoTtlMs: Long? = null
    ) : AutoCloseable {
        val net = RelayConnectionHarness(RelaySocketFixture())
        var afterDelivered: (String) -> Unit = {}

        // Observation only: all calls, permits and invalidation still use the actual transport.
        val observedTransport = object : RelayTransport by net.transport {
            override fun <R : Any> callGuarded(
                correlation: CorrelationId,
                call: RelayCall<R>,
                timeoutMs: Long,
                permit: () -> Boolean,
                handler: (RelayCallResult<R>) -> Unit
            ) {
                net.transport.callGuarded(correlation, call, timeoutMs, permit) { result ->
                    handler(result)
                    afterDelivered(call.method.wire)
                }
            }
        }
        val scheduler = ManualScheduler()
        val memory = MemoryStore()
        var beforePersist: () -> Unit = {}
        val store = object : RelayStore {
            override fun load() = memory.load()
            override fun currentSequence() = memory.currentSequence()
            override fun commit(document: RelayDocument, base: Long): StoreCommit {
                beforePersist()
                return memory.commit(document, base)
            }
        }
        val disk = Disk()
        val repository = InboxRepository(disk)
        val identity = EnrollmentIdentity("wss://example.invalid/gadget", "phone:input-authority")
        fun resource(path: String) = Json.parseToJsonElement(
            javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
        ).jsonObject
        val steps = resource("/task5-inbox/needs-input-answer/session.json")["steps"]!!
            .jsonArray.map { it.jsonObject }.filter { it["device"] == JsonPrimitive("watch") }
        fun method(s: JsonObject, step: Boolean = true) =
            (if (step) s["request"]!!.jsonObject else s)["method"]!!.jsonPrimitive.content
        fun result(s: JsonObject) = s["response"]!!.jsonObject["result"]!!.jsonObject
        val capturedInfo = Info.decode(result(steps.first { method(it) == "relay.info" }))

        // Controlled shorter Info lease; the frozen producer resource remains exact.
        val admittedInfo = if (infoTtlMs == null) {
            capturedInfo
        } else {
            Info.decode(
                JsonObject(
                    capturedInfo.toWire() + (
                        "expires_at_ms" to
                            JsonPrimitive(requireNotNull(capturedInfo.serverTimeMs) + infoTtlMs)
                        )
                )
            )
        }

        // Synthetic authority view only for the existing sibling; raw resources stay exact.
        val info = if (!existing && !observeExisting) {
            admittedInfo
        } else {
            Info.decode(
                JsonObject(
                    admittedInfo.toWire() + (
                        "methods" to JsonArray(
                            (
                                admittedInfo.methods + listOf(
                                    "relay.existing.attach",
                                    "relay.existing.prompt.get",
                                    "relay.existing.tasks.list",
                                    "relay.existing.list",
                                    "relay.existing.history.page"
                                )
                                ).distinct().map(::JsonPrimitive)
                        )
                        )
                )
            )
        }
        val namespace = HistoryNamespace.from(Seeds.record(identity, pinned = info.profileId))!!
        val prompt = result(
            steps.last {
                method(it) == "relay.task.get" && result(it)["prompt"] is JsonObject
            }
        )
        val existingSteps = resource(
            "/task5-inbox/existing-current-superseded/session.json"
        )["steps"]!!
            .jsonArray.map { it.jsonObject }
        val existingTask = ExistingTaskPage.decode(
            result(
                existingSteps.first {
                    method(it) == "relay.existing.tasks.list" &&
                        result(it)["items"]!!.jsonArray.isNotEmpty()
                }
            )
        ).items.first()
        val handle: String
        var ui: RelayUiState? = null
        var publications = 0
        val ready: RelayChannelState.Ready
        val controller = RelayController(
            store,
            scheduler,
            net::nanosNow,
            Dto.scriptedEntropy,
            inbox = repository,
            localMillis = { Dto.SERVER_TIME }
        ) {
            ui = it
            publications++
        }
        val peer get() = net.fixture.latest
        init {
            memory.commit(
                Seeds.empty(
                    ControllerSection(
                        listOf(
                            Seeds.record(identity, pinned = info.profileId)
                        )
                    )
                ),
                0
            )
            repository.configure(
                InboxProfile(
                    namespace,
                    "actual",
                    "Actual",
                    "Box",
                    existingTasks = existing || observeExisting,
                    existingAvailable = existing || observeExisting,
                    existingHistory = observeExisting,
                    existingHistoryAvailable = observeExisting
                )
            )
            if (existing) {
                repository.project(namespace, existing = listOf(existingTask), at = Dto.SERVER_TIME)
                handle = disk.load().tasks.single().handle
            } else {
                if (observeExisting) {
                    repository.project(
                        namespace,
                        existing = listOf(existingTask),
                        at = Dto.SERVER_TIME
                    )
                }
                val page = OutboxPage.decode(
                    result(
                        steps.first {
                            method(it) == "relay.outbox.read" &&
                                result(it)["items"]!!.jsonArray.any { raw ->
                                    raw.jsonObject["payload"]!!.jsonObject["task"]?.jsonObject?.get(
                                        "state"
                                    ) ==
                                        JsonPrimitive("needs_input")
                                }
                        }
                    )
                )
                repository.accept(namespace, page.items, Dto.SERVER_TIME)
                handle = disk.load().copies.first {
                    (it.item.payload as? OutboxPayload.TaskChange.Relay)?.task?.state ==
                        TaskState.NEEDS_INPUT
                }.handle
            }
            controller.prepare(identity)
            controller.attachTransport(observedTransport)
            run()
            net.connection.connect()
            net.awaitConnected()
            reply(next("relay.info"), info.toWire())
            ready = net.awaitState<RelayChannelState.Ready>()
            controller.onChannel(ready, 1)
            reply(next("relay.conversations.list"), Dto.page().toWire())
            run()
        }
        fun run() = scheduler.runAll()
        fun await(test: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < end) {
                run()
                if (test()) return
                Thread.sleep(5)
            }
            error("Production input owner condition not reached")
        }
        fun next(method: String): JsonObject {
            await { peer.relayRequests.isNotEmpty() }
            return peer.nextRequest().also { assertEquals(method, this.method(it, false)) }
        }
        fun reply(request: JsonObject, value: JsonObject) =
            peer.reply(request["id"]!!.jsonPrimitive.content, value)
        fun attach() {
            controller.continueInbox(handle)
            run()
            if (existing) {
                val vector = resource("/relay-v1/relay-existing-attach.json")["cases"]!!.jsonArray
                    .map { it.jsonObject }.first {
                        it["response"]!!.jsonObject["result"]?.jsonObject?.get("can_continue") ==
                            JsonPrimitive(true)
                    }
                val binding = JsonObject(
                    result(vector) +
                        ("conversation_id" to JsonPrimitive(existingTask.conversationId.value))
                )
                reply(next("relay.existing.attach"), binding)
                // A transport FIFO barrier settles the binding callback before issuing its getter.
                val barrier = CountDownLatch(1)
                net.transport.call(
                    net.transport.correlation(),
                    RelayCalls.conversations(),
                    15_000
                ) {
                    barrier.countDown()
                }
                reply(next("relay.conversations.list"), Dto.page().toWire())
                check(barrier.await(5, TimeUnit.SECONDS))
                run()
            } else {
                reply(
                    next("relay.conversation.attach"),
                    result(
                        steps.last {
                            method(it) == "relay.conversation.attach"
                        }
                    )
                )
                reply(
                    next("relay.history.page"),
                    result(
                        steps.first {
                            method(it) ==
                                "relay.history.page"
                        }
                    )
                )
                reply(
                    next("relay.tasks.list"),
                    result(
                        steps.first {
                            method(it) ==
                                "relay.tasks.list"
                        }
                    )
                )
            }
        }
        fun getter(done: (InboxInput?) -> Unit): JsonObject {
            controller.getInboxInput(handle, done)
            return next(if (existing) "relay.existing.prompt.get" else "relay.task.get")
        }
        fun fillAndQueue(): String {
            while (peer.relayTexts.size < 15) {
                val done = CountDownLatch(1)
                net.transport.call(
                    net.transport.correlation(),
                    RelayCalls.conversations(),
                    15_000
                ) {
                    done.countDown()
                }
                reply(next("relay.conversations.list"), Dto.page().toWire())
                check(done.await(5, TimeUnit.SECONDS))
            }
            val queued = net.transport.correlation()
            net.transport.call(queued, RelayCalls.conversations(), 15_000) {}
            assertFalse(sent(queued.value))
            return queued.value
        }
        fun sent(id: String) = peer.relayTexts.any {
            Json.parseToJsonElement(it).jsonObject["id"] == JsonPrimitive(id)
        }
        fun release() {
            net.advance(1000)
            val barrier = net.transport.correlation()
            net.transport.call(barrier, RelayCalls.conversations(), 15_000) {}
            await { sent(barrier.value) }
        }
        fun fail(request: JsonObject, code: String) {
            peer.deliver(
                buildJsonObject {
                    put("type", "relay.response")
                    put("v", 1)
                    put("id", request["id"]!!)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", code)
                            put("snapshot_required", true)
                        }
                    )
                }
            )
            run()
        }
        override fun close() = net.close()
    }

    private fun denial(code: String, existing: Boolean = false, answer: Boolean = false) {
        Owner(existing).use { h ->
            var finished = false
            var callbackStanding: RelayPhase? = null
            val request = if (answer) {
                h.attach()
                var issued: InboxInput? = null
                val get = h.getter { issued = it }
                h.reply(get, h.prompt)
                h.await { issued != null }
                assertTrue(issued!!.canAnswer)
                h.controller.answerInboxInput(issued!!, PromptAnswer.Choice("Continue")) {
                    finished = true
                    callbackStanding = h.ui!!.phase
                    assertFalse(it)
                }
                h.next("relay.prompt.respond")
            } else {
                if (existing) h.attach()
                h.getter {
                    finished = true
                    callbackStanding = h.ui!!.phase
                    assertNull(it)
                }
            }
            val queued = h.fillAndQueue()
            var checkedBeforePersistence = false
            h.beforePersist = {
                h.beforePersist = {}
                h.release()
                assertFalse("Transport withdrawn before fallible persistence", h.sent(queued))
                checkedBeforePersistence = true
            }
            h.memory.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true)
            h.fail(request, code)
            h.await { finished }
            h.release()
            assertFalse(
                "Correlated authority denial must withdraw actual queued SEND",
                h.sent(queued)
            )
            assertTrue(checkedBeforePersistence)
            val standing = if (code ==
                "permission_denied"
            ) {
                RelayPhase.STOPPED
            } else {
                RelayPhase.REVIEW_REQUIRED
            }
            assertEquals(standing, callbackStanding)
            assertEquals(standing, h.ui!!.phase)
            assertTrue(h.ui!!.unsaved)
        }
    }

    @Test fun canonicalPermissionBeforeQueuedSendAndUi() = denial("permission_denied")

    @Test fun canonicalProfileBeforeQueuedSendAndUi() = denial("profile_changed")

    @Test fun existingPermissionBeforeQueuedSendAndUi() = denial(
        "permission_denied",
        existing = true
    )

    @Test fun existingProfileBeforeQueuedSendAndUi() = denial("profile_changed", existing = true)

    @Test fun answerPermissionBeforeQueuedSendAndUi() = denial("permission_denied", answer = true)

    @Test fun answerProfileBeforeQueuedSendAndUi() = denial("profile_changed", answer = true)

    @Test fun existingNotFoundRetainsScopeAndOtherTransport() = targetUnavailable("not_found")

    @Test fun temporaryExistingUnavailabilityRetainsScopeAndOtherTransport() =
        targetUnavailable("temporarily_unavailable")
    private fun targetUnavailable(code: String) {
        Owner(existing = true).use { h ->
            h.attach()
            var finished = false
            val request = h.getter {
                assertNull(it)
                finished = true
            }
            val queued = h.fillAndQueue()
            h.fail(request, code)
            h.await { finished }
            h.release()
            assertTrue(h.sent(queued))
            assertFalse(h.ui!!.phase in setOf(RelayPhase.STOPPED, RelayPhase.REVIEW_REQUIRED))
            assertTrue(h.disk.load().tasks.isNotEmpty())
            assertEquals(if (code == "not_found") 1 else 0, h.disk.load().unavailable.size)
        }
    }

    @Test fun currentExistingGetterRequiresFreshEligiblePromptAndDoesNotAnswerAutomatically() {
        Owner(existing = true).use { h ->
            h.attach()
            var issued: InboxInput? = null
            val request = h.getter { issued = it }
            val prompt = JsonObject(
                h.prompt["prompt"]!!.jsonObject +
                    ("task_id" to JsonPrimitive(h.existingTask.taskId.value))
            )
            h.reply(request, buildJsonObject { put("prompt", prompt) })
            h.await { issued != null }
            assertTrue(issued!!.canAnswer)
            assertFalse(
                h.peer.relayTexts.any {
                    Json.parseToJsonElement(it).jsonObject["method"] ==
                        JsonPrimitive("relay.prompt.respond")
                }
            )
        }
    }

    private fun Owner.observer(method: String): JsonObject {
        controller.refreshInbox()
        reply(next("relay.outbox.read"), OutboxPage(emptyList(), 0).toWire())
        reply(next("relay.tasks.list"), TaskPage(emptyList(), null).toWire())
        reply(next("relay.conversations.list"), Dto.page().toWire())
        val listing = next("relay.existing.list")
        if (method == "relay.existing.list") return listing
        reply(
            listing,
            ExistingConversationPage(
                listOf(
                    ExistingConversation(
                        existingTask.conversationId,
                        "Controlled existing target",
                        Dto.SERVER_TIME,
                        existingTask.source,
                        false
                    )
                ),
                null
            ).toWire()
        )
        val tasks = next("relay.existing.tasks.list")
        if (method == "relay.existing.tasks.list") return tasks
        reply(tasks, ExistingTaskPage(listOf(existingTask), null).toWire())
        return next("relay.existing.history.page").also {
            assertEquals(method, this.method(it, false))
        }
    }

    private fun Owner.queueTyped(): Int {
        while (peer.relayTexts.size < 15) {
            val barrier = CountDownLatch(1)
            net.transport.call(
                net.transport.correlation(),
                RelayCalls.conversations(),
                15_000
            ) { barrier.countDown() }
            reply(next("relay.conversations.list"), Dto.page().toWire())
            check(barrier.await(5, TimeUnit.SECONDS))
        }
        val before = peer.relayTexts.count {
            Json.parseToJsonElement(it).jsonObject["method"] == JsonPrimitive("relay.turn.submit")
        }
        var accepted = false
        controller.send("Deliberate queued observer control") { accepted = it }
        run()
        assertTrue("Fixture requires an actually admitted production typed send", accepted)
        assertEquals(before, typedCount())
        return before
    }

    private fun Owner.typedCount() = peer.relayTexts.count {
        Json.parseToJsonElement(it).jsonObject["method"] == JsonPrimitive("relay.turn.submit")
    }

    private fun observerDenial(method: String, code: String) {
        Owner(observeExisting = true).use { h ->
            h.attach()
            val request = h.observer(method)
            val before = h.queueTyped()
            var checked = false
            val firstWrite: () -> Unit = {
                h.beforePersist = {}
                h.disk.beforeCommit = {}
                h.release()
                assertEquals(
                    "Authority fence precedes the first fallible observer persistence",
                    before,
                    h.typedCount()
                )
                checked = true
            }
            h.beforePersist = firstWrite
            h.disk.beforeCommit = firstWrite
            h.memory.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true)
            val publications = h.publications
            h.fail(request, code)
            h.await { h.publications > publications }
            h.release()
            assertEquals("Denied observer cannot release queued typed SEND", before, h.typedCount())
            assertTrue("The first persistence boundary was exercised", checked)
            assertEquals(
                if (code == "permission_denied") RelayPhase.STOPPED else RelayPhase.REVIEW_REQUIRED,
                h.ui!!.phase
            )
            assertTrue(h.ui!!.unsaved)
        }
    }

    @Test fun observerListPermissionBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.list", "permission_denied")

    @Test fun observerTasksPermissionBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.tasks.list", "permission_denied")

    @Test fun observerHistoryPermissionBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.history.page", "permission_denied")

    @Test fun observerListProfilePriorityBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.list", "profile_changed")

    @Test fun observerTasksProfilePriorityBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.tasks.list", "profile_changed")

    @Test fun observerHistoryProfilePriorityBeforeFirstPersistenceAndTypedSend() =
        observerDenial("relay.existing.history.page", "profile_changed")

    private fun continueDenial(code: String) {
        Owner(existing = true).use { h ->
            h.controller.continueInbox(h.handle)
            val request = h.next("relay.existing.attach")
            // Continue has ALREADY ended its prior session. No old typed-queue claim is made.
            val queued = h.fillAndQueue()
            var wrote = false
            h.beforePersist = {
                h.beforePersist = {}
                h.release()
                assertFalse(
                    "Current post-Continue traffic is fenced before persistence",
                    h.sent(queued)
                )
                wrote = true
            }
            h.memory.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true)
            val publications = h.publications
            h.fail(request, code)
            h.await { h.publications > publications }
            assertEquals(
                "The actual Continue denial changes current standing before publication",
                if (code == "permission_denied") RelayPhase.STOPPED else RelayPhase.REVIEW_REQUIRED,
                h.ui!!.phase
            )
            assertTrue(wrote)
            assertTrue(h.ui!!.unsaved)
        }
    }

    @Test fun deliberateExistingContinuePermissionRetainsHighestPriority() =
        continueDenial("permission_denied")

    @Test fun deliberateExistingContinueProfileRetainsHighestPriority() =
        continueDenial("profile_changed")

    private fun observerUnavailable(method: String, code: String) {
        Owner(observeExisting = true).use { h ->
            h.attach()
            val request = h.observer(method)
            val before = h.queueTyped()
            val publications = h.publications
            h.fail(request, code)
            h.await { h.publications > publications }
            h.release()
            assertEquals(before + 1, h.typedCount())
            assertFalse(h.ui!!.phase in setOf(RelayPhase.STOPPED, RelayPhase.REVIEW_REQUIRED))
            assertTrue(h.disk.load().copies.isNotEmpty())
            assertEquals(if (code == "not_found") 1 else 0, h.disk.load().unavailable.size)
        }
    }

    @Test fun observerTasksExactNotFoundRetainsOtherTypedAuthority() =
        observerUnavailable("relay.existing.tasks.list", "not_found")

    @Test fun observerHistoryExactNotFoundRetainsOtherTypedAuthority() =
        observerUnavailable("relay.existing.history.page", "not_found")

    @Test fun observerListTemporaryFailureRetainsOtherTypedAuthority() =
        observerUnavailable("relay.existing.list", "temporarily_unavailable")

    @Test fun observerTasksTemporaryFailureRetainsOtherTypedAuthority() =
        observerUnavailable("relay.existing.tasks.list", "temporarily_unavailable")

    @Test fun observerHistoryTemporaryFailureRetainsOtherTypedAuthority() =
        observerUnavailable("relay.existing.history.page", "temporarily_unavailable")

    private fun Owner.fillBudget() {
        while (peer.relayTexts.size < 15) {
            val barrier = CountDownLatch(1)
            net.transport.call(
                net.transport.correlation(),
                RelayCalls.conversations(),
                15_000
            ) { barrier.countDown() }
            reply(next("relay.conversations.list"), Dto.page().toWire())
            check(barrier.await(5, TimeUnit.SECONDS))
        }
    }

    private fun Owner.promptResult(ttlMs: Long? = 500, unknown: Boolean = false): JsonObject {
        val projected = JsonObject(
            prompt["prompt"]!!.jsonObject + mapOf(
                "expires_at_ms" to (
                    ttlMs?.let { JsonPrimitive(requireNotNull(info.serverTimeMs) + it) } ?: JsonNull
                    ),
                "deadline" to JsonPrimitive(
                    if (unknown) {
                        "unknown"
                    } else if (ttlMs == null) {
                        "unlimited"
                    } else {
                        "bounded"
                    }
                ),
                "reason" to if (unknown) JsonPrimitive("deadline_unknown") else JsonNull,
                "actionable" to JsonPrimitive(!unknown),
                "free_text" to JsonPrimitive(false)
            )
        )
        return if (existing) {
            JsonObject(
                mapOf(
                    "prompt" to JsonObject(
                        projected + ("task_id" to JsonPrimitive(existingTask.taskId.value))
                    )
                )
            )
        } else {
            JsonObject(prompt + ("prompt" to projected))
        }
    }

    private fun Owner.input(ttlMs: Long? = 500): InboxInput {
        attach()
        var issued: InboxInput? = null
        val request = getter { issued = it }
        reply(request, promptResult(ttlMs))
        await { issued != null }
        assertTrue("Fixture requires the actual fresh bound answer token", issued!!.canAnswer)
        return issued!!
    }

    private fun Owner.count(method: String) = peer.relayTexts.count {
        Json.parseToJsonElement(it).jsonObject["method"] == JsonPrimitive(method)
    }

    private fun answerExpiry(infoExpiry: Boolean, persistence: Boolean, existing: Boolean = false) {
        Owner(existing = existing, infoTtlMs = if (infoExpiry) 500 else null).use { h ->
            val input = h.input(if (infoExpiry) 60_000 else 500)
            val generation = h.net.transport.generation
            val ready = h.ready
            h.fillBudget()
            var crossedPersistence = false
            if (persistence) {
                h.beforePersist = {
                    h.beforePersist = {}
                    h.net.advance(1000)
                    crossedPersistence = true
                }
            }
            var completed = 0
            var accepted = true
            h.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) {
                completed++
                accepted = it
            }
            h.run()
            h.release()
            assertEquals(
                "Expired input authority cannot reach actual final SEND",
                0,
                h.count("relay.prompt.respond")
            )
            h.await { completed == 1 }
            assertFalse(accepted)
            assertEquals(persistence, crossedPersistence)
            assertEquals(generation, h.net.transport.generation)
            assertTrue(ready === h.ready)
            var reused = true
            h.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) { reused = it }
            h.run()
            assertFalse("The expired one-shot action stays consumed", reused)
            assertEquals(0, h.count("relay.prompt.respond"))
            assertEquals(
                1,
                h.count(if (existing) "relay.existing.prompt.get" else "relay.task.get")
            )
        }
    }

    @Test fun answerPromptExpiryAtActualPacedSendWithUnchangedAuthorityGenerations() =
        answerExpiry(infoExpiry = false, persistence = false)

    @Test fun answerInfoExpiryAtActualPacedSendWithUnchangedAuthorityGenerations() =
        answerExpiry(infoExpiry = true, persistence = false)

    @Test fun answerPromptExpiryAcrossAggregatePersistenceRefusesFinalSend() =
        answerExpiry(infoExpiry = false, persistence = true)

    @Test fun answerInfoExpiryAcrossAggregatePersistenceRefusesFinalSend() =
        answerExpiry(infoExpiry = true, persistence = true)

    @Test fun getterInfoExpiryAtActualPacedSendCompletesNullWithoutAutomaticReplacement() {
        Owner(infoTtlMs = 500).use { h ->
            h.fillBudget()
            val generation = h.net.transport.generation
            val ready = h.ready
            var completed = 0
            var issued: InboxInput? = null
            h.controller.getInboxInput(h.handle) {
                completed++
                issued = it
            }
            h.run()
            h.release()
            assertEquals(
                "Expired getter authority cannot reach final SEND",
                0,
                h.count("relay.task.get")
            )
            h.await { completed == 1 }
            assertNull(issued)
            assertEquals(generation, h.net.transport.generation)
            assertTrue(ready === h.ready)
            assertEquals(0, h.count("relay.prompt.respond"))
        }
    }

    @Test fun getterRefusesAlreadyExpiredInfoBeforeRequestAdmission() {
        Owner(infoTtlMs = 500).use { h ->
            h.net.advance(1000)
            var completed = false
            h.controller.getInboxInput(h.handle) {
                assertNull(it)
                completed = true
            }
            h.run()
            h.release()
            assertEquals("Getter admission needs current Info time", 0, h.count("relay.task.get"))
            assertTrue(completed)
        }
    }

    @Test fun getterAcceptanceRechecksExactInfoAfterFallibleRouteRead() {
        Owner(infoTtlMs = 500).use { h ->
            var completed = false
            var issued: InboxInput? = null
            val request = h.getter {
                issued = it
                completed = true
            }
            var crossedRead = false
            h.disk.beforeRead = {
                h.disk.beforeRead = {}
                h.net.advance(1000)
                crossedRead = true
            }
            h.reply(request, h.promptResult(60_000))
            h.await { completed }
            assertTrue(crossedRead)
            assertNull("Expiry during acceptance IO cannot issue an input action", issued)
            assertEquals(1, h.count("relay.task.get"))
            assertEquals(0, h.count("relay.prompt.respond"))
        }
    }

    private fun currentAnswer(ttlMs: Long?) {
        Owner().use { h ->
            val input = h.input(ttlMs)
            var completed = false
            var accepted = false
            h.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) {
                completed = true
                accepted = it
            }
            val request = h.next("relay.prompt.respond")
            val actual = h.steps.single { h.method(it) == "relay.prompt.respond" }
            h.reply(request, h.result(actual))
            h.await { completed }
            assertTrue(accepted)
            assertEquals(1, h.count("relay.prompt.respond"))
            assertEquals(1, h.count("relay.task.get"))
        }
    }

    @Test fun separateFreshBoundedGetterAndDeliberateAnswerMakeValidProgress() = currentAnswer(
        60_000
    )

    @Test fun registeredUnlimitedDeadlineStillUsesCurrentInfoAndDeliberateAnswer() = currentAnswer(
        null
    )

    @Test fun registeredUnknownDeadlineRemainsDisplayOnly() {
        Owner().use { h ->
            var completed = false
            var issued: InboxInput? = null
            val request = h.getter {
                issued = it
                completed = true
            }
            h.reply(request, h.promptResult(null, unknown = true))
            h.await { completed }
            assertNull(issued)
            assertEquals(0, h.count("relay.prompt.respond"))
        }
    }

    @Test fun answerCompletionUsesLifetimeFenceAfterValidSendAndCorrelatedReply() {
        Owner().use { h ->
            val input = h.input(500)
            var completed = false
            var accepted = false
            h.controller.answerInboxInput(input, PromptAnswer.Choice("Continue")) {
                completed = true
                accepted = it
            }
            val request = h.next("relay.prompt.respond")
            val actual = h.steps.single { h.method(it) == "relay.prompt.respond" }
            h.reply(request, h.result(actual))
            // A transport FIFO barrier proves the response callback was posted; the actor is held.
            val barrier = CountDownLatch(1)
            h.net.transport.call(
                h.net.transport.correlation(),
                RelayCalls.conversations(),
                15_000
            ) { barrier.countDown() }
            val wire = h.peer.nextRequest()
            assertEquals("relay.conversations.list", h.method(wire, false))
            h.reply(wire, Dto.page().toWire())
            check(barrier.await(5, TimeUnit.SECONDS))
            assertFalse(completed)
            h.net.advance(1000)
            h.run()
            assertTrue("A current completion must not be swallowed by expired SEND time", completed)
            assertTrue(accepted)
            assertEquals(1, h.count("relay.prompt.respond"))
        }
    }

    private fun expiredObserverDenial(method: String, code: String) {
        Owner(observeExisting = true, infoTtlMs = 5).use { h ->
            h.attach()
            val request = h.observer(method)
            val before = h.queueTyped()
            val delivered = CountDownLatch(1)
            h.afterDelivered = { if (it == method) delivered.countDown() }
            h.net.advance(10)
            h.fail(request, code)
            check(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(
                "Fixture keeps typed SEND paced when the denial is delivered",
                before,
                h.typedCount()
            )
            h.run()
            h.release()
            assertEquals(
                "Local observer expiry must not discard a current correlated authority denial",
                before,
                h.typedCount()
            )
            assertEquals(
                if (code == "permission_denied") RelayPhase.STOPPED else RelayPhase.REVIEW_REQUIRED,
                h.ui!!.phase
            )
        }
    }

    @Test fun expiredObserverLeaseStillAppliesCorrelatedListPermissionWithdrawal() =
        expiredObserverDenial("relay.existing.list", "permission_denied")

    @Test fun expiredObserverLeaseStillAppliesCorrelatedHistoryProfileReview() =
        expiredObserverDenial("relay.existing.history.page", "profile_changed")

    @Test fun existingAnswerPromptExpiryAlsoRefusesAtActualPacedSend() =
        answerExpiry(infoExpiry = false, persistence = false, existing = true)

    @Test fun existingAnswerInfoExpiryAlsoRefusesAtActualPacedSend() =
        answerExpiry(infoExpiry = true, persistence = false, existing = true)

    @Test fun existingGetterInfoExpiryAtActualPacedSendCompletesNull() {
        Owner(existing = true, infoTtlMs = 500).use { h ->
            h.attach()
            h.fillBudget()
            var completed = 0
            var issued: InboxInput? = null
            h.controller.getInboxInput(h.handle) {
                completed++
                issued = it
            }
            h.run()
            h.release()
            assertEquals(0, h.count("relay.existing.prompt.get"))
            h.await { completed == 1 }
            assertNull(issued)
            assertEquals(0, h.count("relay.prompt.respond"))
        }
    }

    private fun outboxAuthority(
        ack: Boolean,
        replacement: Boolean = false,
        ordinary: Boolean = false
    ) {
        Owner(infoTtlMs = 500).use { h ->
            h.attach()
            var completed = 0
            var completionStatus = ""
            h.controller.refreshInbox {
                completed++
                completionStatus = it
            }
            var request = h.next("relay.outbox.read")
            if (ack) {
                val page = h.steps.first {
                    h.method(it) == "relay.outbox.read" &&
                        h.result(it)["items"]!!.jsonArray.isNotEmpty()
                }
                h.reply(request, h.result(page))
                request = h.next("relay.outbox.ack")
                assertTrue(
                    "ACK was actually sent under a valid Info lease after exact copy/aggregate confirmation",
                    h.memory.reread().snapshot.deliveries.single().pendingAcks.isNotEmpty()
                )
            }
            val before = h.queueTyped()
            val debt = h.memory.reread().snapshot.deliveries.single().pendingAcks
            val bodies = InboxCodec.encode(h.disk.load())
            val generation = h.net.transport.generation
            val ready = h.ready
            val delivered = CountDownLatch(1)
            val method = if (ack) "relay.outbox.ack" else "relay.outbox.read"
            h.afterDelivered = { if (it == method) delivered.countDown() }
            if (ordinary) {
                h.reply(
                    request,
                    if (ack) {
                        buildJsonObject {
                            put("acknowledged", JsonArray(debt.map(::JsonPrimitive)))
                        }
                    } else {
                        OutboxPage(emptyList(), 0).toWire()
                    }
                )
            } else {
                h.fail(request, "permission_denied")
            }
            check(delivered.await(5, TimeUnit.SECONDS))
            assertEquals("Denial is posted while typed work is still paced", before, h.typedCount())
            assertEquals(0, completed)
            // Response delivery and posting precede expiry. The controller actor has not run.
            h.net.advance(1000)
            if (replacement) {
                h.controller.configureInbox(h.disk.load().profiles.single().copy(enabled = false))
            }
            var checkedWrite = false
            if (!replacement && !ordinary) {
                val firstWrite: () -> Unit = {
                    h.beforePersist = {}
                    h.disk.beforeCommit = {}
                    h.release()
                    assertEquals("Withdrawal precedes first fallible write", before, h.typedCount())
                    checkedWrite = true
                }
                h.beforePersist = firstWrite
                h.disk.beforeCommit = firstWrite
                h.memory.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true)
            }
            h.run()
            h.release()
            assertEquals(
                "Current O5/ACK denial must withdraw queued typed SEND after local expiry",
                before + if (replacement || ordinary) 1 else 0,
                h.typedCount()
            )
            assertEquals(generation, h.net.transport.generation)
            assertTrue(ready === h.ready)
            if (replacement) {
                assertEquals(0, completed)
                assertFalse(h.disk.load().profiles.single().enabled)
                assertFalse(h.ui!!.phase == RelayPhase.STOPPED)
            } else if (ordinary) {
                assertEquals(1, completed)
                assertEquals("Partial / fresh authority required", completionStatus)
                assertFalse(h.ui!!.phase == RelayPhase.STOPPED)
            } else {
                assertTrue("First fallible persistence was reached", checkedWrite)
                assertEquals(RelayPhase.STOPPED, h.ui!!.phase)
                assertTrue(h.ui!!.unsaved)
                assertEquals(1, completed)
                assertEquals("Partial / source authority withdrawn", completionStatus)
                assertEquals("Partial / source authority withdrawn", h.controller.inboxStatus)
            }
            assertEquals(debt, h.memory.reread().snapshot.deliveries.single().pendingAcks)
            if (!replacement) assertTrue(bodies.contentEquals(InboxCodec.encode(h.disk.load())))
            h.run()
            assertEquals(if (replacement) 0 else 1, completed)
        }
    }

    @Test fun outboxPermissionPostedBeforeExpiryWithdrawsTypedQueueBeforeStorage() =
        outboxAuthority(ack = false)

    @Test fun validSentAckPermissionPostedBeforeExpiryWithdrawsTypedQueueBeforeStorage() =
        outboxAuthority(ack = true)

    @Test fun replacedOutboxObserverSuppressesPostedDenial() =
        outboxAuthority(ack = false, replacement = true)

    @Test fun replacedAckObserverSuppressesPostedDenial() =
        outboxAuthority(ack = true, replacement = true)

    @Test fun expiredOutboxSuccessFinishesPartialWithoutIngestionOrRevocation() =
        outboxAuthority(ack = false, ordinary = true)

    @Test fun expiredAckSuccessRetainsExactCopyAndDebtWithoutRevocation() =
        outboxAuthority(ack = true, ordinary = true)
}
