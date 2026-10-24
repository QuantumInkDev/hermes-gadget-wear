package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ExistingTaskPage
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayConnectionHarness
import dev.quantumink.hermesgadget.protocol.relay.RelaySocketFixture
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        override fun load() = InboxCodec.decode(InboxCodec.encode(state))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class Owner(val existing: Boolean = false) : AutoCloseable {
        val net = RelayConnectionHarness(RelaySocketFixture())
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

        // Synthetic authority view only for the existing sibling; raw resources stay exact.
        val info = if (!existing) {
            capturedInfo
        } else {
            Info.decode(
                JsonObject(
                    capturedInfo.toWire() + (
                        "methods" to JsonArray(
                            (
                                capturedInfo.methods + listOf(
                                    "relay.existing.attach",
                                    "relay.existing.prompt.get",
                                    "relay.existing.tasks.list"
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
        val controller = RelayController(
            store,
            scheduler,
            net::nanosNow,
            Dto.scriptedEntropy,
            inbox = repository,
            localMillis = { Dto.SERVER_TIME }
        ) { ui = it }
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
                    existingTasks = existing,
                    existingAvailable = existing
                )
            )
            if (existing) {
                repository.project(namespace, existing = listOf(existingTask), at = Dto.SERVER_TIME)
                handle = disk.load().tasks.single().handle
            } else {
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
            controller.attachTransport(net.transport)
            run()
            net.connection.connect()
            net.awaitConnected()
            reply(next("relay.info"), info.toWire())
            controller.onChannel(net.awaitState<RelayChannelState.Ready>(), 1)
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
}
