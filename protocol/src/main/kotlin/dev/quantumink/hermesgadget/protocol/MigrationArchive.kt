package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Collections
import java.util.Objects
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wire names deliberately match native appearances without importing the Android client module. */
enum class MigrationTheme { LIGHT, DARK, MOONLIGHT }
enum class MigrationWidgetTheme { SYSTEM, LIGHT, DARK, MOONLIGHT }
enum class MigrationWidgetPreset(val capacity: Int) {
    FOCUS(1),
    FOUR(4),
    EIGHT(8),
    STRIP_FOUR(4),
    SIXTEEN(16),
    STRIP_FIVE(5),
    TEN(10)
}
enum class MigrationArtworkFormat(val extension: String) { PNG("png"), GIF("gif") }

data class MigrationArtwork(
    val sha256: String,
    val bytes: Int,
    val format: MigrationArtworkFormat
) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..MigrationArchive.MAX_ARTWORK_BYTES)
    }
    val path: String get() = "artwork/$sha256.${format.extension}"
}

/**
 * Raw box picture, owner fallback, explicit watch override and GIF fallback remain distinct.
 * A `manual:` key is the native ClientSpeech.endpointId of one exported endpoint; owner fallback
 * pictures are endpoint-keyed natively, so they require a manual key or a Gadget URL.
 */
data class MigrationProfile(
    val key: String,
    val name: String,
    val gadgetUrl: String?,
    val portrait: String? = null,
    val fallbackPortrait: String? = null,
    val watchPortrait: String? = null,
    val animation: String? = null,
    val animationFallback: String? = null
) {
    init {
        transferChecked("Invalid migration profile.") {
            transferKey(key)
            transferName(name)
            val manual = key.startsWith("manual:")
            require(!manual || key.substringAfter(':').matches(Regex("[a-f0-9]{64}")))
            gadgetUrl?.let { transferUrl(it, catalog = false) }
            require(gadgetUrl == null || gadgetUrl.startsWith("wss:")) // Native pages are TLS only.
            require((animation == null) == (animationFallback == null))
            require(fallbackPortrait == null || manual || gadgetUrl != null)
        }
    }
    override fun toString(): String = "MigrationProfile(content=redacted)"
}

/**
 * Portable template ID, never an Android launcher ID/provider class/PendingIntent/revision.
 * [slots] is an owned unmodifiable copy; later changes to the caller's list have no effect.
 */
class MigrationWidget(
    val id: String,
    val preset: MigrationWidgetPreset,
    slots: List<String?>,
    val theme: MigrationWidgetTheme = MigrationWidgetTheme.SYSTEM
) {
    val slots: List<String?> = ownedList(slots)

    init {
        transferChecked("Invalid widget template.") {
            require(QuickActions.validId(id) && this.slots.size == preset.capacity)
            val bound = this.slots.filterNotNull()
            bound.forEach(::transferKey)
            require(bound.distinct().size == bound.size)
        }
    }

    fun copy(
        id: String = this.id,
        preset: MigrationWidgetPreset = this.preset,
        slots: List<String?> = this.slots,
        theme: MigrationWidgetTheme = this.theme
    ): MigrationWidget = MigrationWidget(id, preset, slots, theme)

    override fun equals(other: Any?): Boolean = other is MigrationWidget && id == other.id &&
        preset == other.preset && slots == other.slots && theme == other.theme
    override fun hashCode(): Int = listOf(id, preset, slots, theme).hashCode()
    override fun toString(): String = "MigrationWidget(content=redacted)"
}

/**
 * Explicit allowlist, deeply immutable and validated on construction. Every collection is an
 * owned unmodifiable copy (read once from the caller, never retained), so a checked snapshot
 * cannot change afterwards. Runtime status, consent, identity, auth, providers, history and
 * watch-local appearance have no fields. Every reference resolves.
 */
class MigrationSnapshot(
    endpoints: List<SetupEndpoint> = emptyList(),
    catalogs: List<SetupCatalog> = emptyList(),
    profiles: List<MigrationProfile> = emptyList(),
    sourceOrder: List<String> = listOf("manual"),
    profileOrder: List<String> = emptyList(),
    val appearance: MigrationTheme = MigrationTheme.DARK,
    actions: List<QuickAction> = emptyList(),
    widgets: List<MigrationWidget> = emptyList(),
    artwork: List<MigrationArtwork> = emptyList()
) {
    val endpoints: List<SetupEndpoint> = ownedItems(endpoints)
    val catalogs: List<SetupCatalog> = ownedItems(catalogs)
    val profiles: List<MigrationProfile> = ownedItems(profiles)
    val sourceOrder: List<String> = ownedItems(sourceOrder)
    val profileOrder: List<String> = ownedItems(profileOrder)
    val actions: List<QuickAction> = ownedItems(actions)
    val widgets: List<MigrationWidget> = ownedItems(widgets)
    val artwork: List<MigrationArtwork> = ownedItems(artwork)

    init {
        transferChecked("Invalid migration snapshot.") { MigrationArchive.validate(this) }
    }

    fun copy(
        endpoints: List<SetupEndpoint> = this.endpoints,
        catalogs: List<SetupCatalog> = this.catalogs,
        profiles: List<MigrationProfile> = this.profiles,
        sourceOrder: List<String> = this.sourceOrder,
        profileOrder: List<String> = this.profileOrder,
        appearance: MigrationTheme = this.appearance,
        actions: List<QuickAction> = this.actions,
        widgets: List<MigrationWidget> = this.widgets,
        artwork: List<MigrationArtwork> = this.artwork
    ): MigrationSnapshot = MigrationSnapshot(
        endpoints,
        catalogs,
        profiles,
        sourceOrder,
        profileOrder,
        appearance,
        actions,
        widgets,
        artwork
    )

    private fun fields(): List<Any> = listOf(
        endpoints,
        catalogs,
        profiles,
        sourceOrder,
        profileOrder,
        appearance,
        actions,
        widgets,
        artwork
    )
    override fun equals(other: Any?): Boolean =
        other is MigrationSnapshot && fields() == other.fields()
    override fun hashCode(): Int = fields().hashCode()
    override fun toString(): String = "MigrationSnapshot(content=redacted)"
}

/**
 * One read of the caller's list (`toArray`), then a JDK 1.2 unmodifiable wrapper available on every
 * Android API level; a cast to MutableList cannot mutate it. Avoids Java 9+ `List.copyOf`.
 */
internal fun <T> ownedList(values: List<T>): List<T> = transferChecked("Invalid collection.") {
    Collections.unmodifiableList(ArrayList(values))
}

/** As [ownedList], also rejecting nulls that a racing Java caller could otherwise smuggle in. */
internal fun <T : Any> ownedItems(values: List<T>): List<T> = ownedList(values).also {
    require(it.none(Objects::isNull)) { "Invalid collection." }
}

sealed interface MigrationReadResult {
    /**
     * Produced only by this decoder (an API affordance, not a cryptographic seal). Owned artwork
     * bytes; close after the native staging copy to clear these buffers.
     */
    class Success internal constructor(
        val snapshot: MigrationSnapshot,
        val artwork: Map<String, ByteArray>
    ) : MigrationReadResult,
        AutoCloseable {
        override fun close() {
            artwork.values.forEach { it.fill(0) }
        }
        override fun toString(): String = "MigrationReadResult.Success(content=redacted)"
    }

    /**
     * One result for malformed input, wrong passphrase, tamper, unsupported/quota violations and
     * also caller stream I/O or JCA provider failures. UI must use generic wording ("could not
     * open this archive"), never assert a wrong passphrase, and never log the input.
     */
    data object Invalid : MigrationReadResult
}

/**
 * Synchronous pure codec; call on an IO worker. No files, networking or store mutations.
 * Password entropy controls offline resistance. No key/passphrase is persisted. Caller owns its
 * passphrase/artwork and must not mutate inputs during encoding. Mutable owned buffers are cleared;
 * immutable JSON strings, returned content, caller inputs and JCA internals cannot be
 * guaranteed erased.
 */
object MigrationArchive {
    const val MAX_METADATA_BYTES = 512 * 1024
    const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = 40 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 256
    const val MAX_ARTWORK = 128
    const val MAX_WIDGETS = 128

    /** Fixed v1 work factor (OWASP's PBKDF2-HMAC-SHA256 figure; our choice, no FIPS claim). */
    const val KDF_ITERATIONS = 600000
    const val MIN_PASSPHRASE_CODE_POINTS = 12
    const val MAX_PASSPHRASE_CHARS = 1024
    const val MAX_PNG_DIMENSION = 4096
    const val MAX_PNG_PIXELS = 16_000_000L
    internal const val HEADER_BYTES = 52
    private const val TAG_BYTES = 16
    private val magic = "HGMIGR01".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(
        snapshot: MigrationSnapshot,
        passphrase: CharArray,
        artwork: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        requirePassphrase(passphrase)
        val archive = transferChecked("Could not create the migration archive.") {
            encodeZip(snapshot, artwork)
        }
        try {
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(magic).put(1).put(1).put(1).put(0)
                .putInt(KDF_ITERATIONS).putInt(archive.size).putInt(archive.size + TAG_BYTES)
                .put(salt).put(nonce).array()
            require(header.size + archive.size + TAG_BYTES <= MAX_ENVELOPE_BYTES)
            val key = deriveKey(passphrase, salt, KDF_ITERATIONS)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce)
                )
                cipher.updateAAD(header)
                val result = ByteArray(header.size + archive.size + TAG_BYTES)
                header.copyInto(result)
                cipher.doFinal(archive, 0, archive.size, result, header.size)
                return result
            } finally {
                key.fill(0)
            }
        } finally {
            archive.fill(0)
        }
    }

    /**
     * Opens a caller-held envelope in place: no stream or chunk copy of the ciphertext is made and
     * the caller's array is never modified. See [MigrationReadResult.Invalid] for failure wording.
     */
    fun decrypt(envelope: ByteArray, passphrase: CharArray): MigrationReadResult = try {
        requirePassphrase(passphrase)
        require(envelope.size in HEADER_BYTES..MAX_ENVELOPE_BYTES)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val bodyLength = validateHeader(header)
        require(envelope.size == HEADER_BYTES + bodyLength) // No truncation or trailing bytes.
        open(header, envelope, HEADER_BYTES, bodyLength, passphrase)
    } catch (_: Exception) {
        MigrationReadResult.Invalid
    }

    /**
     * Validates the fixed header before reading the body or doing KDF work. Body chunks are
     * allocated only as bytes arrive (at most one 1-MiB chunk beyond what was received), then
     * assembled once, so this path transiently holds about twice the ciphertext. Requires exact
     * EOF; the caller closes [input]. See [MigrationReadResult.Invalid] for failure wording.
     */
    fun read(input: InputStream, passphrase: CharArray): MigrationReadResult {
        var ciphertext: ByteArray? = null
        return try {
            requirePassphrase(passphrase)
            val header = ByteArray(HEADER_BYTES)
            readExactly(input, header)
            val bodyLength = validateHeader(header)
            ciphertext = readBody(input, bodyLength)
            require(input.read() == -1) // No concatenated envelopes or trailing bytes.
            open(header, ciphertext, 0, bodyLength, passphrase)
        } catch (_: Exception) {
            MigrationReadResult.Invalid
        } finally {
            ciphertext?.fill(0)
        }
    }

    /** KDF only after the complete bounded body is present; one doFinal verifies the tag first. */
    private fun open(
        header: ByteArray,
        body: ByteArray,
        offset: Int,
        length: Int,
        passphrase: CharArray
    ): MigrationReadResult.Success {
        var plaintext: ByteArray? = null
        val key = deriveKey(passphrase, header.copyOfRange(24, 40), KDF_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(40, 52))
            )
            cipher.updateAAD(header)
            // A single doFinal: JCA returns no plaintext unless the tag verifies. Never update().
            plaintext = cipher.doFinal(body, offset, length)
            require(plaintext.size == length - TAG_BYTES)
            return decodeZip(plaintext)
        } finally {
            plaintext?.fill(0)
            key.fill(0)
        }
    }

    private fun validateHeader(header: ByteArray): Int {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        require(ByteArray(8).also(buffer::get).contentEquals(magic))
        require(
            buffer.get().toInt() == 1 && buffer.get().toInt() == 1 &&
                buffer.get().toInt() == 1 && buffer.get().toInt() == 0
        )
        // Exactly one v1 KDF policy: attacker-selected work factors/algorithms are forbidden.
        require(buffer.int == KDF_ITERATIONS)
        val zipLength = buffer.int
        val bodyLength = buffer.int
        require(zipLength in 22..(MAX_ENVELOPE_BYTES - HEADER_BYTES - TAG_BYTES))
        require(bodyLength == zipLength + TAG_BYTES)
        return bodyLength
    }

    /** v1 policy: at least 12 code points after NFC; see [kdfInput]. */
    private fun requirePassphrase(passphrase: CharArray) {
        val bytes = kdfInput(passphrase)
        try {
            // Strict UTF-8: every code point has exactly one non-continuation (lead) byte.
            val codePoints = bytes.count { (it.toInt() and 0xc0) != 0x80 }
            require(codePoints >= MIN_PASSPHRASE_CODE_POINTS) {
                "Use a passphrase of at least 12 characters."
            }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Fixed v1 passphrase-to-bytes policy: at most 1024 UTF-16 input units; malformed Unicode
     * (unpaired surrogates) rejected BEFORE normalization; then Unicode NFC (canonical equivalence
     * only: no NFKC, trimming or case folding) and strict UTF-8. ASCII input is unchanged. The
     * normalized form is an immutable JVM String that cannot be erased.
     */
    private fun kdfInput(passphrase: CharArray): ByteArray {
        require(passphrase.size in 1..MAX_PASSPHRASE_CHARS) {
            "Use a passphrase of 12–1024 characters."
        }
        utf8(CharBuffer.wrap(passphrase)).fill(0)
        return utf8(
            CharBuffer.wrap(Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC))
        )
    }

    private fun utf8(text: CharBuffer): ByteArray {
        val buffer = ByteBuffer.allocate(text.remaining() * 3)
        return try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val result = encoder.encode(text, buffer, true)
            if (result.isError) result.throwException()
            val flushed = encoder.flush(buffer)
            if (flushed.isError) flushed.throwException()
            require(!result.isOverflow && !flushed.isOverflow)
            buffer.array().copyOf(buffer.position())
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Passphrase is not well-formed text.")
        } finally {
            buffer.array().fill(0)
        }
    }

    /**
     * RFC 8018 PBKDF2-HMAC-SHA256, one 32-byte block, over [kdfInput] bytes so every JCA provider
     * (JDK, Android) derives the same key. Mac/SecretKeySpec internal copies are not erasable.
     */
    internal fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val password = kdfInput(passphrase)
        val block = ByteArray(32)
        val result = ByteArray(32)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            mac.doFinal(block, 0)
            block.copyInto(result)
            repeat(iterations - 1) {
                mac.update(block)
                mac.doFinal(block, 0)
                for (index in result.indices) {
                    result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
                }
            }
            return result
        } catch (failure: Exception) {
            result.fill(0)
            throw failure
        } finally {
            password.fill(0)
            block.fill(0)
        }
    }

    /** Allocates at most one 1-MiB chunk beyond the bytes actually received. */
    private fun readBody(input: InputStream, length: Int): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var received = 0
        while (received < length) {
            val chunk = ByteArray(minOf(1 shl 20, length - received))
            readExactly(input, chunk)
            chunks += chunk
            received += chunk.size
        }
        val body = ByteArray(length)
        var offset = 0
        chunks.forEach {
            it.copyInto(body, offset)
            offset += it.size
        }
        return body
    }

    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, minOf(8192, bytes.size - offset))
            require(count > 0)
            offset += count
        }
    }

    internal fun manifest(snapshot: MigrationSnapshot): ByteArray {
        val text = buildJsonObject {
            put("version", 1)
            put("endpoints", JsonArray(snapshot.endpoints.map(::endpointJson)))
            put("catalogs", JsonArray(snapshot.catalogs.map(::catalogJson)))
            put("profiles", JsonArray(snapshot.profiles.map(::profileJson)))
            put("source_order", strings(snapshot.sourceOrder))
            put("profile_order", strings(snapshot.profileOrder))
            put("appearance", snapshot.appearance.name)
            put(
                "actions",
                JsonArray(
                    snapshot.actions.map { action ->
                        buildJsonObject {
                            put("id", action.id)
                            put("profile", optional(action.profileKey))
                            put("title", action.title)
                            put("template", action.template)
                        }
                    }
                )
            )
            put(
                "widgets",
                JsonArray(
                    snapshot.widgets.map { widget ->
                        buildJsonObject {
                            put("id", widget.id)
                            put("preset", widget.preset.name)
                            put("slots", JsonArray(widget.slots.map(::optional)))
                            put("theme", widget.theme.name)
                        }
                    }
                )
            )
            put(
                "artwork",
                JsonArray(
                    snapshot.artwork.map { asset ->
                        buildJsonObject {
                            put("sha256", asset.sha256)
                            put("bytes", asset.bytes)
                            put("format", asset.format.name)
                        }
                    }
                )
            )
        }.toString()
        return TransferJson.bytes(text, MAX_METADATA_BYTES)
    }

    private fun profileJson(profile: MigrationProfile): JsonObject = buildJsonObject {
        put("key", profile.key)
        put("name", profile.name)
        put("gadget_url", optional(profile.gadgetUrl))
        put("portrait", optional(profile.portrait))
        put("fallback_portrait", optional(profile.fallbackPortrait))
        put("watch_portrait", optional(profile.watchPortrait))
        put("animation", optional(profile.animation))
        put("animation_fallback", optional(profile.animationFallback))
    }

    private fun optional(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun string(value: JsonElement): String =
        (value as? JsonPrimitive).also { require(it != null && it.isString) }!!.content

    internal fun parseManifest(bytes: ByteArray): MigrationSnapshot {
        val item = TransferJson.parse(bytes, MAX_METADATA_BYTES, 6).transferObject(
            "version", "endpoints", "catalogs", "profiles", "source_order", "profile_order",
            "appearance", "actions", "widgets", "artwork"
        )
        require(item.transferLong("version") == 1L)
        return MigrationSnapshot(
            endpoints = item.transferArray(
                "endpoints",
                EndpointProfiles.MAX_PROFILES
            ).map(::decodeEndpoint),
            catalogs = item.transferArray(
                "catalogs",
                ProfileCatalog.MAX_SOURCES
            ).map(::decodeCatalog),
            profiles = item.transferArray("profiles", ProfileCatalog.MAX_PROFILES).map { value ->
                val profile = value.transferObject(
                    "key",
                    "name",
                    "gadget_url",
                    "portrait",
                    "fallback_portrait",
                    "watch_portrait",
                    "animation",
                    "animation_fallback"
                )
                MigrationProfile(
                    profile.transferString("key"),
                    profile.transferString("name"),
                    profile.transferOptionalString("gadget_url"),
                    profile.transferOptionalString("portrait"),
                    profile.transferOptionalString("fallback_portrait"),
                    profile.transferOptionalString("watch_portrait"),
                    profile.transferOptionalString("animation"),
                    profile.transferOptionalString("animation_fallback")
                )
            },
            sourceOrder = item.transferArray(
                "source_order",
                ProfileCatalog.MAX_SOURCES + 1
            ).map(::string),
            profileOrder = item.transferArray(
                "profile_order",
                ProfileCatalog.MAX_PROFILES
            ).map(::string),
            appearance = MigrationTheme.valueOf(item.transferString("appearance")),
            actions = item.transferArray("actions", QuickActions.MAX_ACTIONS).map { value ->
                val action = value.transferObject("id", "profile", "title", "template")
                QuickAction(
                    action.transferString("id"),
                    action.transferOptionalString("profile"),
                    action.transferString("title"),
                    action.transferString("template")
                )
            },
            widgets = item.transferArray("widgets", MAX_WIDGETS).map { value ->
                val widget = value.transferObject("id", "preset", "slots", "theme")
                MigrationWidget(
                    widget.transferString("id"),
                    MigrationWidgetPreset.valueOf(widget.transferString("preset")),
                    widget.transferArray("slots", 16).map {
                        if (it ==
                            JsonNull
                        ) {
                            null
                        } else {
                            string(it)
                        }
                    },
                    MigrationWidgetTheme.valueOf(widget.transferString("theme"))
                )
            },
            artwork = item.transferArray("artwork", MAX_ARTWORK).map { value ->
                val asset = value.transferObject("sha256", "bytes", "format")
                val size = asset.transferLong("bytes")
                require(size in 1..MAX_ARTWORK_BYTES.toLong())
                MigrationArtwork(
                    asset.transferString("sha256"),
                    size.toInt(),
                    MigrationArtworkFormat.valueOf(asset.transferString("format"))
                )
            }
        )
    }

    internal fun validate(snapshot: MigrationSnapshot) {
        require(snapshot.endpoints.size <= EndpointProfiles.MAX_PROFILES)
        require(snapshot.catalogs.size <= ProfileCatalog.MAX_SOURCES)
        require(snapshot.profiles.size <= ProfileCatalog.MAX_PROFILES)
        require(snapshot.actions.size <= QuickActions.MAX_ACTIONS)
        require(snapshot.widgets.size <= MAX_WIDGETS && snapshot.artwork.size <= MAX_ARTWORK)
        unique(snapshot.endpoints.map { it.url })
        val sources = snapshot.catalogs.map { it.id }
        unique(sources)
        val keys = snapshot.profiles.map { it.key }
        unique(keys)
        exactOrder(snapshot.sourceOrder, sources + "manual")
        exactOrder(snapshot.profileOrder, keys)
        unique(snapshot.profiles.mapNotNull { it.gadgetUrl })
        val assets = snapshot.artwork.associateBy { it.sha256 }
        require(assets.size == snapshot.artwork.size)
        require(snapshot.artwork.sumOf { it.bytes.toLong() } <= MAX_UNCOMPRESSED_BYTES)
        val referenced = mutableSetOf<String>()
        fun reference(hash: String?, format: MigrationArtworkFormat) {
            if (hash == null) return
            require(assets[hash]?.format == format)
            referenced += hash
        }
        val manual = snapshot.endpoints.associateBy {
            // Syntax-only parse of an already validated URL; no consent is implied or retained.
            val endpoint = Endpoint.parse(it.url, privateNetwork = true, cleartextAccepted = true)
            "manual:" + ClientSpeech.endpointId(endpoint)
        }
        snapshot.profiles.forEach { profile ->
            require(profile.key.substringBefore(':') in sources + "manual")
            if (profile.key.startsWith("manual:")) {
                // Native manual pages exist only for a saved endpoint and expose only its TLS URL.
                val endpoint = requireNotNull(manual[profile.key])
                require(profile.gadgetUrl == endpoint.url.takeIf { it.startsWith("wss:") })
            }
            reference(profile.portrait, MigrationArtworkFormat.PNG)
            reference(profile.fallbackPortrait, MigrationArtworkFormat.PNG)
            reference(profile.watchPortrait, MigrationArtworkFormat.PNG)
            reference(profile.animation, MigrationArtworkFormat.GIF)
            reference(profile.animationFallback, MigrationArtworkFormat.PNG)
        }
        require(referenced == assets.keys) // No unreferenced or unresolved members.
        unique(snapshot.actions.map { it.id })
        snapshot.actions.forEach { require(it.profileKey == null || it.profileKey in keys) }
        QuickActions.encode(snapshot.actions) // Reuses aggregate byte, input and action limits.
        unique(snapshot.widgets.map { it.id })
        snapshot.widgets.forEach { widget ->
            require(widget.slots.all { it == null || it in keys })
        }
    }

    private fun unique(values: List<String>) {
        require(values.toSet().size == values.size)
    }
    private fun exactOrder(order: List<String>, values: List<String>) {
        require(order.size == values.size && order.toSet() == values.toSet())
    }

    /**
     * Reads each caller artwork array exactly once into an owned copy; validation, CRC and ZIP
     * writing all use those copies, which are zeroed afterwards. The exact manifest bytes are
     * parsed back and must equal [snapshot] before anything is written (pre-seal self-check).
     */
    internal fun encodeZip(
        snapshot: MigrationSnapshot,
        artwork: Map<String, ByteArray>
    ): ByteArray {
        val owned = linkedMapOf<String, ByteArray>()
        try {
            var total = 0L
            for ((hash, bytes) in artwork.entries.toList()) {
                require(owned.size < MAX_ARTWORK && bytes.size in 1..MAX_ARTWORK_BYTES)
                total += bytes.size
                require(total <= MAX_UNCOMPRESSED_BYTES && hash !in owned)
                owned[hash] = bytes.copyOf()
            }
            require(owned.keys == snapshot.artwork.map { it.sha256 }.toSet())
            snapshot.artwork.forEach { asset -> validateAsset(asset, owned.getValue(asset.sha256)) }
            val metadata = manifest(snapshot)
            try {
                require(parseManifest(metadata) == snapshot)
                return zip(snapshot, metadata, owned)
            } finally {
                metadata.fill(0)
            }
        } finally {
            owned.values.forEach { it.fill(0) }
        }
    }

    private fun zip(
        snapshot: MigrationSnapshot,
        metadata: ByteArray,
        artwork: Map<String, ByteArray>
    ): ByteArray {
        require(
            metadata.size.toLong() + snapshot.artwork.sumOf { it.bytes.toLong() } <=
                MAX_UNCOMPRESSED_BYTES
        )
        val capacity = 22L + metadata.size + 76 + 2 * "manifest.json".length +
            snapshot.artwork.sumOf { it.bytes.toLong() + 76 + 2 * it.path.length }
        require(capacity <= MAX_ENVELOPE_BYTES - HEADER_BYTES - TAG_BYTES)
        return WipingOutput(capacity.toInt()).use { output ->
            val zip = ZipOutputStream(output)
            try {
                fun member(name: String, bytes: ByteArray) {
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().also { it.update(bytes) }.value
                        // 2000: inside the DOS range in every timezone; no timestamp extra.
                        time = 946684800000L
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
                member("manifest.json", metadata)
                snapshot.artwork.sortedBy { it.path }.forEach {
                    member(it.path, artwork.getValue(it.sha256))
                }
                zip.finish()
                output.detach() // The stored-only layout normally fills the preflight exactly.
            } finally {
                zip.close()
            }
        }.also { require(it.size <= MAX_ENVELOPE_BYTES - HEADER_BYTES - TAG_BYTES) }
    }

    internal fun decodeZip(bytes: ByteArray): MigrationReadResult.Success {
        val assets = linkedMapOf<String, ByteArray>()
        try {
            val zip = StrictZip(bytes)
            val metadata = zip.member(0, MAX_METADATA_BYTES)
            val snapshot = try {
                parseManifest(metadata)
            } finally {
                metadata.fill(0)
            }
            val expected = listOf("manifest.json") + snapshot.artwork.map { it.path }.sorted()
            require(zip.names == expected)
            snapshot.artwork.forEach { asset ->
                val data = zip.member(zip.names.indexOf(asset.path), asset.bytes)
                assets[asset.sha256] = data
                validateAsset(asset, data)
            }
            return MigrationReadResult.Success(snapshot, Collections.unmodifiableMap(assets))
        } catch (failure: Exception) {
            assets.values.forEach { it.fill(0) }
            throw failure
        }
    }

    private fun validateAsset(asset: MigrationArtwork, bytes: ByteArray) {
        require(bytes.size == asset.bytes && sha256(bytes) == asset.sha256)
        when (asset.format) {
            MigrationArtworkFormat.GIF -> {
                // Existing GIF contract is tighter than the generic 8-MiB archive member cap.
                GifPortrait.validate(bytes)
            }
            MigrationArtworkFormat.PNG -> {
                require(bytes.size <= ProfileCatalog.MAX_PORTRAIT_BYTES)
                validatePng(bytes)
            }
        }
    }

    /** Structural PNG validation only; native ImageDecoder must still decode before commit. */
    private fun validatePng(bytes: ByteArray) {
        val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        require(bytes.size >= 57 && bytes.copyOfRange(0, 8).contentEquals(signature))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        input.position(8)
        var chunks = 0
        var dataSeen = false
        while (input.remaining() >= 12) {
            val length = input.int
            require(length >= 0 && length <= input.remaining() - 8)
            val start = input.position()
            val type = ByteArray(4).also(input::get).toString(Charsets.US_ASCII)
            require(type.all { it in 'a'..'z' || it in 'A'..'Z' })
            if (chunks++ == 0) {
                require(type == "IHDR" && length == 13)
                val width = input.int
                val height = input.int
                // Box pictures are not phone-normalized; mirror PortraitStore's 16-MP decode bound.
                require(
                    width in 1..MAX_PNG_DIMENSION && height in 1..MAX_PNG_DIMENSION &&
                        width.toLong() * height <= MAX_PNG_PIXELS
                )
                val depth = input.get().toInt() and 255
                val color = input.get().toInt() and 255
                require(
                    when (color) {
                        0 -> depth in setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> depth in setOf(8, 16)
                        3 -> depth in setOf(1, 2, 4, 8)
                        else -> false
                    }
                )
                require(
                    input.get().toInt() == 0 && input.get().toInt() == 0 &&
                        input.get().toInt() in 0..1
                )
            } else {
                require(type != "IHDR" && type !in setOf("acTL", "fcTL", "fdAT"))
                require(type in setOf("PLTE", "IDAT", "IEND") || type[0].isLowerCase())
            }
            input.position(start + 4 + length)
            require(
                (input.int.toLong() and 0xffffffffL) == CRC32().also {
                    it.update(bytes, start, 4 + length)
                }.value
            )
            if (type == "IDAT") {
                require(length > 0)
                dataSeen = true
            }
            if (type == "IEND") {
                require(length == 0 && dataSeen && !input.hasRemaining())
                return
            }
        }
        throw IllegalArgumentException("Invalid artwork.")
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).transferHex()

    private class WipingOutput(capacity: Int) : ByteArrayOutputStream(capacity) {
        // Fixed preflighted capacity prevents abandoned plaintext buffers from growth copies.
        override fun write(value: Int) {
            require(count < buf.size)
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(count.toLong() + length <= buf.size)
            super.write(bytes, offset, length)
        }
        private var detached = false

        /** Hands over an exactly filled buffer without another copy; otherwise an exact copy. */
        fun detach(): ByteArray = if (count == buf.size) {
            detached = true
            buf
        } else {
            toByteArray()
        }

        override fun close() {
            if (!detached) buf.fill(0)
            reset()
        }
    }

    /**
     * ZIP32 allowlist, no extraction. A central/local cross-check establishes nonoverlapping,
     * contiguous payloads and exact EOF before any decompression. No ZIP64, extras or comments.
     * Stored and raw DEFLATE are supported; only the standard signed 16-byte descriptor is allowed.
     */
    private class StrictZip(private val bytes: ByteArray) {
        private data class Member(
            val name: String,
            val method: Int,
            val offset: Int,
            val compressed: Int,
            val size: Int,
            val crc: Long
        )
        private val members = mutableListOf<Member>()
        val names: List<String> get() = members.map { it.name }

        init {
            require(bytes.size in 22..MAX_ENVELOPE_BYTES)
            val end = bytes.size - 22
            require(u32(end) == 0x06054b50L && u16(end + 4) == 0 && u16(end + 6) == 0)
            val count = u16(end + 10)
            require(count in 1..MAX_ZIP_ENTRIES && u16(end + 8) == count && u16(end + 20) == 0)
            val directorySize = int32(end + 12)
            val directoryOffset = int32(end + 16)
            require(directoryOffset.toLong() + directorySize == end.toLong())
            var cursor = directoryOffset
            var localCursor = 0
            var total = 0L
            val seen = mutableSetOf<String>()
            repeat(count) {
                require(cursor <= end - 46 && u32(cursor) == 0x02014b50L)
                require(u16(cursor + 6) <= 20)
                val flags = u16(cursor + 8)
                val method = u16(cursor + 10)
                require(flags and 0x0808 == flags && method in setOf(0, 8))
                val crc = u32(cursor + 16)
                val compressed = int32(cursor + 20)
                val size = int32(cursor + 24)
                val length = u16(cursor + 28)
                require(length in 1..100 && u16(cursor + 30) == 0 && u16(cursor + 32) == 0)
                require(u16(cursor + 34) == 0 && cursor.toLong() + 46 + length <= end)
                val attributes = u32(cursor + 38)
                val unixType = (attributes ushr 16).toInt() and 0xf000
                require(unixType == 0 || unixType == 0x8000)
                require(attributes and 0x10L == 0L)
                val name = bytes.copyOfRange(
                    cursor + 46,
                    cursor + 46 + length
                ).toString(Charsets.US_ASCII)
                require(
                    name == "manifest.json" ||
                        name.matches(Regex("artwork/[a-f0-9]{64}\\.(png|gif)"))
                )
                require(seen.add(name) && (members.isNotEmpty() || name == "manifest.json"))
                val limit = if (name == "manifest.json") MAX_METADATA_BYTES else MAX_ARTWORK_BYTES
                require(size in 1..limit && compressed in 1..MAX_ENVELOPE_BYTES)
                total += size
                require(total <= MAX_UNCOMPRESSED_BYTES)
                val local = int32(cursor + 42)
                require(
                    local == localCursor && local <= directoryOffset - 30 &&
                        u32(local) == 0x04034b50L
                )
                require(u16(local + 4) <= 20 && u16(local + 6) == flags && u16(local + 8) == method)
                require(u16(local + 26) == length && u16(local + 28) == 0)
                val data = local.toLong() + 30 + length
                require(data + compressed <= directoryOffset)
                require(
                    bytes.copyOfRange(local + 30, data.toInt()).toString(Charsets.US_ASCII) == name
                )
                val descriptor = flags and 8 != 0
                require(u32(local + 14) == if (descriptor) 0L else crc)
                require(int32(local + 18) == if (descriptor) 0 else compressed)
                require(int32(local + 22) == if (descriptor) 0 else size)
                localCursor = (data + compressed).toInt()
                if (descriptor) {
                    require(localCursor <= directoryOffset - 16 && u32(localCursor) == 0x08074b50L)
                    require(
                        u32(localCursor + 4) == crc && int32(localCursor + 8) == compressed &&
                            int32(localCursor + 12) == size
                    )
                    localCursor += 16
                }
                members += Member(name, method, data.toInt(), compressed, size, crc)
                cursor += 46 + length
            }
            require(cursor == end && localCursor == directoryOffset)
        }

        fun member(index: Int, limit: Int): ByteArray {
            val member = members[index]
            require(member.size <= limit)
            val output = ByteArray(member.size)
            try {
                if (member.method == 0) {
                    require(member.compressed == member.size)
                    bytes.copyInto(output, 0, member.offset, member.offset + member.size)
                } else {
                    val inflater = Inflater(true)
                    val scratch = ByteArray(8192)
                    try {
                        inflater.setInput(bytes, member.offset, member.compressed)
                        var written = 0
                        while (!inflater.finished()) {
                            val count = inflater.inflate(scratch)
                            require(
                                count > 0 && written.toLong() + count <= member.size &&
                                    written.toLong() + count <= limit
                            )
                            scratch.copyInto(output, written, 0, count)
                            written += count
                        }
                        require(written == member.size && inflater.remaining == 0)
                    } finally {
                        inflater.end()
                        scratch.fill(0)
                    }
                }
                require(CRC32().also { it.update(output) }.value == member.crc)
                return output
            } catch (failure: Exception) {
                output.fill(0)
                throw failure
            }
        }

        private fun u16(offset: Int): Int {
            require(offset >= 0 && offset.toLong() + 2 <= bytes.size)
            return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        }
        private fun u32(offset: Int): Long =
            u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        private fun int32(offset: Int): Int =
            u32(offset).also { require(it <= Int.MAX_VALUE) }.toInt()
    }
}
