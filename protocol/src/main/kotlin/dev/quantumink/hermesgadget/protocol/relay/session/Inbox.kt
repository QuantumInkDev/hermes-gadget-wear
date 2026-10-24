package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.BindingRef
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ExistingTask
import dev.quantumink.hermesgadget.protocol.relay.ExistingTaskPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.Prompt
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.RelayParse
import dev.quantumink.hermesgadget.protocol.relay.Task
import dev.quantumink.hermesgadget.protocol.relay.TaskOrigin
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun <T : Any> ownInboxItems(values: List<T>, maximum: Int): List<T> = try {
    ownHistoryItems(values, maximum)
} catch (_: HistoryFailure) {
    throw InboxFailure(InboxProblem.CAPACITY)
}

enum class InboxProblem { CORRUPT, UNSUPPORTED, KEY, MISSING, STORAGE, CAPACITY, WRITE }
class InboxFailure(val problem: InboxProblem) : IllegalStateException("Inbox: $problem")
sealed interface InboxWrite {
    data object Committed : InboxWrite
    data object Refused : InboxWrite
    data object Stale : InboxWrite
    class Failed(val uncertain: Boolean) : InboxWrite
}

/** Blocking authenticated display storage, never a pending-action/voice/ACK journal. */
interface InboxFile {
    fun load(): InboxSnapshot
    fun commit(next: InboxSnapshot, base: Long): InboxWrite
}
data class InboxProfile(
    val namespace: HistoryNamespace,
    val key: String,
    val label: String,
    val box: String,
    val enabled: Boolean = true,
    val existingTasks: Boolean = false,
    val existingHistory: Boolean = false,
    val completionAlerts: Boolean = false,
    val inputAlerts: Boolean = false,
    val highWaterMs: Long = 0,
    val lastSyncMs: Long = 0,
    val existingAvailable: Boolean = false,
    val existingHistoryAvailable: Boolean = false
) {
    init {
        require(key.isNotEmpty() && key.length <= 512)
        require(label.length <= 512 && box.length <= 512)
        require(highWaterMs >= 0 && lastSyncMs >= 0)
    }
    override fun toString(): String = "InboxProfile(redacted)"
}
class InboxCopy(
    val namespace: HistoryNamespace,
    val item: OutboxItem,
    val receivedAtMs: Long,
    val handle: String,
    /** Persisted reservation/suppression BEFORE any OS call; never exactly-once publication. */
    val alert: Boolean,
    val unread: Boolean = true
) {
    init {
        require(receivedAtMs >= 0 && handle.matches(Regex("[0-9a-f]{64}")))
    }
    val canonicalConversation: String? get() = (item as? OutboxItem.Change)?.conversationId?.value
    val conversation: String get() = when (val v = item) {
        is OutboxItem.Change -> v.conversationId.value
        is OutboxItem.Final -> v.conversationRef.value
    }
    val existing: Boolean get() = (item.payload as? OutboxPayload.TaskChange)?.origin ==
        TaskOrigin.EXISTING
    val eventIdentity: String get() = JsonArray(
        listOf(
            JsonPrimitive(if (item is OutboxItem.Final) "opaque" else "canonical"),
            JsonPrimitive(conversation),
            JsonPrimitive(item.eventKey),
            JsonPrimitive(existing)
        )
    ).toString()
    override fun toString(): String = "InboxCopy(redacted)"
}
class InboxTask(
    val namespace: HistoryNamespace,
    val relay: Task?,
    val existing: ExistingTask?,
    val receivedAtMs: Long,
    val handle: String
) {
    init {
        require((relay == null) != (existing == null))
        require(relay?.prompt == null && receivedAtMs >= 0)
        require(handle.matches(Regex("[0-9a-f]{64}")))
    }
    val id: String get() = relay?.taskId?.value ?: requireNotNull(existing).taskId.value
    val conversation: String get() = relay?.conversationId?.value
        ?: requireNotNull(existing).conversationId.value
    val state: TaskState get() = relay?.state ?: requireNotNull(existing).state
    val outcome: String? get() = relay?.outcome?.wire ?: existing?.outcome?.wire
    val wire: JsonObject get() = relay?.toWire() ?: requireNotNull(existing).toWire()
    override fun toString(): String = "InboxTask(redacted)"
}
class InboxHistory(
    val namespace: HistoryNamespace,
    val conversation: String,
    val existing: Boolean,
    messages: List<RetainedMessage>,
    val receivedAtMs: Long
) {
    val messages = ownInboxItems(messages, 256)
    init {
        require(ConversationId.of(conversation) != null && receivedAtMs >= 0)
    }
    override fun toString(): String = "InboxHistory(redacted)"
}
data class InboxSuppression(
    val namespace: HistoryNamespace,
    val event: String,
    val receivedAtMs: Long
) {
    init {
        require(event.length in 1..4096 && receivedAtMs >= 0)
    }
    override fun toString(): String = "InboxSuppression(redacted)"
}
data class InboxUnavailable(
    val namespace: HistoryNamespace,
    val conversation: String,
    val history: Boolean
) {
    init {
        require(ConversationId.of(conversation) != null)
    }
    override fun toString(): String = "InboxUnavailable(redacted)"
}
class InboxSnapshot(
    val revision: Long = 0,
    val background: Boolean = false,
    val retentionDays: Int = 90,
    profiles: List<InboxProfile> = emptyList(),
    copies: List<InboxCopy> = emptyList(),
    tasks: List<InboxTask> = emptyList(),
    histories: List<InboxHistory> = emptyList(),
    suppressions: List<InboxSuppression> = emptyList(),
    unavailable: List<InboxUnavailable> = emptyList(),
    val roundRobin: Int = 0
) {
    val profiles = ownInboxItems(profiles, 16)
    val copies = ownInboxItems(copies, 4096)
    val tasks = ownInboxItems(tasks, 512)
    val histories = ownInboxItems(histories, 64)
    val suppressions = ownInboxItems(suppressions, 4096)
    val unavailable = ownInboxItems(unavailable, 128)
    init {
        if (this.copies.size + this.suppressions.size >
            4096
        ) {
            throw InboxFailure(InboxProblem.CAPACITY)
        }
        require(revision >= 0 && retentionDays in 0..365 && roundRobin in 0..15)
        require(this.profiles.map { it.namespace }.distinct().size == this.profiles.size)
        require(
            this.copies.map { it.namespace to it.item.deliveryId }.distinct().size ==
                this.copies.size
        )
        require(
            (this.copies.map { it.handle } + this.tasks.map { it.handle }).distinct().size ==
                this.copies.size + this.tasks.size
        )
    }
    fun copy(
        revision: Long = this.revision,
        background: Boolean = this.background,
        retentionDays: Int = this.retentionDays,
        profiles: List<InboxProfile> = this.profiles,
        copies: List<InboxCopy> = this.copies,
        tasks: List<InboxTask> = this.tasks,
        histories: List<InboxHistory> = this.histories,
        suppressions: List<InboxSuppression> = this.suppressions,
        unavailable: List<InboxUnavailable> = this.unavailable,
        roundRobin: Int = this.roundRobin
    ) = InboxSnapshot(
        revision, background, retentionDays, profiles, copies, tasks, histories,
        suppressions, unavailable, roundRobin
    )
    override fun toString(): String = "InboxSnapshot(revision=$revision, redacted)"
}

/**
 * Authenticated local route result. The handle itself carries no authority or guessed canonical
 * identity.
 */
class InboxRoute internal constructor(
    val profile: InboxProfile,
    val copy: InboxCopy?,
    val task: InboxTask?
) {
    val namespace: HistoryNamespace get() = profile.namespace
    val existing: Boolean get() = copy?.existing ?: (task?.existing != null)
    val conversation: String get() = copy?.conversation ?: requireNotNull(task).conversation
    val canonicalConversation: String? get() = copy?.canonicalConversation
        ?: if (copy == null) task?.conversation else null
    val relayTask: Task? get() = task?.relay
        ?: ((copy?.item?.payload as? OutboxPayload.TaskChange.Relay)?.task)
    val existingTask: ExistingTask? get() = task?.existing
        ?: ((copy?.item?.payload as? OutboxPayload.TaskChange.Existing)?.task)
    companion object {
        fun resolve(snapshot: InboxSnapshot, handle: String): InboxRoute? {
            if (!handle.matches(Regex("[0-9a-f]{64}"))) return null
            val copy = snapshot.copies.singleOrNull { it.handle == handle }
            val task = snapshot.tasks.singleOrNull { it.handle == handle }
            val owner = copy?.namespace ?: task?.namespace ?: return null
            val profile =
                snapshot.profiles.singleOrNull { it.namespace == owner && it.enabled }
                    ?: return null
            val route = InboxRoute(profile, copy, task)
            if (route.existing &&
                (
                    !profile.existingTasks || !profile.existingAvailable ||
                        snapshot.unavailable.any {
                            it.namespace == owner && it.conversation == route.conversation &&
                                !it.history
                        }
                    )
            ) {
                return null
            }
            return route
        }
    }
    override fun toString(): String = "InboxRoute(redacted)"
}
class InboxInput internal constructor(
    val prompt: Prompt,
    internal val route: String,
    internal val owner: HistoryNamespace,
    internal val serial: Long,
    internal val flow: Long,
    internal val writer: Long,
    internal val observer: Long,
    internal val inputGeneration: Long,
    internal val binding: BindingRef?,
    internal val boundToken: Any?,
    val canAnswer: Boolean
) {
    override fun toString(): String = "InboxInput(redacted)"
}

/**
 * Schema1 is independent of document4. Every nested wire object uses the existing strict bounded
 * decoder.
 */
object InboxCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(s: InboxSnapshot): ByteArray = try {
        val bytes = ByteArrayOutputStream()
        val d = DataOutputStream(bytes)
        fun text(value: String, max: Int = 65536) {
            val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                ).encode(java.nio.CharBuffer.wrap(value))
            require(b.remaining() <= max)
            d.writeInt(b.remaining())
            while (b.hasRemaining()) d.writeByte(b.get().toInt())
            if (bytes.size() > MAX_BYTES) throw InboxFailure(InboxProblem.CAPACITY)
        }
        fun owner(n: HistoryNamespace) {
            text(n.endpointKey)
            text(n.endpointUrl)
            text(n.enrollmentLabel)
            text(n.profileId)
            d.writeLong(n.reviewEpoch)
        }
        d.writeInt(0x48474942)
        d.writeInt(1)
        d.writeLong(s.revision)
        d.writeBoolean(s.background)
        d.writeInt(s.retentionDays)
        d.writeInt(s.profiles.size)
        for (p in s.profiles) {
            owner(p.namespace)
            text(p.key)
            text(p.label)
            text(p.box)
            for (v in listOf(
                p.enabled,
                p.existingTasks,
                p.existingHistory,
                p.completionAlerts,
                p.inputAlerts,
                p.existingAvailable,
                p.existingHistoryAvailable
            )) {
                d.writeBoolean(v)
            }
            d.writeLong(p.highWaterMs)
            d.writeLong(p.lastSyncMs)
        }
        d.writeInt(s.copies.size)
        for (c in s.copies) {
            owner(c.namespace)
            text(c.item.toWire().toString())
            d.writeLong(c.receivedAtMs)
            text(c.handle)
            d.writeBoolean(c.alert)
            d.writeBoolean(c.unread)
        }
        d.writeInt(s.tasks.size)
        for (t in s.tasks) {
            owner(t.namespace)
            d.writeBoolean(t.existing != null)
            text(t.wire.toString())
            d.writeLong(t.receivedAtMs)
            text(t.handle)
        }
        d.writeInt(s.histories.size)
        for (h in s.histories) {
            owner(h.namespace)
            text(h.conversation)
            d.writeBoolean(h.existing)
            d.writeLong(h.receivedAtMs)
            d.writeInt(h.messages.size)
            for (m in h.messages) {
                text(m.id)
                d.writeBoolean(m.role == RetainedRole.USER)
                d.writeLong(m.createdAtMs)
                text(m.text, 1_048_576)
            }
        }
        d.writeInt(s.suppressions.size)
        for (v in s.suppressions) {
            owner(v.namespace)
            text(v.event)
            d.writeLong(v.receivedAtMs)
        }
        d.writeInt(s.unavailable.size)
        for (v in s.unavailable) {
            owner(v.namespace)
            text(v.conversation)
            d.writeBoolean(v.history)
        }
        d.writeInt(s.roundRobin)
        d.writeInt(0x42494748)
        bytes.toByteArray().also {
            if (it.size >
                MAX_BYTES
            ) {
                throw InboxFailure(InboxProblem.CAPACITY)
            }
        }
    } catch (
        e: InboxFailure
    ) {
        throw e
    } catch (_: Exception) {
        throw InboxFailure(InboxProblem.CORRUPT)
    }

    fun decode(bytes: ByteArray): InboxSnapshot = try {
        if (bytes.size > MAX_BYTES) throw InboxFailure(InboxProblem.CAPACITY)
        val d = DataInputStream(ByteArrayInputStream(bytes))
        fun count(max: Int): Int = d.readInt().also { require(it in 0..max) }
        fun bool(): Boolean = d.readUnsignedByte().also { require(it <= 1) } == 1
        fun text(max: Int = 65536): String {
            val n = count(max)
            require(n <= d.available())
            val b = ByteArray(n)
            d.readFully(b)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                ).decode(ByteBuffer.wrap(b)).toString()
        }
        fun owner() = HistoryNamespace(text(512), text(8192), text(512), text(64), d.readLong())
        fun json(): JsonObject = (RelayJson.parse(text()) as? RelayParse.Parsed)?.root
            ?: throw InboxFailure(InboxProblem.CORRUPT)
        require(d.readInt() == 0x48474942)
        if (d.readInt() != 1) throw InboxFailure(InboxProblem.UNSUPPORTED)
        val rev = d.readLong()
        val bg = bool()
        val days = d.readInt()
        val profiles = List(count(16)) {
            val n = owner()
            val key = text(2048)
            val label = text(2048)
            val box = text(2048)
            val on = bool()
            val et = bool()
            val eh = bool()
            val ca = bool()
            val ia = bool()
            val ea = bool()
            val ha = bool()
            InboxProfile(n, key, label, box, on, et, eh, ca, ia, d.readLong(), d.readLong(), ea, ha)
        }
        val copies = List(count(4096)) {
            val n = owner()
            val raw = json()
            val page = OutboxPage.decode(
                buildJsonObject {
                    put("items", JsonArray(listOf(raw)))
                    put("next_cursor", JsonNull)
                    put("gap_generation", 0)
                }
            )
            InboxCopy(n, page.items.single(), d.readLong(), text(64), bool(), bool())
        }
        val tasks = List(count(512)) {
            val n = owner()
            val existing = bool()
            val raw = json()
            val relay = if (!existing) {
                Task.decodeGet(raw).also {
                    require(it.prompt == null)
                }
            } else {
                null
            }
            val old = if (existing) {
                ExistingTaskPage.decode(
                    buildJsonObject {
                        put("items", JsonArray(listOf(raw)))
                        put("next_cursor", JsonNull)
                    }
                ).items.single()
            } else {
                null
            }
            InboxTask(n, relay, old, d.readLong(), text(64))
        }
        val histories = List(count(64)) {
            val n = owner()
            val conversation = text(64)
            val existing = bool()
            val age = d.readLong()
            val messages = List(count(256)) {
                val id = text(64)
                val user = bool()
                val at = d.readLong()
                RetainedMessage(
                    id,
                    if (user) RetainedRole.USER else RetainedRole.ASSISTANT,
                    text(1_048_576),
                    at
                )
            }
            InboxHistory(n, conversation, existing, messages, age)
        }
        val suppressions =
            List(count(4096)) { InboxSuppression(owner(), text(16384), d.readLong()) }
        val unavailable = List(count(128)) { InboxUnavailable(owner(), text(64), bool()) }
        val roundRobin = d.readInt()
        require(d.readInt() == 0x42494748 && d.available() == 0)
        InboxSnapshot(
            rev, bg, days, profiles, copies, tasks, histories, suppressions, unavailable, roundRobin
        )
    } catch (
        e: InboxFailure
    ) {
        throw e
    } catch (_: Exception) {
        throw InboxFailure(InboxProblem.CORRUPT)
    }
}

/** Used exclusively by the controller's application-lifetime serial owner. */
class InboxRepository(private val file: InboxFile) {
    @Volatile private var displayed: InboxSnapshot? = null
    fun peek(): InboxSnapshot? = displayed
    fun snapshot(): InboxSnapshot = try {
        file.load().also { displayed = it }
    } catch (
        e: InboxFailure
    ) {
        displayed = null
        throw e
    }
    private fun save(old: InboxSnapshot, next: InboxSnapshot) {
        val owned = next.copy(revision = Math.addExact(old.revision, 1))
        InboxCodec.encode(owned) // bounds before any storage effect
        if (file.commit(
                owned,
                old.revision
            ) !is InboxWrite.Committed
        ) {
            throw InboxFailure(InboxProblem.WRITE)
        }
        displayed = owned
    }
    fun configure(profile: InboxProfile) {
        val old = snapshot()
        val previous = old.profiles.firstOrNull { it.namespace == profile.namespace }
        val next = profile.copy(
            highWaterMs = maxOf(profile.highWaterMs, previous?.highWaterMs ?: 0),
            lastSyncMs = maxOf(profile.lastSyncMs, previous?.lastSyncMs ?: 0)
        )
        save(
            old,
            old.copy(
                profiles =
                old.profiles.filterNot { it.namespace == profile.namespace } + next
            )
        )
    }
    fun nextRoundRobin(): Int {
        val old = snapshot()
        save(old, old.copy(roundRobin = (old.roundRobin + 1) % 16))
        return old.roundRobin
    }
    fun background(on: Boolean) {
        val old = snapshot()
        if (old.background != on) save(old, old.copy(background = on))
    }
    fun retention(days: Int) {
        require(days in 0..365)
        val old = snapshot()
        save(old, old.copy(retentionDays = days))
    }
    fun accept(
        namespace: HistoryNamespace,
        items: List<OutboxItem>,
        at: Long,
        serverAt: Long = at
    ): List<InboxCopy> {
        val old = snapshot()
        val profile = old.profiles.singleOrNull { it.namespace == namespace && it.enabled }
            ?: throw InboxFailure(InboxProblem.STORAGE)
        val now = maxOf(at, profile.highWaterMs)
        val rows = ArrayList(old.copies)
        val fresh = ArrayList<InboxCopy>()
        for (item in ownInboxItems(items, 50)) {
            val existing =
                (item.payload as? OutboxPayload.TaskChange)?.origin == TaskOrigin.EXISTING
            if (existing && (!profile.existingTasks || !profile.existingAvailable)) {
                throw InboxFailure(InboxProblem.STORAGE)
            }
            val prior = rows.firstOrNull {
                it.namespace == namespace &&
                    it.item.deliveryId == item.deliveryId
            }
            if (prior != null) {
                //  The server may reclassify CURRENT as SUPERSEDED. Identity, age and body do not
                // change.
                val a = prior.item.toWire().toMutableMap()
                val b = item.toWire().toMutableMap()
                fun withoutReference(v: JsonElement?): JsonObject {
                    val o = (v as JsonObject).toMutableMap()
                    o.remove("reference")
                    return JsonObject(o)
                }
                a["payload"] = withoutReference(a["payload"])
                b["payload"] =
                    withoutReference(b["payload"])
                if (a != b) throw InboxFailure(InboxProblem.CORRUPT)
                rows[rows.indexOf(prior)] =
                    InboxCopy(
                        namespace,
                        item,
                        prior.receivedAtMs,
                        prior.handle,
                        prior.alert,
                        prior.unread
                    )
                continue
            }
            val payload = item.payload as? OutboxPayload.TaskChange
            val state = when (payload) {
                is OutboxPayload.TaskChange.Relay -> payload.task.state
                is OutboxPayload.TaskChange.Existing -> payload.task.state
                null -> null
            }
            val wants = if (state ==
                TaskState.NEEDS_INPUT
            ) {
                profile.inputAlerts
            } else {
                profile.completionAlerts &&
                    (state == null || state == TaskState.FINISHED || state == TaskState.FAILED)
            }
            val candidate = InboxCopy(
                namespace,
                item,
                now,
                handle(),
                wants && item.expiresAtMs > serverAt
            )
            val duplicate =
                rows.any {
                    it.namespace == namespace && it.eventIdentity == candidate.eventIdentity
                } ||
                    old.suppressions.any {
                        it.namespace == namespace &&
                            it.event == candidate.eventIdentity
                    }
            val priorAge =
                (
                    rows.filter {
                        it.namespace == namespace &&
                            it.eventIdentity == candidate.eventIdentity
                    }
                        .map { it.receivedAtMs } + old.suppressions.filter {
                        it.namespace == namespace &&
                            it.event == candidate.eventIdentity
                    }.map { it.receivedAtMs }
                    ).minOrNull()
                    ?: now
            val row = if (duplicate) {
                InboxCopy(
                    namespace,
                    item,
                    priorAge,
                    candidate.handle,
                    false
                )
            } else {
                candidate
            }
            rows.add(row)
            fresh.add(row)
        }
        save(
            old,
            old.copy(
                copies = rows,
                suppressions = old.suppressions.filterNot { v ->
                    fresh.any { it.namespace == v.namespace && it.eventIdentity == v.event }
                },
                profiles = old.profiles.map {
                    if (it.namespace == namespace) it.copy(highWaterMs = now) else it
                }
            )
        )
        return fresh
    }

    /** Fresh authenticated bytes plus a successful write, never a seen-key inference. */
    fun confirmCopies(namespace: HistoryNamespace, ids: List<String>): Boolean {
        val old = snapshot()
        if (ids.any { id ->
                old.copies.none {
                    it.namespace == namespace &&
                        it.item.deliveryId.value == id
                }
            }
        ) {
            return false
        }
        save(old, old)
        return true
    }
    fun project(
        namespace: HistoryNamespace,
        relay: List<Task> = emptyList(),
        existing: List<ExistingTask> = emptyList(),
        at: Long
    ) {
        val old = snapshot()
        val rows = ArrayList(old.tasks)
        val now = maxOf(at, old.profiles.single { it.namespace == namespace }.highWaterMs)
        fun add(t: Task?, e: ExistingTask?) {
            val id = t?.taskId?.value ?: requireNotNull(e).taskId.value
            val conversation = t?.conversationId?.value ?: requireNotNull(e).conversationId.value
            val index = rows.indexOfFirst {
                it.namespace == namespace && it.id == id &&
                    it.conversation == conversation && (it.existing != null) == (e != null)
            }
            val prior = rows.getOrNull(index)
            val row = InboxTask(
                namespace,
                t?.withoutPrompt,
                e,
                if (prior?.wire ==
                    (t?.withoutPrompt?.toWire() ?: e?.toWire())
                ) {
                    requireNotNull(prior).receivedAtMs
                } else {
                    now
                },
                prior?.handle ?: handle()
            )
            if (index < 0) rows.add(row) else rows[index] = row
        }
        for (t in relay) add(t, null)
        for (t in existing) add(null, t)
        save(old, old.copy(tasks = rows))
    }
    fun history(
        namespace: HistoryNamespace,
        conversation: String,
        existing: Boolean,
        messages: List<RetainedMessage>,
        at: Long
    ) {
        val old = snapshot()
        val p = old.profiles.single { it.namespace == namespace && it.enabled }
        if (existing && !p.existingHistory) throw InboxFailure(InboxProblem.STORAGE)
        val prior = old.histories.firstOrNull {
            it.namespace == namespace &&
                it.conversation == conversation && it.existing == existing
        }
        val age = if (prior?.messages == messages) prior.receivedAtMs else maxOf(at, p.highWaterMs)
        save(
            old,
            old.copy(
                histories = old.histories.filterNot { it === prior } +
                    InboxHistory(namespace, conversation, existing, messages, age)
            )
        )
    }
    fun sourceUnavailable(
        namespace: HistoryNamespace,
        conversation: String,
        history: Boolean,
        unavailable: Boolean
    ) {
        val old = snapshot()
        val marker = InboxUnavailable(namespace, conversation, history)
        val markers =
            old.unavailable.filterNot { it == marker } +
                if (unavailable) listOf(marker) else emptyList()
        if (markers.toSet() != old.unavailable.toSet()) save(old, old.copy(unavailable = markers))
    }
    fun synced(namespace: HistoryNamespace, at: Long) {
        val old = snapshot()
        save(
            old,
            old.copy(
                profiles = old.profiles.map {
                    if (it.namespace == namespace) {
                        it.copy(
                            lastSyncMs = maxOf(at, it.lastSyncMs),
                            highWaterMs = maxOf(at, it.highWaterMs)
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }

    /**
     * Receipt ages never renew. Copy bodies owed to the aggregate cannot be purged by retention.
     */
    fun sweep(namespace: HistoryNamespace, at: Long, protected: List<String>) {
        val old = snapshot()
        val p = old.profiles.single { it.namespace == namespace }
        val now = maxOf(at, p.highWaterMs)
        val cutoff = maxOf(0L, now - old.retentionDays * 86_400_000L)
        val sevenDays = maxOf(0L, now - 7 * 86_400_000L)
        val removed = old.copies.filter {
            it.namespace == namespace && it.receivedAtMs <= cutoff &&
                it.item.deliveryId.value !in protected
        }
        val suppression =
            old.suppressions.filterNot {
                it.namespace == namespace && it.receivedAtMs < sevenDays
            } +
                removed.filter {
                    it.receivedAtMs >= sevenDays
                }.map { InboxSuppression(namespace, it.eventIdentity, it.receivedAtMs) }
        val tasks = old.tasks.filterNot { it.namespace == namespace && it.receivedAtMs <= cutoff }
        val histories = old.histories.filterNot {
            it.namespace == namespace &&
                it.receivedAtMs <= cutoff
        }
        if (removed.isEmpty() && suppression.size == old.suppressions.size &&
            tasks.size == old.tasks.size && histories.size == old.histories.size
        ) {
            return
        }
        save(
            old,
            old.copy(
                copies = old.copies.filterNot { it in removed },
                suppressions = suppression.distinctBy { it.namespace to it.event },
                tasks = tasks,
                histories = histories,
                profiles = old.profiles.map {
                    if (it.namespace ==
                        namespace
                    ) {
                        it.copy(highWaterMs = now)
                    } else {
                        it
                    }
                }
            )
        )
    }
    fun markRead(namespace: HistoryNamespace, handle: String) {
        val old = snapshot()
        val row =
            old.copies.singleOrNull {
                it.namespace == namespace && it.handle == handle && it.unread
            }
                ?: return
        save(
            old,
            old.copy(
                copies = old.copies.map {
                    if (it === row) {
                        InboxCopy(
                            it.namespace,
                            it.item,
                            it.receivedAtMs,
                            it.handle,
                            it.alert,
                            false
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }
    fun evict(namespace: HistoryNamespace, conversation: String?) {
        val old = snapshot()
        save(
            old,
            old.copy(
                copies = old.copies.filterNot {
                    it.namespace == namespace &&
                        (conversation == null || it.canonicalConversation == conversation)
                },
                tasks = old.tasks.filterNot {
                    it.namespace == namespace &&
                        (conversation == null || it.conversation == conversation)
                },
                histories = old.histories.filterNot {
                    it.namespace == namespace &&
                        (conversation == null || it.conversation == conversation)
                }
            )
        )
    }
    private fun handle(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
