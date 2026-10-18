package dev.quantumink.hermesgadget.protocol

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Configuration only: no access token, identity, private-network declaration or consent. */
data class SetupEndpoint(
    val label: String,
    val url: String,
    val transport: TransportPreference = TransportPreference.AUTO
) {
    init {
        transferChecked("Invalid setup endpoint.") {
            transferName(label)
            transferUrl(url, catalog = false)
            require(transport != TransportPreference.RELAY || url.startsWith("wss:"))
        }
    }
    override fun toString(): String = "SetupEndpoint(content=redacted)"

    companion object {
        /** Deliberate allowlist projection; never serialize EndpointProfile.message(). */
        fun from(profile: EndpointProfile): SetupEndpoint =
            transferChecked("Invalid setup endpoint.") {
                SetupEndpoint(profile.label, profile.endpoint.url, profile.transport)
            }
    }
}

/** A source still requires an independently entered credential before native catalog discovery. */
data class SetupCatalog(val id: String, val label: String, val url: String) {
    init {
        transferChecked("Invalid setup catalog.") {
            transferId(id)
            require(id != "manual")
            transferName(label)
            transferUrl(url, catalog = true)
        }
    }
    override fun toString(): String = "SetupCatalog(content=redacted)"

    companion object {
        /**
         * Applies only HTTP-equivalent canonicalization of a user-typed native URL (lowercase host,
         * default 443 omitted, empty path as "/"); anything else must already be canonical.
         */
        fun from(source: CatalogSource): SetupCatalog = transferChecked("Invalid setup catalog.") {
            val uri = URI(source.url)
            val port = uri.port.takeUnless { it == -1 || it == 443 }?.let { ":" + it }.orEmpty()
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            val url = "https://" + requireNotNull(uri.host).lowercase(Locale.ROOT) + port + path
            SetupCatalog(source.id, source.label, url)
        }
    }
}

data class SetupMetadata(
    val setupId: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val label: String,
    val endpoint: SetupEndpoint?,
    val catalog: SetupCatalog?
) {
    init {
        transferChecked("Invalid setup metadata.") {
            require(setupId.matches(Regex("[a-f0-9]{32}")))
            transferTime(issuedAtEpochSeconds)
            transferTime(expiresAtEpochSeconds)
            require(expiresAtEpochSeconds - issuedAtEpochSeconds in 1..600)
            transferName(label)
            require(endpoint != null || catalog != null)
        }
    }
    override fun toString(): String = "SetupMetadata(content=redacted)"
}

/** The full address is intentionally available for native review, never for automatic access. */
class SetupConnectionPreview internal constructor(val label: String, val url: String) {
    private val uri = URI(url)
    val scheme: String get() = uri.scheme
    val host: String get() = uri.host.removeSurrounding("[", "]")
    val port: Int get() = if (uri.port != -1) {
        uri.port
    } else if (scheme == "ws") {
        80
    } else {
        443
    }
    val path: String get() = uri.rawPath
    val requiresCleartextReview: Boolean get() = scheme == "ws"
    override fun toString(): String = "SetupConnectionPreview(content=redacted)"
}

/** QR has NO cryptographic authenticity. Even valid edits require native host review/consent. */
class UntrustedSetupReview internal constructor(val metadata: SetupMetadata) {
    val endpoint: SetupConnectionPreview? = metadata.endpoint?.let {
        SetupConnectionPreview(it.label, it.url)
    }
    val catalog: SetupConnectionPreview? = metadata.catalog?.let {
        SetupConnectionPreview(it.label, it.url)
    }
    override fun toString(): String = "UntrustedSetupReview(content=redacted)"
}

object SetupMetadataCodec {
    const val MAX_BYTES = 4096
    const val MAX_LIFETIME_SECONDS = 600L
    private val random = SecureRandom()

    fun create(label: String, endpoint: String, now: Long): SetupMetadata =
        create(label, SetupEndpoint(label, endpoint), null, now)

    fun create(
        label: String,
        endpoint: SetupEndpoint?,
        catalog: SetupCatalog?,
        now: Long,
        lifetimeSeconds: Long = MAX_LIFETIME_SECONDS
    ): SetupMetadata {
        transferTime(now)
        require(lifetimeSeconds in 1..MAX_LIFETIME_SECONDS)
        val id = ByteArray(16).also(random::nextBytes).transferHex()
        return SetupMetadata(id, now, now + lifetimeSeconds, label, endpoint, catalog)
    }

    fun encode(value: SetupMetadata): String = buildJsonObject {
        put("version", 1)
        put("setup_id", value.setupId)
        put("issued_at", value.issuedAtEpochSeconds)
        put("expires_at", value.expiresAtEpochSeconds)
        put("label", value.label)
        put("endpoint", value.endpoint?.let(::endpointJson) ?: JsonNull)
        put("catalog", value.catalog?.let(::catalogJson) ?: JsonNull)
    }.toString().also { TransferJson.bytes(it, MAX_BYTES).fill(0) }

    fun decode(text: String, now: Long): UntrustedSetupReview = try {
        transferTime(now)
        val item = TransferJson.parse(text, MAX_BYTES, 6).transferObject(
            "version",
            "setup_id",
            "issued_at",
            "expires_at",
            "label",
            "endpoint",
            "catalog"
        )
        require(item.transferLong("version") == 1L)
        val value = SetupMetadata(
            item.transferString("setup_id"),
            item.transferLong("issued_at"),
            item.transferLong("expires_at"),
            item.transferString("label"),
            item["endpoint"].takeUnless { it == JsonNull }?.let(::decodeEndpoint),
            item["catalog"].takeUnless { it == JsonNull }?.let(::decodeCatalog)
        )
        require(value.issuedAtEpochSeconds <= now + 30 && value.expiresAtEpochSeconds > now)
        UntrustedSetupReview(value)
    } catch (_: Exception) {
        // Do not surface private addresses, malformed content or parser diagnostics to logs.
        throw IllegalArgumentException("Invalid or expired setup metadata.")
    }
}

internal fun endpointJson(value: SetupEndpoint): JsonObject = buildJsonObject {
    put("label", value.label)
    put("url", value.url)
    put("transport", value.transport.name)
}

internal fun catalogJson(value: SetupCatalog): JsonObject = buildJsonObject {
    put("id", value.id)
    put("label", value.label)
    put("url", value.url)
}

internal fun decodeEndpoint(value: JsonElement): SetupEndpoint {
    val item = value.transferObject("label", "url", "transport")
    return SetupEndpoint(
        item.transferString("label"),
        item.transferString("url"),
        TransportPreference.valueOf(item.transferString("transport"))
    )
}

internal fun decodeCatalog(value: JsonElement): SetupCatalog {
    val item = value.transferObject("id", "label", "url")
    return SetupCatalog(
        item.transferString("id"),
        item.transferString("label"),
        item.transferString("url")
    )
}

/**
 * N10 constructors/projections never surface input-bearing messages or causes (for example a
 * URISyntaxException quotes the private URL). Global Endpoint/stock behavior is unchanged.
 */
internal inline fun <T> transferChecked(message: String, block: () -> T): T = try {
    block()
} catch (_: Exception) {
    throw IllegalArgumentException(message)
}

internal fun transferId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_-]{1,64}")))
}

internal fun transferKey(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}")))
}

internal fun transferName(value: String) {
    require(value.isNotBlank() && value.length <= 32 && value.none(Char::isISOControl))
    TransferJson.bytes(value, 128).fill(0)
}

private fun transferTime(value: Long) {
    // Explicit epoch-seconds policy (2020 through 2100), rejecting millis and bad clocks.
    require(value in 1577836800L..4102444800L)
}

internal fun transferUrl(value: String, catalog: Boolean) {
    require(value.length in 1..2048 && value == value.trim() && value.all { it.code in 33..126 })
    val uri = URI(value)
    require(uri.scheme in if (catalog) setOf("https") else setOf("ws", "wss"))
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    val host = requireNotNull(uri.host).removeSurrounding("[", "]")
    require(host.isNotBlank() && '%' !in host && host == host.lowercase(Locale.ROOT))
    if (':' !in host) {
        require(
            host.length <= 253 && host.split('.').all {
                it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            }
        )
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            require(
                parts.size == 4 && parts.all {
                    it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0'))
                }
            )
        }
    }
    require(uri.rawPath.startsWith('/') && uri.normalize() == uri)
    require(
        uri.rawPath.none { it == '\\' } &&
            !uri.rawPath.contains(Regex("%(?:0[0-9a-fA-F]|1[0-9a-fA-F]|7[fF]|5[cC])"))
    )
    if (!catalog) {
        // These temporary flags validate syntax/private numeric ranges, never leave this function.
        // No DNS resolution occurs: Endpoint only parses numeric IPs on its cleartext path.
        require(Endpoint.parse(value, privateNetwork = true, cleartextAccepted = true).url == value)
    }
}

internal fun ByteArray.transferHex(): String = joinToString("") { "%02x".format(it) }

internal fun JsonElement.transferObject(vararg keys: String): JsonObject =
    (this as? JsonObject).also { require(it != null && it.keys == keys.toSet()) }!!

internal fun JsonObject.transferString(key: String): String =
    (getValue(key) as? JsonPrimitive).also { require(it != null && it.isString) }!!.content

internal fun JsonObject.transferLong(key: String): Long {
    val value = (getValue(key) as? JsonPrimitive).also {
        require(it != null && !it.isString)
    }!!.content
    require(value.matches(Regex("0|[1-9][0-9]*")))
    return value.toLong()
}

internal fun JsonObject.transferOptionalString(key: String): String? =
    if (getValue(key) == JsonNull) null else transferString(key)

internal fun JsonObject.transferArray(key: String, limit: Int): JsonArray =
    (getValue(key) as? JsonArray).also { require(it != null && it.size <= limit) }!!

/** Bounded strict JSON; decoded object keys (including escaped aliases) may occur only once. */
internal object TransferJson {
    fun bytes(text: String, limit: Int): ByteArray {
        require(text.length <= limit)
        val buffer = ByteBuffer.allocate(minOf(limit.toLong(), text.length.toLong() * 3).toInt())
        return try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val result = encoder.encode(CharBuffer.wrap(text), buffer, true)
            if (result.isError) result.throwException()
            require(!result.isOverflow)
            val flushed = encoder.flush(buffer)
            if (flushed.isError) flushed.throwException()
            require(!flushed.isOverflow)
            buffer.array().copyOf(buffer.position())
        } finally {
            buffer.array().fill(0)
        }
    }

    fun parse(bytes: ByteArray, limit: Int, depth: Int): JsonElement {
        require(bytes.size <= limit)
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        val text = try {
            decoded.toString()
        } finally {
            if (decoded.hasArray()) decoded.array().fill('\u0000')
        }
        return Parser(text, depth).parse()
    }

    fun parse(text: String, limit: Int, depth: Int): JsonElement {
        val bytes = bytes(text, limit)
        return try {
            parse(bytes, limit, depth)
        } finally {
            bytes.fill(0)
        }
    }

    private class Parser(private val text: String, private val maxDepth: Int) {
        private var index = 0
        private var nodes = 0
        fun parse(): JsonElement = value(1).also {
            whitespace()
            require(index == text.length)
        }
        private fun whitespace() {
            while (index < text.length && text[index] in " \n\r\t") index++
        }
        private fun take(char: Char): Boolean {
            whitespace()
            if (index == text.length || text[index] != char) return false
            index++
            return true
        }
        private fun value(depth: Int): JsonElement {
            require(depth <= maxDepth && ++nodes <= 32768)
            whitespace()
            require(index < text.length)
            return when (text[index]) {
                '{' -> {
                    index++
                    val members = linkedMapOf<String, JsonElement>()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val key = string()
                            require(!members.containsKey(key) && take(':'))
                            members[key] = value(depth + 1)
                        } while (take(','))
                        require(take('}'))
                    }
                    JsonObject(members)
                }
                '[' -> {
                    index++
                    val members = mutableListOf<JsonElement>()
                    if (!take(']')) {
                        do {
                            members += value(depth + 1)
                        } while (take(','))
                        require(take(']'))
                    }
                    JsonArray(members)
                }
                '"' -> JsonPrimitive(string())
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in ",]} \r\n\t") index++
                    val token = text.substring(start, index)
                    when (token) {
                        "null" -> JsonNull
                        "true" -> JsonPrimitive(true)
                        "false" -> JsonPrimitive(false)
                        else -> {
                            require(token.matches(Regex("-?(?:0|[1-9][0-9]*)")))
                            JsonPrimitive(token.toLong())
                        }
                    }
                }
            }
        }
        private fun string(): String {
            require(index < text.length && text[index] == '"')
            val start = index++
            while (index < text.length && text[index] != '"') {
                require(text[index].code >= 32)
                if (text[index] == '\\') index++
                index++
            }
            require(index < text.length)
            val result = (
                Json.parseToJsonElement(
                    text.substring(start, ++index)
                ) as JsonPrimitive
                ).content
            bytes(result, text.length).fill(0) // Reject unpaired escaped UTF-16 surrogates too.
            return result
        }
    }
}
