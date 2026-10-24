package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.ExistingConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ExistingTaskPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxCommit
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxStageOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.RefreshKind
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual gap transitions and controller traversal; controlled Info/IO, no producer/native claim. */
class InboxExistingCompletenessTest {
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(state))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }

    private class Owner(
        val tasks: Boolean,
        val history: Boolean,
        val taskMethod: Boolean = true,
        val listMethod: Boolean = true,
        val historyMethod: Boolean = true,
        val knownTarget: Boolean = false
    ) {
        val disk = Disk()
        val repository = InboxRepository(disk)
        var published: InboxSnapshot? = null
        val f = ControllerFixture(inbox = repository, inboxUpdates = { s, _ -> published = s })
        val record = Seeds.record(f.identity)
        val namespace = HistoryNamespace.from(record)!!
        val sourceSteps = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResourceAsStream(
                    "/task5-inbox/existing-current-superseded/session.json"
                )
            ).bufferedReader().use { it.readText() }
        ).jsonObject["steps"]!!.jsonArray.map { it.jsonObject }
        val existing = ExistingTaskPage.decode(
            sourceSteps.first {
                it["request"]!!.jsonObject["method"] == JsonPrimitive(
                    "relay.existing.tasks.list"
                ) &&
                    result(it)["items"]!!.jsonArray.isNotEmpty()
            }["response"]!!.jsonObject["result"]!!.jsonObject
        ).items.first()

        private fun result(step: JsonObject) = step["response"]!!.jsonObject["result"]!!.jsonObject

        private fun settledBaseline(): DeliverySnapshot {
            val delivery = EnrollmentDelivery.begin(Seeds.scope(f.identity))
            val planned = delivery.planOutboxRead(CorrelationId.of("baseline")!!)
            val read = when (val result = planned.result) {
                is OutboxPlanOutcome.Planned -> result.plan
                else -> error("Baseline read must be planned")
            }
            val staged = planned.delivery.stageOutbox(
                read.ref,
                RelayOutcome.Success(OutboxPage(emptyList(), 6))
            )
            val batch = (staged.result as OutboxStageOutcome.Staged).batch
            val clock = (ServerClock.fix(Dto.info(), f.nanos.get()) as ClockFix.Fixed).clock
            val instant = (clock.read(f.nanos.get()) as InstantRead.At).instant
            val committed = staged.delivery.commitOutbox(batch.batchId, instant)
            check(committed.result is OutboxCommit.Committed)
            return committed.delivery.refreshed(
                null,
                RefreshKind.entries.toSet()
            ).delivery.snapshot()
        }

        init {
            val empty = Seeds.empty(ControllerSection(listOf(record)))
            f.store.commit(
                RelayDocument(
                    empty.section,
                    RelayStateSnapshot(
                        empty.snapshot.version,
                        empty.snapshot.ledger,
                        empty.snapshot.histories,
                        listOf(settledBaseline()),
                        empty.snapshot.captures
                    )
                ),
                0
            )
            repository.configure(
                InboxProfile(
                    namespace,
                    "gap",
                    "Gap",
                    "Box",
                    existingTasks = tasks,
                    existingHistory = history,
                    existingAvailable = tasks && taskMethod,
                    existingHistoryAvailable = history && listMethod && historyMethod,
                    lastSyncMs = 10
                )
            )
            if (knownTarget) {
                repository.project(namespace, existing = listOf(existing), at = Dto.SERVER_TIME)
            }
            f.prepare()
            f.controller.observeInboxOnly(true)
            f.run()
            val methods = Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack") +
                (if (taskMethod) listOf("relay.existing.tasks.list") else emptyList()) +
                (if (listMethod) listOf("relay.existing.list") else emptyList()) +
                (if (historyMethod) listOf("relay.existing.history.page") else emptyList())
            f.connect(Dto.info(methods = methods))
            f.answer("relay.outbox.read", OutboxPage(emptyList(), 7).toWire())
            // A REAL increased O5 gap, not a hand-constructed obligation or unknown initial baseline.
            assertEquals(
                listOf(Triple(null, "HISTORY", "OUTBOX_GAP"), Triple(null, "TASKS", "OUTBOX_GAP")),
                debt()
            )
            assertEquals(6L, settledBaseline().gapBaseline)
        }

        fun debt() = f.store.reread().snapshot.deliveries.single().obligations.map {
            Triple(it.conversationId, it.kind, it.cause)
        }

        fun finish() {
            f.answer("relay.tasks.list", TaskPage(emptyList(), null).toWire())
            f.answer("relay.conversations.list", Dto.page().toWire())
            if (history && listMethod) {
                f.answer(
                    "relay.existing.list",
                    ExistingConversationPage(emptyList(), null).toWire()
                )
            }
            if (knownTarget && tasks && taskMethod) {
                val request = f.transport.next("relay.existing.tasks.list")
                assertEquals(
                    existing.conversationId.value,
                    request.params["conversation_id"]!!.jsonPrimitive.content
                )
                f.answer("relay.existing.tasks.list", ExistingTaskPage(emptyList(), null).toWire())
            }
        }

        fun partial() {
            val exact = debt()
            finish()
            assertEquals("Incomplete source must retain the EXACT real gap debt", exact, debt())
            assertEquals(10, repository.snapshot().profiles.single().lastSyncMs)
            assertEquals(10, published!!.profiles.single().lastSyncMs)
            assertTrue(f.controller.inboxStatus.startsWith("Partial"))
            assertTrue(f.frames("relay.conversation.attach").isEmpty())
            assertTrue(f.frames("relay.turn.submit").isEmpty())
            assertEquals(if (history && listMethod) 1 else 0, f.frames("relay.existing.list").size)
            assertEquals(
                if (knownTarget && tasks &&
                    taskMethod
                ) {
                    1
                } else {
                    0
                },
                f.frames("relay.existing.tasks.list").size
            )
        }

        fun complete() {
            finish()
            assertTrue(debt().isEmpty())
            assertEquals(Dto.SERVER_TIME, repository.snapshot().profiles.single().lastSyncMs)
            assertEquals(Dto.SERVER_TIME, published!!.profiles.single().lastSyncMs)
            assertEquals("Current", f.controller.inboxStatus)
        }
    }

    @Test fun emptyTaskOnlyScopeWithMissingTaskMethodRetainsRealGapAndPreviousFullSync() =
        Owner(tasks = true, history = false, taskMethod = false).partial()

    @Test fun emptyBothSourcesWithMissingTaskMethodRetainsRealGapAndPreviousFullSync() =
        Owner(tasks = true, history = true, taskMethod = false).partial()

    @Test fun emptyTaskOnlyScopeWithPresentTaskMethodCannotInventExhaustiveDiscovery() =
        Owner(tasks = true, history = false).partial()

    @Test fun historyPurposeCatalogCannotProveExhaustiveIndependentTaskPurposeScope() =
        Owner(tasks = true, history = true).partial()

    @Test fun emptyHistoryOnlyScopeWithMissingHistoryMethodRetainsRealGap() =
        Owner(tasks = false, history = true, historyMethod = false).partial()

    @Test fun missingHistoryListRetainsRealGapWithoutBorrowingTaskGrant() =
        Owner(tasks = false, history = true, listMethod = false).partial()

    @Test fun knownTaskOnlyBoundedRefreshStillCannotProveUnknownTargetsWereRecovered() =
        Owner(tasks = true, history = false, knownTarget = true).partial()

    @Test fun completedCanonicalTraversalWithoutExistingOptInsClearsActualGap() =
        Owner(tasks = false, history = false).complete()

    @Test fun completedHistoryPurposeTraversalWithAllRequiredMethodsClearsActualGap() =
        Owner(tasks = false, history = true).complete()

    @Test fun unrequestedOptionalMethodsDoNotBlockCompleteCanonicalTraversal() = Owner(
        tasks = false,
        history = false,
        taskMethod = false,
        listMethod = false,
        historyMethod = false
    ).complete()
}
