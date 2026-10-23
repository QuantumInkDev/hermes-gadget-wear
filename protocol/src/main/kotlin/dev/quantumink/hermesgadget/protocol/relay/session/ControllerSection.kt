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
    val createIntent: CreateIntent?
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
        createIntent: CreateIntent? = this.createIntent
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
        createIntent
    )

    override fun equals(other: Any?): Boolean = other is EnrollmentRecord &&
        endpointKey == other.endpointKey && endpointUrl == other.endpointUrl &&
        enrollmentKey == other.enrollmentKey && enabled == other.enabled &&
        pinnedProfileId == other.pinnedProfileId && reviewEpoch == other.reviewEpoch &&
        standing == other.standing && observedProfileId == other.observedProfileId &&
        selectedConversationId == other.selectedConversationId && createIntent == other.createIntent

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
class ControllerSection(enrollments: List<EnrollmentRecord>) {
    val enrollments: List<EnrollmentRecord> = Collections.unmodifiableList(ArrayList(enrollments))

    init {
        require(this.enrollments.size <= StateLimits.MAX_ENROLLMENTS + 1) {
            "Too many enrollments."
        }
        require(
            this.enrollments.map { it.endpointKey to it.enrollmentKey }.toSet().size ==
                this.enrollments.size
        ) { "Duplicate enrollment." }
    }

    fun find(endpointKey: String, enrollmentKey: String): EnrollmentRecord? =
        enrollments.firstOrNull { it.sameEnrollment(endpointKey, enrollmentKey) }

    /** Replaces (or appends) [record]; the caller checks the count bound through the codec/commit refusal. */
    fun with(record: EnrollmentRecord): ControllerSection {
        val index = enrollments.indexOfFirst {
            it.sameEnrollment(record.endpointKey, record.enrollmentKey)
        }
        val next = ArrayList(enrollments)
        if (index >= 0) next[index] = record else next.add(record)
        return ControllerSection(next)
    }

    override fun equals(other: Any?): Boolean =
        other is ControllerSection && other.enrollments == enrollments

    override fun hashCode(): Int = enrollments.hashCode()

    override fun toString(): String = "ControllerSection(enrollments=${enrollments.size})"

    companion object {
        val EMPTY = ControllerSection(emptyList())
    }
}
