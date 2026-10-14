package dev.quantumink.hermesgadget.protocol

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CatalogSource(val id: String, val label: String, val url: String, val credential: String) {
    init {
        catalogId(id)
        catalogName(label)
        require(url.length <= 2048)
        val uri = URI(url)
        require(
            uri.scheme == "https" && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
        ) { "Use a credential-free HTTPS catalog URL." }
        require(credential.length in 1..2048 && credential.all { it.code in 33..126 })
    }
    override fun toString(): String = "CatalogSource(redacted)"
}

data class PortraitDescriptor(val sha256: String, val bytes: Int, val path: String) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..ProfileCatalog.MAX_PORTRAIT_BYTES)
        require(path == "/assets/$sha256.png") { "Artwork must use a source-bound hash path." }
    }
}

class CatalogProfile(
    val id: String,
    val name: String,
    val gadgetUrl: String?,
    val portrait: PortraitDescriptor?
) {
    init {
        catalogId(id)
        catalogName(name)
        if (gadgetUrl !=
            null
        ) {
            require(
                Endpoint.parse(gadgetUrl).isTls && Endpoint.parse(gadgetUrl).url == gadgetUrl
            )
        }
    }
    override fun toString(): String = "CatalogProfile(redacted)"
}

enum class CatalogStatus { AVAILABLE, OFFLINE, ATTENTION, WITHDRAWN }

class CatalogPage(
    val sourceId: String,
    val sourceLabel: String,
    val profile: CatalogProfile,
    val status: CatalogStatus,
    val checkedAt: Long
) {
    init {
        catalogId(sourceId)
        catalogName(sourceLabel)
        require(checkedAt >= 0)
    }
    val key: String get() = "$sourceId:${profile.id}"
    override fun toString(): String = "CatalogPage(redacted)"
}

private fun catalogId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid catalog identity." }
}

private fun catalogName(value: String) {
    require(
        value.isNotBlank() && value.length <= 32 && value.none {
            it.isISOControl()
        }
    ) { "Name must be 1–32 characters." }
}

object ProfileCatalog {
    const val MAX_BYTES = 65536
    const val MAX_PROFILES = 32
    const val MAX_SOURCES = 3
    const val MAX_PORTRAIT_BYTES = 262144
    const val DATA_PATH = "/hermes/catalog/v1"
    const val PORTRAIT_PATH = "/hermes/portraits/v1/"
    const val REFRESH_PATH = "/hermes/catalog/refresh/v1"

    private fun envelope(text: String): Message {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Catalog exceeds 64 KiB." }
        require(text.trim().startsWith("{"))
        val message = requireNotNull(Message.parse("{\"type\":\"catalog\"," + text.trim().drop(1)))
        require(message.int("version") == 1) { "Unsupported catalog version." }
        return message
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf {
        it.isString
    }?.content
    private fun profile(item: JsonObject): CatalogProfile {
        val portrait = (item["portrait"] as? JsonObject)?.let { asset ->
            PortraitDescriptor(
                requireNotNull(asset.text("sha256")),
                requireNotNull(
                    (asset["bytes"] as? JsonPrimitive)?.takeUnless {
                        it.isString
                    }?.content?.toIntOrNull()
                ),
                requireNotNull(asset.text("path"))
            )
        }
        require(item["portrait"] == null || item["portrait"] == JsonNull || portrait != null)
        require(
            item["gadget_url"] == null || item["gadget_url"] == JsonNull ||
                item.text("gadget_url") != null
        )
        return CatalogProfile(
            requireNotNull(item.text("id")),
            requireNotNull(item.text("name")),
            item.text("gadget_url"),
            portrait
        )
    }

    fun decodeSource(text: String): List<CatalogProfile> {
        val items = envelope(text).field("profiles") as? JsonArray ?: error("Missing profiles")
        require(items.size <= MAX_PROFILES) { "Catalog exceeds 32 profiles." }
        val profiles = items.map { profile(it as? JsonObject ?: error("Invalid profile")) }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        val urls = profiles.mapNotNull { it.gadgetUrl }
        require(urls.distinct().size == urls.size) { "Profiles require independent Gadget URLs." }
        return profiles
    }

    private fun profileJson(profile: CatalogProfile): JsonObject = buildJsonObject {
        put("id", profile.id)
        put("name", profile.name)
        profile.gadgetUrl?.let { put("gadget_url", it) }
        profile.portrait?.let { asset ->
            put(
                "portrait",
                buildJsonObject {
                    put("sha256", asset.sha256)
                    put("bytes", asset.bytes)
                    put("path", asset.path)
                }
            )
        }
    }

    fun encodePages(pages: List<CatalogPage>): String {
        validate(pages)
        val text = buildJsonObject {
            put("version", 1)
            put(
                "pages",
                JsonArray(
                    pages.map { page ->
                        buildJsonObject {
                            put("source_id", page.sourceId)
                            put("source_label", page.sourceLabel)
                            put("profile", profileJson(page.profile))
                            put("status", page.status.name)
                            put("checked_at", page.checkedAt)
                        }
                    }
                )
            )
        }.toString()
        require(text.toByteArray().size <= MAX_BYTES) { "Catalog exceeds 64 KiB." }
        return text
    }

    fun decodePages(text: String): List<CatalogPage> {
        val items = envelope(text).field("pages") as? JsonArray ?: error("Missing pages")
        require(items.size <= MAX_PROFILES)
        return items.map { value ->
            val item = value as? JsonObject ?: error("Invalid page")
            CatalogPage(
                requireNotNull(item.text("source_id")),
                requireNotNull(item.text("source_label")),
                profile(item["profile"] as? JsonObject ?: error("Invalid profile")),
                CatalogStatus.valueOf(requireNotNull(item.text("status"))),
                requireNotNull(
                    (item["checked_at"] as? JsonPrimitive)?.takeUnless {
                        it.isString
                    }?.content?.toLongOrNull()
                )
            )
        }.also(::validate)
    }

    fun encodeSources(sources: List<CatalogSource>): String {
        require(
            sources.size <= MAX_SOURCES && sources.map {
                it.id
            }.distinct().size == sources.size
        )
        return buildJsonObject {
            put("version", 1)
            put(
                "sources",
                JsonArray(
                    sources.map { source ->
                        buildJsonObject {
                            put("id", source.id)
                            put("label", source.label)
                            put("url", source.url)
                            put("credential", source.credential)
                        }
                    }
                )
            )
        }.toString().also { require(it.toByteArray().size <= MAX_BYTES) }
    }

    fun decodeSources(text: String): List<CatalogSource> {
        val items = envelope(text).field("sources") as? JsonArray ?: error("Missing sources")
        require(items.size <= MAX_SOURCES)
        return items.map { value ->
            val item = value as? JsonObject ?: error("Invalid source")
            CatalogSource(
                requireNotNull(item.text("id")),
                requireNotNull(item.text("label")),
                requireNotNull(item.text("url")),
                requireNotNull(item.text("credential"))
            )
        }.also { encodeSources(it) }
    }

    private fun validate(pages: List<CatalogPage>) {
        require(pages.size <= MAX_PROFILES) { "Catalog exceeds 32 profiles." }
        require(pages.map { it.key }.distinct().size == pages.size)
        val urls = pages.mapNotNull { it.profile.gadgetUrl }
        require(urls.distinct().size == urls.size) { "Profiles require independent Gadget URLs." }
    }
}

object CatalogRoster {
    fun merge(
        previous: List<CatalogPage>,
        source: CatalogSource,
        response: List<CatalogProfile>?,
        status: CatalogStatus,
        checkedAt: Long
    ): List<CatalogPage> {
        require((response != null) == (status == CatalogStatus.AVAILABLE))
        val incoming = response.orEmpty().associateBy { it.id }
        require(incoming.size == response.orEmpty().size)
        val known = previous.filter { it.sourceId == source.id }.map { it.profile.id }.toSet()
        val updated = previous.map { page ->
            if (page.sourceId != source.id) {
                page
            } else {
                val profile = incoming[page.profile.id] ?: page.profile
                CatalogPage(
                    source.id,
                    source.label,
                    profile,
                    if (response != null &&
                        page.profile.id !in incoming
                    ) {
                        CatalogStatus.WITHDRAWN
                    } else if (page.status ==
                        CatalogStatus.WITHDRAWN &&
                        response == null
                    ) {
                        CatalogStatus.WITHDRAWN
                    } else {
                        status
                    },
                    checkedAt
                )
            }
        } +
            response.orEmpty().filter {
                it.id !in known
            }.map { CatalogPage(source.id, source.label, it, status, checkedAt) }
        ProfileCatalog.encodePages(updated)
        return updated
    }
}
