package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.RequestId
import java.security.SecureRandom

/**
 * The one entropy seam of request-id generation. It is asked for exactly [StateLimits.REQUEST_ID_ENTROPY_BYTES]
 * bytes per generated id and only ever by [RequestIds.mint]. Nothing else in this layer accepts entropy, so
 * reconnects, gaps, timeouts and restores cannot mint an id.
 */
fun interface RequestIdEntropy {
    fun fill(destination: ByteArray)
}

/** The production seam: the JDK's cryptographically strong generator. */
class SecureRequestIdEntropy(private val random: SecureRandom = SecureRandom()) : RequestIdEntropy {
    override fun fill(destination: ByteArray) = random.nextBytes(destination)

    override fun toString(): String = "SecureRequestIdEntropy"
}

enum class MintRefusal {
    /** The server-time estimate does not fit the 48-bit UUIDv7 timestamp. */
    TIMESTAMP_OUT_OF_RANGE,

    /** The entropy seam threw. */
    ENTROPY_FAILED,

    /** The seam returned all zero bytes: an obviously broken source is never used. */
    ENTROPY_DEGENERATE
}

sealed interface MintResult {
    class Minted internal constructor(val id: RequestId) : MintResult {
        override fun toString(): String = "MintResult.Minted"
    }

    class Refused internal constructor(val reason: MintRefusal) : MintResult {
        override fun toString(): String = "MintResult.Refused($reason)"
    }
}

/** How a NEW request id compares with server time (`relay_store.uuid7(fresh=True)`). */
enum class IdAge {
    FRESH,

    /** `timestamp < now - 604800000`. */
    TOO_OLD,

    /** `timestamp > now + 300000`. */
    TOO_FAR_AHEAD,

    /** [now] is negative or beyond the server's own clock range; no verdict is given and nothing wraps. */
    TIME_OUT_OF_RANGE
}

/** How a proposed NEW turn deadline compares with server time (`reserve_turn`: `now < e <= now + 300000`). */
enum class ExpiryCheck {
    VALID,

    /** `e <= now`: already past, or exactly now. */
    NOT_AFTER_NOW,

    /** `e > now + 300000`. */
    TOO_FAR_AHEAD,
    TIME_OUT_OF_RANGE
}

/**
 * Client-generated durable request ids and the server's age rules for them. Only ids WE generate are read for a
 * timestamp: server-issued identifiers (conversation, binding, message, event, ...) are opaque and never parsed.
 */
object RequestIds {
    /**
     * A canonical lowercase UUIDv7 whose 48-bit timestamp is the server-time estimate [now] (never the device wall
     * clock) with the RFC 4122 variant and 74 random bits from [entropy].
     */
    fun mint(now: ServerInstant, entropy: RequestIdEntropy): MintResult {
        val timestamp = now.serverMs
        if (timestamp < 0 || timestamp > StateLimits.UUID_TIMESTAMP_MAX_MS) {
            return MintResult.Refused(MintRefusal.TIMESTAMP_OUT_OF_RANGE)
        }
        val random = ByteArray(StateLimits.REQUEST_ID_ENTROPY_BYTES)
        try {
            entropy.fill(random)
        } catch (_: Exception) {
            return MintResult.Refused(MintRefusal.ENTROPY_FAILED)
        }
        if (random.all {
                it == 0.toByte()
            }
        ) {
            return MintResult.Refused(MintRefusal.ENTROPY_DEGENERATE)
        }
        val randomA = ((random[0].toInt() and 0x0f) shl 8) or (random[1].toInt() and 0xff)
        var low = 0L
        for (index in 2 until StateLimits.REQUEST_ID_ENTROPY_BYTES) {
            low = (low shl 8) or (random[index].toLong() and 0xff)
        }
        val high = (timestamp shl 16) or (VERSION_7 shl 12) or randomA.toLong()
        val lowWithVariant = (low and RANDOM_B_MASK) or VARIANT_BITS
        val hex = hex64(high) + hex64(lowWithVariant)
        val text = buildString(36) {
            append(
                hex,
                0,
                8
            ).append('-').append(hex, 8, 12).append('-').append(hex, 12, 16).append('-')
            append(hex, 16, 20).append('-').append(hex, 20, 32)
        }
        val id = RequestId.of(text) ?: return MintResult.Refused(MintRefusal.ENTROPY_FAILED)
        return MintResult.Minted(id)
    }

    /** The 48-bit millisecond timestamp of an id this client generated. */
    fun timestampMs(id: RequestId): Long {
        val text = id.value
        return text.substring(0, 8).toLong(16) shl 16 or text.substring(9, 13).toLong(16)
    }

    /**
     * Whether [id] may be used for a NEW turn at server time [serverNowMs]. The boundaries are inclusive exactly as
     * in the source: expired if `timestamp < now - 604800000` or `timestamp > now + 300000`.
     */
    fun checkNew(id: RequestId, serverNowMs: Long): IdAge {
        if (serverNowMs < 0 ||
            serverNowMs > StateLimits.SERVER_TIME_MAX_MS
        ) {
            return IdAge.TIME_OUT_OF_RANGE
        }
        val timestamp = timestampMs(id)
        return when {
            timestamp < serverNowMs - StateLimits.ID_RETENTION_MS -> IdAge.TOO_OLD
            timestamp > serverNowMs + StateLimits.ID_FUTURE_MS -> IdAge.TOO_FAR_AHEAD
            else -> IdAge.FRESH
        }
    }

    /**
     * A retained receipt or an exact same-id retry is judged by FORM only, never by freshness: the server resolves
     * a known id before it looks at the clock. Returns null when [text] is not a canonical lowercase UUIDv7.
     */
    fun retainedLookupId(text: String): RequestId? = RequestId.of(text)

    /** `now < expiresAtMs <= now + 300000`, strictly as `reserve_turn` checks a NEW turn. */
    fun checkNewTurnExpiry(serverNowMs: Long, expiresAtMs: Long): ExpiryCheck {
        if (serverNowMs < 0 ||
            serverNowMs > StateLimits.SERVER_TIME_MAX_MS
        ) {
            return ExpiryCheck.TIME_OUT_OF_RANGE
        }
        return when {
            expiresAtMs <= serverNowMs -> ExpiryCheck.NOT_AFTER_NOW
            expiresAtMs > serverNowMs + StateLimits.TURN_EXPIRY_MAX_AHEAD_MS ->
                ExpiryCheck.TOO_FAR_AHEAD
            else -> ExpiryCheck.VALID
        }
    }

    /** `now + ttlMs` for a lifetime in 1..300000, or null; checked, never wrapping. */
    fun proposeExpiry(serverNowMs: Long, ttlMs: Long): Long? {
        if (ttlMs < 1 || ttlMs > StateLimits.TURN_EXPIRY_MAX_AHEAD_MS) return null
        if (serverNowMs < 0 || serverNowMs > StateLimits.SERVER_TIME_MAX_MS) return null
        val expires = serverNowMs + ttlMs
        return expires.takeIf { checkNewTurnExpiry(serverNowMs, it) == ExpiryCheck.VALID }
    }

    private const val VERSION_7 = 7L
    private const val RANDOM_B_MASK = (1L shl 62) - 1
    private const val VARIANT_BITS = 2L shl 62

    private fun hex64(value: Long): String {
        val digits = "0123456789abcdef"
        val out = CharArray(16)
        for (index in 0 until 16) {
            out[15 - index] = digits[((value ushr (4 * index)) and 0xf).toInt()]
        }
        return String(out)
    }
}
