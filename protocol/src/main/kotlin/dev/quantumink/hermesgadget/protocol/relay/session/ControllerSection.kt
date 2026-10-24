package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import java.security.MessageDigest
import java.util.Collections

/**
 * Whether sending for this enrollment is allowed at all. A non-[ACTIVE] standing is itself durable so that process loss
 * can never re-enable a scope: only an explicit user action (review or re-authorisation) changes it back.
 */
enum class Standing {
    ACTIVE,

    /** The pinned profile and the observed one differ, or the server answered `profile_changed`. Nothing is sent. */
    REVIEW_REQUIRED,

    /** `permission_denied`: sending stops until the user re-authorises. */
    STOPPED
}

/**
 * A conversation creation the user asked for, saved BEFORE `relay.conversation.create` is written. The id and the title
 * never change; the create is retried with exactly these until it is answered. It is NOT a transport binding.
 */
class CreateIntent(val requestId: RequestId, val title: String?, val preparedAtMs: Long) {
    init {
        require(preparedAtMs >= 0)
        require(title == null || title.isNotBlank())
    }

    override fun equals(other: Any?): Boolean = other is CreateIntent &&
        other.requestId == requestId && other.title == title && other.preparedAtMs == preparedAtMs

    override fun hashCode(): Int = requestId.hashCode() * 31 + preparedAtMs.hashCode()

    override fun toString(): String = "CreateIntent(request=${requestId.value}, redacted)"
}

/**
 * The durable, controller-owned record of ONE phone enrollment's Relay mode (the "own session/create metadata").
 *
 * What is pinned (there is no `server_id` on the wire and none is invented):
 *  - [endpointUrl]: the canonical `wss` URL the phone enrolled at; [endpointKey] is its digest, the opaque key of the
 *    Relay scope;
 *  - [enrollmentKey]: THIS phone's own enrollment label (the public, key-derived device id), never the secret key;
 *  - [pinnedProfileId]: the server-issued `relay.info.profile_id` first seen after the user's explicit enable;
 *  - [reviewEpoch]: advanced by every deliberate review, so state of an older epoch is another scope.
 * Catalog and display identifiers play no part here and are never authority.
 */
class EnrollmentRecord(
    val endpointKey: String,
    val endpointUrl: String,
    val enrollmentKey: String,
    val enabled: Boolean,
    val pinnedProfileId: ProfileId?,
    val reviewEpoch: Long,
    val standing: Standing,
    /** What the last changed `relay.info` showed, for the review screen only. */
    val observedProfileId: ProfileId?,
    val selectedConversationId: ConversationId?,
    val createIntent: CreateIntent?,
    /** The already reserved selected identity is exact deletion evidence until confirmed removal. */
    val selectionDeleted: Boolean = false
) {
    init {
        require(
            endpointKey.length in 1..RelayScope.MAX_KEY && endpointKey.none {
                it.isISOControl()
            }
        )
        require(
            enrollmentKey.length in 1..RelayScope.MAX_KEY &&
                enrollmentKey.none { it.isISOControl() }
        )
        require(endpointUrl.length in 1..MAX_URL_CHARS)
        require(reviewEpoch >= 0)
        require(
            !selectionDeleted ||
                (selectedConversationId != null && HistoryNamespace.from(this) != null)
        )
    }

    /** The scope of this enrollment's state, or null before any profile was pinned. */
    fun scope(): EnrollmentScope? = pinnedProfileId?.let {
        EnrollmentScope(RelayScope(endpointKey, it), enrollmentKey, reviewEpoch)
    }

    /** Same enrollment, whatever its state. */
    fun sameEnrollment(endpointKey: String, enrollmentKey: String): Boolean =
        this.endpointKey == endpointKey && this.enrollmentKey == enrollmentKey

    fun copy(
        enabled: Boolean = this.enabled,
        pinnedProfileId: ProfileId? = this.pinnedProfileId,
        reviewEpoch: Long = this.reviewEpoch,
        standing: Standing = this.standing,
        observedProfileId: ProfileId? = this.observedProfileId,
        selectedConversationId: ConversationId? = this.selectedConversationId,
        createIntent: CreateIntent? = this.createIntent,
        selectionDeleted: Boolean = this.selectionDeleted
    ): EnrollmentRecord = EnrollmentRecord(
        endpointKey,
        endpointUrl,
        enrollmentKey,
        enabled,
        pinnedProfileId,
        reviewEpoch,
        standing,
        observedProfileId,
        selectedConversationId,
        createIntent,
        selectionDeleted
    )

    override fun equals(other: Any?): Boolean = other is EnrollmentRecord &&
        endpointKey == other.endpointKey && endpointUrl == other.endpointUrl &&
        enrollmentKey == other.enrollmentKey && enabled == other.enabled &&
        pinnedProfileId == other.pinnedProfileId && reviewEpoch == other.reviewEpoch &&
        standing == other.standing && observedProfileId == other.observedProfileId &&
        selectedConversationId == other.selectedConversationId &&
        createIntent == other.createIntent &&
        selectionDeleted == other.selectionDeleted

    override fun hashCode(): Int = 31 * endpointKey.hashCode() + enrollmentKey.hashCode()

    override fun toString(): String =
        "EnrollmentRecord(enabled=$enabled, standing=$standing, epoch=$reviewEpoch, redacted)"

    companion object {
        const val MAX_URL_CHARS = 2_048

        /** The opaque endpoint key of the Relay scope: a digest, never the URL. */
        fun endpointKey(canonicalUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalUrl.toByteArray())
            return "ep1:" + digest.joinToString("") { "%02x".format(it) }
        }

        fun fresh(canonicalUrl: String, enrollmentLabel: String): EnrollmentRecord =
            EnrollmentRecord(
                endpointKey(canonicalUrl),
                canonicalUrl,
                enrollmentLabel,
                enabled = false,
                pinnedProfileId = null,
                reviewEpoch = 0,
                standing = Standing.ACTIVE,
                observedProfileId = null,
                selectedConversationId = null,
                createIntent = null
            )
    }
}

/** The controller-owned section of the stored document: at most [StateLimits.MAX_ENROLLMENTS] enrollments. */
class ControllerSection(
    enrollments: List<EnrollmentRecord>,
    intents: List<MetadataIntent> = emptyList(),
    evictions: List<HistoryEviction> = emptyList(),
    /** Reserved for ONE exact canonical deletion at the ordinary eviction limit; never cache-only or scope-wide. */
    val emergencyDeletion: HistoryEviction? = null
) {
    val intents: List<MetadataIntent> = ownHistoryItems(intents, 16)
    val evictions: List<HistoryEviction> = ownHistoryItems(evictions, 64)
    val enrollments: List<EnrollmentRecord> = ownHistoryItems(
        enrollments,
        StateLimits.MAX_ENROLLMENTS + 1
    )
    val removals: List<HistoryEviction> get() = evictions + listOfNotNull(emergencyDeletion)

    init {
        require(
            this.enrollments.map { it.endpointKey to it.enrollmentKey }.toSet().size ==
                this.enrollments.size
        )
        require(
            this.intents.map {
                it.namespace to it.conversationId
            }.toSet().size == this.intents.size
        )
        require(this.intents.map { it.namespace to it.requestId }.toSet().size == this.intents.size)
        require(this.evictions.toSet().size == this.evictions.size)
        require(
            emergencyDeletion == null || (
                emergencyDeletion.kind == EvictionKind.DELETED &&
                    emergencyDeletion.conversationId != null && emergencyDeletion !in this.evictions
                )
        )
    }

    fun find(endpointKey: String, enrollmentKey: String): EnrollmentRecord? =
        enrollments.firstOrNull { it.sameEnrollment(endpointKey, enrollmentKey) }

    fun with(record: EnrollmentRecord): ControllerSection {
        val next = ArrayList(enrollments)
        val index = next.indexOfFirst {
            it.sameEnrollment(record.endpointKey, record.enrollmentKey)
        }
        if (index >= 0) next[index] = record else next.add(record)
        return ControllerSection(next, intents, evictions, emergencyDeletion)
    }

    fun withIntent(intent: MetadataIntent): ControllerSection = ControllerSection(
        enrollments,
        intents.filterNot {
            it.namespace == intent.namespace &&
                it.conversationId == intent.conversationId
        } +
            intent,
        evictions,
        emergencyDeletion
    )

    fun withoutIntent(intent: MetadataIntent): ControllerSection = ControllerSection(
        enrollments,
        intents.filterNot {
            it.requestId == intent.requestId &&
                it.namespace == intent.namespace
        },
        evictions,
        emergencyDeletion
    )

    fun owing(eviction: HistoryEviction): ControllerSection {
        if (eviction in removals) return this
        if (evictions.size <
            64
        ) {
            return ControllerSection(
                enrollments,
                intents,
                evictions + eviction,
                emergencyDeletion
            )
        }
        if (eviction.kind == EvictionKind.DELETED && eviction.conversationId != null &&
            emergencyDeletion == null
        ) {
            return ControllerSection(enrollments, intents, evictions, eviction)
        }
        throw HistoryFailure(HistoryProblem.CAPACITY)
    }

    fun cleared(eviction: HistoryEviction): ControllerSection = ControllerSection(
        enrollments.map { record ->
            if (record.selectionDeleted && eviction.kind != EvictionKind.CACHE_ONLY &&
                HistoryNamespace.from(record) == eviction.namespace &&
                (
                    eviction.conversationId == null ||
                        record.selectedConversationId?.value == eviction.conversationId
                    )
            ) {
                record.copy(selectedConversationId = null, selectionDeleted = false)
            } else {
                record
            }
        },
        intents.filterNot {
            eviction.kind != EvictionKind.CACHE_ONLY &&
                it.namespace == eviction.namespace &&
                (
                    eviction.conversationId == null ||
                        it.conversationId.value == eviction.conversationId
                    )
        },
        evictions.filterNot { it == eviction },
        emergencyDeletion?.takeUnless { it == eviction }
    )

    override fun equals(other: Any?): Boolean =
        other is ControllerSection && other.enrollments == enrollments &&
            other.intents == intents && other.evictions == evictions &&
            other.emergencyDeletion == emergencyDeletion
    override fun hashCode(): Int = enrollments.hashCode()
    override fun toString(): String = "ControllerSection(enrollments=${enrollments.size})"
    companion object {
        val EMPTY = ControllerSection(emptyList())
    }
}
