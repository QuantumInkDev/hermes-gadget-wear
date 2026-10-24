package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.mobile.widgets.phoneSelectionLocked
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.EndpointProfile

/** One incoming share, already bounded. Text is a draft only: nothing here reads a URI, fetches a link or sends. */
internal sealed interface SharedContent {
    class Text(val text: String) : SharedContent {
        override fun toString(): String = "SharedContent.Text(content=redacted)"
    }
    data class Rejected(val reason: ShareRejection) : SharedContent
}

internal enum class ShareRejection { EMPTY, TOO_LONG, UNSUPPORTED, UNREADABLE }

/** Phase 1 sharing accepts text and links only (ACTION_SEND, text/plain). Files are refused, never dropped silently. */
internal object ShareIntake {
    /** The stock typed-turn bound (UTF-8 bytes); the phone composer never needs to clip an accepted share. */
    const val MAX_BYTES = 8192
    const val ACTION_SEND = "android.intent.action.SEND"
    const val TEXT_PLAIN = "text/plain"

    fun read(
        action: String?,
        type: String?,
        subject: CharSequence?,
        text: CharSequence?,
        hasStream: Boolean
    ): SharedContent {
        if (action != ACTION_SEND || hasStream ||
            type?.substringBefore(';')?.trim()?.lowercase() != TEXT_PLAIN
        ) {
            return SharedContent.Rejected(ShareRejection.UNSUPPORTED)
        }
        // A character never encodes to fewer than one byte: reject oversize input before copying it.
        if ((subject?.length ?: 0) > MAX_BYTES || (text?.length ?: 0) > MAX_BYTES) {
            return SharedContent.Rejected(ShareRejection.TOO_LONG)
        }
        val heading = subject?.toString()?.trim().orEmpty()
        val body = text?.toString()?.trim().orEmpty()
        val combined = when {
            heading.isEmpty() -> body
            body.isEmpty() || heading in body -> body.ifEmpty { heading }
            else -> heading + "\n\n" + body
        }
        return when {
            combined.isBlank() -> SharedContent.Rejected(ShareRejection.EMPTY)
            !withinBound(combined) -> SharedContent.Rejected(ShareRejection.TOO_LONG)
            else -> SharedContent.Text(combined)
        }
    }

    fun withinBound(value: String): Boolean =
        value.length <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    fun sendable(value: String): Boolean = value.isNotBlank() && withinBound(value)

    /** Final synchronous gate after async profile lookup, before replacing a foreground draft. */
    fun accept(
        key: String,
        text: String,
        resolved: Pair<CatalogPage, EndpointProfile>?,
        state: PhoneConversationState
    ): Pair<CatalogPage, EndpointProfile>? {
        val (page, profile) = resolved ?: return null
        return resolved.takeIf {
            !phoneSelectionLocked(state) && sendable(text) && page.key == key &&
                page.status != CatalogStatus.WITHDRAWN && profile.endpoint.isTls &&
                page.profile.gadgetUrl == profile.endpoint.url
        }
    }
}

/**
 * In-process handoff from the exported receiver to MainActivity. Content never enters an Intent or saved state;
 * only its random token does, so rotation keeps the share and process death drops it.
 */
internal class ShareHandoffStore(private val capacity: Int = 4) {
    private val pending = LinkedHashMap<String, SharedContent>()

    @Synchronized fun put(content: SharedContent): String {
        while (pending.size >= capacity) pending.remove(pending.keys.first())
        val token = java.util.UUID.randomUUID().toString()
        pending[token] = content
        return token
    }

    @Synchronized fun get(token: String?): SharedContent? = token?.let { pending[it] }

    @Synchronized fun remove(token: String?) {
        if (token != null) pending.remove(token)
    }
}
