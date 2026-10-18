package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only a verified transfer changes a page's last good fallback portrait. */
class PortraitCache(private val directory: File) {
    private var roster: Set<String>? = null
    private var epoch = 0L
    private var accepting = true

    private fun admitted(page: String): Boolean =
        accepting && (roster == null || page in roster.orEmpty())

    /** Capture admission before remote IO; promotion rechecks it under the retirement lock. */
    @Synchronized fun admission(page: String): Long? = if (admitted(page)) epoch else null

    fun fetchAndStore(
        page: String,
        descriptor: PortraitDescriptor,
        admission: Long? = null,
        owner: PresentationKey? = null,
        fetch: () -> ByteArray?
    ): Boolean {
        val request = synchronized(this) {
            if (!admitted(page) || (admission != null && admission != epoch)) return false
            epoch
        }
        val bytes = fetch() ?: return false
        return synchronized(this) {
            if (request != epoch || !admitted(page)) return false
            store(page, descriptor, bytes, owner)
            true
        }
    }

    @Synchronized fun invalidateRequests() {
        epoch++
        accepting = false
    }
    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
    private fun index(): Map<String, String> {
        val file = AtomicFile(File(directory, "index"))
        if (!file.baseFile.exists() && !File(directory, "index.bak").exists()) return emptyMap()
        return file.openRead().use { input ->
            require(input.channel.size() <= 16384)
            (
                Json.parseToJsonElement(
                    input.readBytes().toString(Charsets.UTF_8)
                ) as JsonObject
                ).mapValues { (_, value) ->
                val id = (value as JsonPrimitive).content
                require(id.matches(Regex("[a-f0-9]{64}")))
                id
            }.also { require(it.size <= 32) }
        }
    }
    private fun write(file: File, data: ByteArray) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(data)
            atomic.finishWrite(output)
        } catch (
            failure: Exception
        ) {
            atomic.failWrite(output)
            throw failure
        }
    }
    private fun bitmap(bytes: ByteArray): Bitmap {
        require(bytes.size in 1..ProfileCatalog.MAX_PORTRAIT_BYTES)
        require(
            bytes.take(
                8
            ).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..256 && bounds.outHeight in 1..256)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    @Synchronized fun store(
        page: String,
        descriptor: PortraitDescriptor,
        bytes: ByteArray,
        owner: PresentationKey? = null
    ) = synchronized(storageLock) {
        require(page.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}")))
        require(admitted(page)) { "Portrait page has been retired." }
        require(bytes.size == descriptor.bytes && hash(bytes) == descriptor.sha256)
        bitmap(bytes).recycle()
        val index = index().filterKeys { roster == null || it in roster.orEmpty() }.toMutableMap()
        require(page in index || index.size < 32)
        val bindings = owners().toMutableMap()
        if (owner != null) {
            require(owner.digest in bindings || bindings.size < 64) {
                "Portrait ownership storage is full."
            }
            bindings[owner.digest] = descriptor.sha256
        }
        val images = directory.listFiles().orEmpty().filter { it.extension == "png" }
        val target = File(directory, descriptor.sha256 + ".png")
        require(target.exists() || images.size < 64) {
            "Portrait cache is full; retained artwork was kept."
        }
        require(
            images.sumOf { it.length() } - (if (target.exists()) target.length() else 0) +
                bytes.size <=
                8 * 1024 * 1024
        ) {
            "Portrait cache is full; retained artwork was kept."
        }
        write(target, bytes)
        if (owner != null) writeOwners(bindings)
        index[page] = descriptor.sha256
        write(
            File(directory, "index"),
            JsonObject(
                index.mapValues {
                    JsonPrimitive(it.value)
                }
            ).toString().toByteArray()
        )
        trim(index.keys)
    }

    @Synchronized fun currentHash(page: String): String? = if (admitted(
            page
        )
    ) {
        index()[page]
    } else {
        null
    }

    @Synchronized fun load(page: String): Bitmap? = runCatching {
        if (!admitted(page)) return null
        val id = index()[page] ?: return null
        val file = File(directory, "$id.png")
        require(file.length() in 1..ProfileCatalog.MAX_PORTRAIT_BYTES)
        val data = file.readBytes()
        require(hash(data) == id)
        bitmap(data)
    }.getOrNull()

    private fun owners(): Map<String, String> {
        val file = AtomicFile(File(directory, "owners-v1"))
        if (!file.baseFile.exists() && !File(directory, "owners-v1.bak").exists()) return emptyMap()
        return file.openRead().use { input ->
            require(input.channel.size() in 1..16384)
            val value = Json.parseToJsonElement(
                input.readBytes().toString(Charsets.UTF_8)
            ) as JsonObject
            require(value.size <= 64)
            value.map { (owner, item) ->
                require(item is JsonPrimitive && item.isString)
                val id = item.content
                require(owner.matches(Regex("[a-f0-9]{64}")) && id.matches(Regex("[a-f0-9]{64}")))
                owner to id
            }.toMap()
        }
    }
    private fun writeOwners(bindings: Map<String, String>) {
        write(
            File(directory, "owners-v1"),
            JsonObject(
                bindings.mapValues {
                    JsonPrimitive(it.value)
                }
            ).toString().toByteArray()
        )
    }
    private fun verified(id: String): Bitmap {
        val file = File(directory, "$id.png")
        require(file.length() in 1..ProfileCatalog.MAX_PORTRAIT_BYTES)
        val data = file.inputStream().use { it.readNBytes(ProfileCatalog.MAX_PORTRAIT_BYTES + 1) }
        require(data.size <= ProfileCatalog.MAX_PORTRAIT_BYTES && hash(data) == id)
        return bitmap(data)
    }

    /** A descriptor explicitly attributes content; the current request alone proves no ownership. */
    @Synchronized fun owned(
        page: String,
        owner: PresentationKey,
        descriptor: PortraitDescriptor?
    ): OwnedPortrait? = synchronized(storageLock) {
        runCatching {
            if (!admitted(page)) return null
            val bindings = owners().toMutableMap()
            var id = bindings[owner.digest]
            if (descriptor != null && descriptor.sha256 != id &&
                index()[page] == descriptor.sha256
            ) {
                val image = verified(descriptor.sha256)
                try {
                    require(owner.digest in bindings || bindings.size < 64)
                    bindings[owner.digest] = descriptor.sha256
                    writeOwners(bindings)
                } finally {
                    image.recycle()
                }
                id = descriptor.sha256
            }
            id?.let { OwnedPortrait(verified(it), owner, it) }
        }.getOrNull()
    }

    @Synchronized fun retain(pages: Set<String>) = synchronized(storageLock) {
        require(
            pages.size <= 32 &&
                pages.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}")) }
        )
        epoch++
        accepting = true
        roster = pages.toSet()
        trim(pages)
    }

    private fun trim(pages: Set<String>) {
        val index = index().filterKeys { it in pages }
        write(
            File(directory, "index"),
            JsonObject(
                index.mapValues {
                    JsonPrimitive(it.value)
                }
            ).toString().toByteArray()
        )
        // Retirement changes admission/index visibility, not ownership of retained bytes.
        // New writes refuse at the byte/file quota instead of deleting another owner's art.
    }

    @Synchronized fun reset() = synchronized(storageLock) {
        epoch++
        roster = emptySet()
        if (directory.exists()) check(directory.deleteRecursively())
    }
    companion object {
        // Tile/service cache instances share durable owner and quota transactions.
        private val storageLock = Any()
    }
}

/** Ownership is supplied only after the durable binding and content hash have been verified. */
class OwnedPortrait(val bitmap: Bitmap, val identity: PresentationKey, val sha256: String) {
    override fun toString() = "OwnedPortrait(redacted)"
}
