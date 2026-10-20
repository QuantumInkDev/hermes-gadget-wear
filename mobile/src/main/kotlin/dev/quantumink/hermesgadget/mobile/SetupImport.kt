package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.GifDecoder
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetTemplateStore
import dev.quantumink.hermesgadget.mobile.widgets.WidgetAppearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetTemplate
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.EndpointProfiles
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.MigrationArtworkFormat
import dev.quantumink.hermesgadget.protocol.MigrationPrivateStage
import dev.quantumink.hermesgadget.protocol.MigrationProfile
import dev.quantumink.hermesgadget.protocol.MigrationReadResult
import dev.quantumink.hermesgadget.protocol.MigrationSnapshot
import dev.quantumink.hermesgadget.protocol.MigrationStagedReadResult
import dev.quantumink.hermesgadget.protocol.MigrationWidget
import dev.quantumink.hermesgadget.protocol.PortraitAnimations
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.TransportPreference
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values read together before a preview and again, unchanged, under the commit lock. */
internal class LocalTransferState(
    val endpoints: List<EndpointProfile>,
    val view: CatalogTransferView,
    /** Null while the action store has never been saved (defaults not yet seeded). */
    val actions: List<QuickAction>?,
    val templates: List<WidgetTemplate>,
    val appearance: Appearance
) {
    /** Credential-free digest: tokens, catalog credentials and consent are never hashed in. */
    val fingerprint: String = sha256Hex(
        listOf(
            endpoints.joinToString("\n") { it.endpoint.url + "|" + it.label + "|" + it.transport },
            view.sources.joinToString("\n") { it.id + "|" + it.label + "|" + it.url },
            view.sourceOrder.joinToString(",") + "|" + view.storedSourceOrder,
            ProfileCatalog.encodePages(view.raw),
            ProfileCatalog.encodePages(view.fallbacks),
            ProfileCatalog.encodePages(view.overrides),
            PortraitAnimations.encode(view.animations),
            actions?.let(QuickActions::encode).toString(),
            HomeWidgetTemplateStore.encode(templates),
            appearance.name
        ).joinToString("\u0000").toByteArray()
    )

    companion object {
        fun read(stores: SetupTransferStores) = LocalTransferState(
            stores.profiles.profiles(),
            stores.catalog.transferView(),
            stores.quickActions.stored(),
            stores.templates.templates(),
            stores.appearance.mode.value
        )
    }
}

/** A natively decoded picture ready to store; [hash] may differ after normalization. */
internal class PreparedArt(
    val format: MigrationArtworkFormat,
    val hash: String,
    val size: Int,
    private val load: () -> ByteArray
) {
    fun read(): ByteArray = load()
}

internal class EndpointItem(val setup: SetupEndpoint, val existing: EndpointProfile?) {
    val cleartext: Boolean get() = setup.url.startsWith("ws:")
    val key: String = "manual:" + ClientSpeech.endpointId(
        Endpoint.parse(setup.url, privateNetwork = true, cleartextAccepted = true)
    )
}

internal class SourceItem(val catalog: SetupCatalog, val localId: String?, val targetId: String) {
    val merged: Boolean get() = localId != null
}

internal class ProfileItem(
    val profile: MigrationProfile,
    val targetKey: String,
    val existing: Boolean,
    val endpointUrl: String?,
    val sourceId: String?,
    /** A different local page that already presents this bot's Gadget URL; never duplicated. */
    val sameBot: String?
) {
    val blocked: Boolean get() = sameBot != null

    val pictures: Int = listOfNotNull(
        profile.portrait,
        profile.fallbackPortrait,
        profile.watchPortrait,
        profile.animation
    ).size
}

internal class ActionItem(val action: QuickAction, val existing: QuickAction?) {
    internal var defaultProfileKey: String? = action.profileKey
    val identical: Boolean get() = identical(defaultProfileKey)
    fun identical(effectiveProfileKey: String?): Boolean = existing != null &&
        existing.title == action.title && existing.template == action.template &&
        existing.profileKey == effectiveProfileKey
}

internal class TemplateItem(val widget: MigrationWidget, val existing: WidgetTemplate?)

internal data class EndpointChoice(
    val include: Boolean,
    val update: Boolean = false,
    val privateNetwork: Boolean = false,
    val cleartextAccepted: Boolean = false
)
internal data class SourceChoice(
    val include: Boolean,
    val credential: String = "",
    val updateLabel: Boolean = false
) {
    override fun toString(): String = "SourceChoice(include=$include, credential=redacted)"
}
internal data class ProfileChoice(val include: Boolean, val usePictures: Boolean = false)
internal data class ItemChoice(val include: Boolean, val replace: Boolean = false)

internal data class ImportSelection(
    val endpoints: Map<String, EndpointChoice>,
    val sources: Map<String, SourceChoice>,
    val profiles: Map<String, ProfileChoice>,
    val actions: Map<String, ItemChoice>,
    val templates: Map<String, ItemChoice>,
    val useAppearance: Boolean
) {
    override fun toString(): String = "ImportSelection(content=redacted)"
}

/** Validated, natively decoded archive content plus its merge items. [close] clears art. */
internal class ImportPreview(
    val snapshot: MigrationSnapshot,
    val local: LocalTransferState,
    val art: Map<String, PreparedArt>,
    val endpoints: List<EndpointItem>,
    val sources: List<SourceItem>,
    val profiles: List<ProfileItem>,
    val actions: List<ActionItem>,
    val templates: List<TemplateItem>,
    private val disposeArt: () -> Unit
) : AutoCloseable {
    val appearance: Appearance = Appearance.valueOf(snapshot.appearance.name)

    /** Defaults preserve every existing value; consent and credentials start empty. */
    fun defaults(): ImportSelection = ImportSelection(
        endpoints.associate {
            it.setup.url to EndpointChoice(include = it.existing == null && !it.cleartext)
        },
        sources.associate { it.catalog.id to SourceChoice(include = it.merged) },
        profiles.associate {
            it.profile.key to ProfileChoice(include = !it.existing && !it.blocked)
        },
        actions.associate { it.action.id to ItemChoice(include = it.existing == null) },
        templates.associate { it.widget.id to ItemChoice(include = it.existing == null) },
        useAppearance = false
    )

    init {
        val selection = defaults()
        actions.forEach { it.defaultProfileKey = effectiveKey(it.action.profileKey, selection) }
    }

    /** Same selected roster/remapping as the planner, without allocating or reading artwork. */
    fun effectiveKey(key: String?, selection: ImportSelection): String? {
        key ?: return null
        val availableEndpoints = local.endpoints.map { it.endpoint.url }.toSet() +
            endpoints.filter { item ->
                val choice = selection.endpoints[item.setup.url]
                choice?.include == true &&
                    (!item.cleartext || choice.privateNetwork && choice.cleartextAccepted)
            }.map { it.setup.url }
        val availableSources = sources.filter { item ->
            item.merged || selection.sources[item.catalog.id]?.let { choice ->
                choice.include && runCatching {
                    CatalogSource(
                        item.targetId,
                        item.catalog.label,
                        item.catalog.url,
                        choice.credential.trim()
                    )
                }.isSuccess
            } == true
        }.map { it.catalog.id }.toSet()
        val urls = local.view.raw.filter {
            it.sourceId != "manual"
        }.mapNotNull { it.profile.gadgetUrl }.toMutableSet()
        val keys = local.view.raw.filter { it.sourceId != "manual" }.map { it.key }.toMutableSet()
        profiles.filter { it.endpointUrl == null && !it.blocked && !it.existing }.forEach { item ->
            if (item.sourceId in availableSources &&
                selection.profiles[item.profile.key]?.include == true
            ) {
                keys += item.targetKey
                item.profile.gadgetUrl?.let(urls::add)
            }
        }
        availableEndpoints.filter { it !in urls }.forEach { url ->
            keys += "manual:" + ClientSpeech.endpointId(Endpoint.parse(url, true, true))
        }
        val item = profiles.singleOrNull { it.profile.key == key } ?: return null
        return (item.sameBot ?: item.targetKey).takeIf { it in keys }
    }

    fun recipient(key: String?): String {
        key ?: return "Unbound — choose a bot later"
        val page = local.view.raw.singleOrNull { it.key == key }
        if (page != null) {
            return page.profile.name + " (" + key + ") " +
                (
                    page.profile.gadgetUrl
                        ?: local.endpoints.singleOrNull {
                            "manual:" + ClientSpeech.endpointId(it.endpoint) ==
                                key
                        }?.endpoint?.url
                    ).orEmpty()
        }
        val item = profiles.singleOrNull { it.targetKey == key }
        return (item?.profile?.name ?: key) + " (" + key + ") " +
            (item?.profile?.gadgetUrl ?: item?.endpointUrl).orEmpty()
    }

    override fun close() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanup.execute(disposeArt)
        } else {
            disposeArt()
        }
    }
    companion object {
        private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { action ->
            Thread(action, "setup-import-cleanup").apply { isDaemon = true }
        }
    }
    override fun toString(): String = "ImportPreview(content=redacted)"
}

/** The complete post-import value of every touched store, computed before any write. */
internal class ImportPlan(
    val addedEndpoints: List<EndpointProfile>,
    val updatedEndpoints: List<Triple<String, String, TransportPreference>>,
    val addedSources: List<CatalogSource>,
    val updatedSources: Map<String, String>,
    val raw: List<CatalogPage>,
    val fallbacks: List<CatalogPage>,
    val overrides: List<CatalogPage>,
    val animations: Map<String, AnimationDescriptor>,
    /** Null when no store write is needed (no new box / no action change). */
    val sourceOrder: List<String>?,
    val actions: List<QuickAction>?,
    val templates: List<WidgetTemplate>,
    val appearance: Appearance,
    val newPictures: List<PreparedArt>,
    val newAnimations: List<Pair<AnimationDescriptor, PreparedArt>>,
    val unbound: Int
)

internal class ImportPlanException(val problems: List<String>) :
    IllegalStateException("Import cannot be applied.")

/** One owner-visible outcome after commit; watch delivery is reported separately. */
internal class ImportOutcome(val publication: SetupPublication, val templates: Int) {
    val watch: CompletableFuture<Unit>? get() = publication.completion
}

internal enum class ImportStep {
    PREPARED,
    ARTWORK,
    ENDPOINTS,
    SOURCES,
    PAGES,
    FALLBACKS,
    OVERRIDES,
    ANIMATIONS,
    ORDER,
    ACTIONS,
    TEMPLATES,
    APPEARANCE,
    COMMITTED
}

internal object SetupImport {
    /** Test seam: invoked before each step; throwing an Error models process death. */
    @Volatile internal var fault: ((ImportStep) -> Unit)? = null

    @Volatile var recoveryProblem: Boolean = false
        private set

    /** Validates every picture natively, then pairs archive items with local collisions. */
    fun preview(stores: SetupTransferStores, read: MigrationReadResult.Success): ImportPreview {
        val incoming = read.takeArtwork()
        return try {
            preview(stores, read.snapshot) { incoming.getValue(it) }
        } finally {
            incoming.values.forEach { it.fill(0) }
        }
    }

    fun preview(
        stores: SetupTransferStores,
        read: MigrationStagedReadResult.Success
    ): ImportPreview = preview(stores, read.snapshot, read::artwork)

    private fun preview(
        stores: SetupTransferStores,
        snapshot: MigrationSnapshot,
        load: (String) -> ByteArray
    ): ImportPreview {
        requireTransferWorker()
        val stage = MigrationPrivateStage.create(
            java.io.File(stores.context.cacheDir, "setup-private-stage")
        )
        val art = linkedMapOf<String, PreparedArt>()
        try {
            snapshot.artwork.forEach { asset ->
                val bytes = load(asset.sha256)
                var normalized: ByteArray? = null
                try {
                    val stored = when (asset.format) {
                        MigrationArtworkFormat.PNG -> {
                            val portraits = stores.catalog.portraits
                            if (portraits.adoptable(bytes)) {
                                bytes
                            } else {
                                portraits.prepare(bytes).also {
                                    normalized =
                                        it
                                }
                            }
                        }
                        MigrationArtworkFormat.GIF -> {
                            GifPortrait.validate(bytes)
                            GifDecoder.drawable(bytes).let {
                                if (it is android.graphics.drawable.AnimatedImageDrawable) it.stop()
                            }
                            bytes
                        }
                    }
                    val offset = stage.append(stored)
                    val size = stored.size
                    art[asset.sha256] =
                        PreparedArt(asset.format, sha256Hex(stored), size) {
                            stage.bytes(offset, size)
                        }
                } finally {
                    normalized?.fill(0)
                    bytes.fill(0)
                }
            }
            stage.seal()
            val local = LocalTransferState.read(stores)
            val localEndpoints = local.endpoints.associateBy { it.endpoint.url }
            val endpoints = snapshot.endpoints.map { EndpointItem(it, localEndpoints[it.url]) }
            val localSources = local.view.sources
            val canonical = localSources.associate { source ->
                (runCatching { SetupCatalog.from(source).url }.getOrNull() ?: source.url) to
                    source.id
            }
            val taken = localSources.map { it.id }.toMutableSet()
            val sources = snapshot.catalogs.map { catalog ->
                val merged = canonical[catalog.url]
                val target = merged ?: freshId(catalog.id, taken)
                taken += target
                SourceItem(catalog, merged, target)
            }
            val targetSource = sources.associate { it.catalog.id to it.targetId }
            val localKeys = local.view.raw.map { it.key }.toSet()
            // Like the native roster, a manual page never hides a discovered bot's address.
            val localUrls = local.view.raw.filter { it.sourceId != "manual" }.mapNotNull { page ->
                page.profile.gadgetUrl?.let { it to page.key }
            }.toMap()
            val manualUrls = endpoints.associate { it.key to it.setup.url }
            val profileByKey = snapshot.profiles.associateBy { it.key }
            val profiles = snapshot.profileOrder.map { key ->
                val profile = profileByKey.getValue(key)
                val source = profile.key.substringBefore(':')
                val manual = source == "manual"
                val target = if (manual) {
                    profile.key
                } else {
                    targetSource.getValue(source) + ":" + profile.key.substringAfter(':')
                }
                val url = if (manual) manualUrls[profile.key] else profile.gadgetUrl
                val owner = url?.let { localUrls[it] }?.takeIf { it != target }
                ProfileItem(
                    profile,
                    target,
                    existing = target in localKeys ||
                        manual && manualUrls[profile.key] in localEndpoints,
                    endpointUrl = if (manual) manualUrls[profile.key] else null,
                    sourceId = if (manual) null else source,
                    sameBot = owner
                )
            }
            val localActions = local.actions.orEmpty().associateBy { it.id }
            val localTemplates = local.templates.associateBy { it.id }
            return ImportPreview(
                snapshot,
                local,
                art,
                endpoints,
                sources,
                profiles,
                snapshot.actions.map { ActionItem(it, localActions[it.id]) },
                snapshot.widgets.map { TemplateItem(it, localTemplates[it.id]) },
                stage::close
            )
        } catch (failure: Exception) {
            stage.close()
            throw failure
        }
    }

    private fun freshId(base: String, taken: Set<String>): String {
        if (base !in taken && base != "manual") return base
        for (suffix in 2..99) {
            val candidate = base.take(60) + "-" + suffix
            if (candidate !in taken) return candidate
        }
        error("No free box identity.")
    }

    /** Pure merge of [selection] into the preview's local state, with every limit checked. */
    fun plan(
        stores: SetupTransferStores,
        preview: ImportPreview,
        selection: ImportSelection
    ): ImportPlan {
        val problems = mutableListOf<String>()
        val local = preview.local
        val addedEndpoints = mutableListOf<EndpointProfile>()
        val updatedEndpoints = mutableListOf<Triple<String, String, TransportPreference>>()
        val availableEndpoints = local.endpoints.map { it.endpoint.url }.toMutableSet()
        preview.endpoints.forEach { item ->
            val choice = selection.endpoints[item.setup.url] ?: return@forEach
            if (item.existing != null) {
                if (choice.update) {
                    updatedEndpoints +=
                        Triple(item.setup.url, item.setup.label, item.setup.transport)
                }
            } else if (choice.include) {
                val endpoint = runCatching {
                    if (item.cleartext) {
                        Endpoint.parse(
                            item.setup.url,
                            choice.privateNetwork,
                            choice.cleartextAccepted
                        )
                    } else {
                        Endpoint.parse(item.setup.url)
                    }
                }.getOrNull()
                if (endpoint == null) {
                    problems += "A cleartext connection needs both LAN/tailnet choices."
                } else {
                    addedEndpoints +=
                        EndpointProfile(endpoint, item.setup.label, "", item.setup.transport)
                    availableEndpoints += item.setup.url
                }
            }
        }
        if (availableEndpoints.size > EndpointProfiles.MAX_PROFILES) {
            problems += "This phone can keep at most ${EndpointProfiles.MAX_PROFILES} connections."
        }
        val addedSources = mutableListOf<CatalogSource>()
        val updatedSources = linkedMapOf<String, String>()
        val sourceLabels = local.view.sources.associate { it.id to it.label }.toMutableMap()
        val availableSources = mutableMapOf<String, String>()
        preview.sources.forEach { item ->
            val choice = selection.sources[item.catalog.id] ?: SourceChoice(false)
            if (item.merged) {
                availableSources[item.catalog.id] = item.targetId
                if (choice.updateLabel) {
                    updatedSources[item.targetId] = item.catalog.label
                    sourceLabels[item.targetId] = item.catalog.label
                }
            } else if (choice.include) {
                val source = runCatching {
                    CatalogSource(
                        item.targetId,
                        item.catalog.label,
                        item.catalog.url,
                        choice.credential.trim()
                    )
                }.getOrNull()
                if (source == null) {
                    problems += "Enter this box's catalog key before importing it."
                } else {
                    addedSources += source
                    availableSources[item.catalog.id] = item.targetId
                    sourceLabels[item.targetId] = item.catalog.label
                }
            }
        }
        if (local.view.sources.size + addedSources.size > ProfileCatalog.MAX_SOURCES) {
            problems += "This phone can keep at most ${ProfileCatalog.MAX_SOURCES} boxes."
        }
        val art = preview.art
        fun png(hash: String?): PortraitDescriptor? = hash?.let {
            val prepared = art.getValue(it)
            PortraitDescriptor(prepared.hash, prepared.size, "/assets/${prepared.hash}.png")
        }
        val raw = local.view.raw.toMutableList()
        val fallbacks = local.view.fallbacks.associateBy { it.profile.id }.toMutableMap()
        val overrides = local.view.overrides.associateBy { it.key }.toMutableMap()
        val animations = local.view.animations.toMutableMap()
        val gadgetUrls = raw.filter { it.sourceId != "manual" }
            .mapNotNull { it.profile.gadgetUrl }.toMutableSet()
        preview.profiles.forEach { item ->
            val choice = selection.profiles[item.profile.key] ?: ProfileChoice(false)
            val available = if (item.endpointUrl != null) {
                item.endpointUrl in availableEndpoints
            } else {
                item.sourceId in availableSources
            }
            if (!available || item.blocked) return@forEach
            val profile = item.profile
            val manual = item.endpointUrl != null
            val sourceId = if (manual) "manual" else item.targetKey.substringBefore(':')
            val sourceLabel = if (manual) "Saved on phone" else sourceLabels.getValue(sourceId)
            val profileId = item.targetKey.substringAfter(':')
            if (!item.existing) {
                if (!choice.include) return@forEach
                if (!manual) {
                    val url = profile.gadgetUrl
                    if (url != null && !gadgetUrls.add(url)) {
                        problems += "Two imported bots use the same connection."
                        return@forEach
                    }
                    raw += CatalogPage(
                        sourceId,
                        sourceLabel,
                        CatalogProfile(
                            profileId,
                            profile.name,
                            profile.gadgetUrl,
                            png(profile.portrait)
                        ),
                        CatalogStatus.OFFLINE,
                        0
                    )
                } else if (raw.none { it.key == item.targetKey }) {
                    raw += CatalogPage(
                        "manual",
                        "Saved on phone",
                        CatalogProfile(
                            profileId,
                            profile.name,
                            item.endpointUrl.takeIf { it.startsWith("wss:") },
                            null
                        ),
                        CatalogStatus.AVAILABLE,
                        0
                    )
                }
            } else if (!choice.usePictures) {
                return@forEach
            }
            if (item.existing && !manual && profile.portrait != null) {
                val index = raw.indexOfFirst { it.key == item.targetKey }
                val previous = raw[index]
                raw[index] = CatalogPage(
                    previous.sourceId,
                    previous.sourceLabel,
                    CatalogProfile(
                        previous.profile.id,
                        previous.profile.name,
                        previous.profile.gadgetUrl,
                        png(profile.portrait)
                    ),
                    previous.status,
                    previous.checkedAt
                )
            }
            // New profiles take all imported pictures; "use imported pictures" replaces only the
            // roles the archive supplies, mirroring the native picture actions.
            profile.fallbackPortrait?.let { hash ->
                val id = if (manual) {
                    profileId
                } else {
                    ClientSpeech.endpointId(Endpoint.parse(requireNotNull(profile.gadgetUrl)))
                }
                fallbacks[id] = CatalogPage(
                    "manual",
                    "Phone picture",
                    CatalogProfile(
                        id,
                        profile.name,
                        if (manual) {
                            item.endpointUrl.takeIf {
                                it.startsWith("wss:")
                            }
                        } else {
                            profile.gadgetUrl
                        },
                        png(hash)
                    ),
                    CatalogStatus.AVAILABLE,
                    0
                )
            }
            profile.watchPortrait?.let { hash ->
                overrides[item.targetKey] = CatalogPage(
                    sourceId,
                    sourceLabel,
                    CatalogProfile(profileId, profile.name, null, png(hash)),
                    CatalogStatus.AVAILABLE,
                    0
                )
                animations.remove(item.targetKey)
            }
            profile.animation?.let { hash ->
                val gif = art.getValue(hash)
                animations[item.targetKey] = AnimationDescriptor(
                    gif.hash,
                    gif.size,
                    art.getValue(requireNotNull(profile.animationFallback)).hash
                )
            }
        }
        val manualKeys = (local.endpoints.map { it.endpoint } + addedEndpoints.map { it.endpoint })
            .filter { it.url !in gadgetUrls }
            .map { "manual:" + ClientSpeech.endpointId(it) }
        val discovered = raw.filter { it.sourceId != "manual" }
        val roster = discovered.map { it.key } + manualKeys
        if (roster.size > ProfileCatalog.MAX_PROFILES) {
            problems += "This phone can show at most ${ProfileCatalog.MAX_PROFILES} bots."
        }
        val keys = roster.toSet()
        val remap = preview.profiles.associate { it.profile.key to (it.sameBot ?: it.targetKey) }
            .filterValues { it in keys }
        var unbound = 0
        fun bind(key: String?): String? {
            key ?: return null
            return remap[key] ?: run {
                unbound++
                null
            }
        }
        val actions = local.actions.orEmpty().toMutableList()
        preview.actions.forEach { item ->
            val choice = selection.actions[item.action.id] ?: ItemChoice(false)
            val action = item.action.copy(profileKey = bind(item.action.profileKey))
            if (item.existing == null) {
                if (choice.include) actions += action
            } else if (choice.replace) {
                actions[actions.indexOfFirst { it.id == action.id }] = action
            }
        }
        if (actions.size > QuickActions.MAX_ACTIONS) {
            problems += "This phone can keep at most ${QuickActions.MAX_ACTIONS} quick actions."
        }
        val templates = local.templates.toMutableList()
        preview.templates.forEach { item ->
            val choice = selection.templates[item.widget.id] ?: ItemChoice(false)
            val template = WidgetTemplate(
                item.widget.id,
                HomeWidgetPreset.valueOf(item.widget.preset.name),
                item.widget.slots.map(::bind),
                WidgetAppearance.valueOf(item.widget.theme.name)
            )
            if (item.existing == null) {
                if (choice.include) templates += template
            } else if (choice.replace) {
                templates[templates.indexOfFirst { it.id == template.id }] = template
            }
        }
        if (templates.size > HomeWidgetTemplateStore.MAX_TEMPLATES) {
            problems += "This phone can keep at most 128 widget layouts."
        }
        val incomingOrder = preview.snapshot.sourceOrder.mapNotNull { id ->
            if (id == "manual") id else availableSources[id]
        }
        val emptyLocal = local.endpoints.isEmpty() && local.view.sources.isEmpty() &&
            local.view.raw.isEmpty() && local.view.storedSourceOrder == null
        val order = if (emptyLocal) {
            (incomingOrder + "manual").distinct()
        } else {
            (local.view.sourceOrder + incomingOrder).distinct()
        }
        val actionsChanged = actions != local.actions.orEmpty()
        val finalPictures = (raw + fallbacks.values + overrides.values)
            .mapNotNull { it.profile.portrait?.sha256 }.toSet()
        val portraits = stores.catalog.portraits
        val newPictures = art.values.filter {
            it.format == MigrationArtworkFormat.PNG && it.hash in finalPictures &&
                portraits.file(it.hash) == null
        }.distinctBy { it.hash }
        // Originals stay protected until commit so a rollback never points at an evicted file.
        val pictureBytes = portraits.protectedBytes(portraits.hashes()) +
            newPictures.sumOf { it.size.toLong() }
        if (pictureBytes > PICTURE_BUDGET) {
            problems += "Not enough picture space. Choose fewer bots or pictures."
        }
        val newAnimations = animations.values.distinctBy { it.sha256 }.mapNotNull { descriptor ->
            if (stores.catalog.animations.file(descriptor) != null) return@mapNotNull null
            art.values.firstOrNull {
                it.format == MigrationArtworkFormat.GIF && it.hash == descriptor.sha256
            }?.let { descriptor to it }
        }
        val (keptCount, keptBytes) = stores.catalog.animations.usage(
            stores.catalog.animations.hashes()
        )
        if (keptCount + newAnimations.size > 32 ||
            keptBytes + newAnimations.sumOf { it.second.size.toLong() } > ANIMATION_BUDGET
        ) {
            problems += "Not enough animation space. Choose fewer animated pictures."
        }
        val newBytes = newPictures.sumOf { it.size.toLong() } +
            newAnimations.sumOf { it.second.size.toLong() }
        if (stores.freeBytes() < newBytes * 2 + FREE_SPACE_MARGIN) {
            problems += "Not enough free storage on this phone."
        }
        val plan = ImportPlan(
            addedEndpoints,
            updatedEndpoints,
            addedSources,
            updatedSources,
            raw,
            fallbacks.values.toList(),
            overrides.values.toList(),
            animations,
            order.takeIf { addedSources.isNotEmpty() },
            actions.takeIf { actionsChanged },
            templates,
            if (selection.useAppearance) preview.appearance else local.appearance,
            newPictures,
            newAnimations,
            unbound
        )
        val encoded = runCatching {
            EndpointProfiles.encode(local.endpoints + addedEndpoints)
            ProfileCatalog.encodeSources(local.view.sources + addedSources)
            ProfileCatalog.encodePages(plan.raw)
            ProfileCatalog.encodePages(plan.fallbacks)
            ProfileCatalog.encodePages(plan.overrides)
            PortraitAnimations.encode(plan.animations)
            plan.actions?.let(QuickActions::encode)
            HomeWidgetTemplateStore.encode(plan.templates)
            require(order.distinct().size == order.size)
        }
        if (encoded.isFailure && problems.isEmpty()) problems += "These choices exceed a limit."
        if (problems.isNotEmpty()) throw ImportPlanException(problems.distinct())
        return plan
    }

    /**
     * Applies [plan] through the owned stores under the exclusive gate, recording every original
     * first. Any failure rolls the stores back before reporting; a process death is undone by
     * [recover] before the next normal mutation.
     */
    fun commit(
        stores: SetupTransferStores,
        preview: ImportPreview,
        selection: ImportSelection
    ): ImportOutcome {
        requireTransferWorker()
        check(!recoveryProblem) { "An earlier import record needs attention." }
        val (templates, watch) = stores.catalog.importExclusive {
            check(SetupTransferGate.isExclusiveHeld)
            val journal = SetupJournalFile(stores.directory, stores.journalAlias)
            check(!journal.exists()) { "An earlier import record needs attention." }
            val current = LocalTransferState.read(stores)
            check(current.fingerprint == preview.local.fingerprint) {
                "Settings changed while you reviewed. Open the archive again."
            }
            val plan = plan(stores, preview, selection)
            applyJournaled(stores, current, plan)
            plan.templates.size - current.templates.size
        }
        return ImportOutcome(publishAfterCommit(stores, watch), templates)
    }

    /** Also used for QR: one durable commit point covers native writes and replay completion. */
    internal fun applyJournaled(
        stores: SetupTransferStores,
        current: LocalTransferState,
        plan: ImportPlan,
        replay: Pair<String, Long>? = null
    ) {
        check(SetupTransferGate.isExclusiveHeld)
        val journal = SetupJournalFile(stores.directory, stores.journalAlias)
        check(!journal.exists()) { "An earlier import record needs attention." }
        validateProjections(current, plan)
        val record = Record.of(current, plan, replay)
        journal.write(record.encode(committed = false))
        try {
            fault?.invoke(ImportStep.PREPARED)
            apply(stores, plan)
            journal.write(record.encode(committed = true))
        } catch (failure: Exception) {
            try {
                rollback(stores, record)
                journal.delete()
            } catch (rollbackFailure: Exception) {
                recoveryProblem = true
                SetupTransferGate.requireRecovery { recover(stores) }
                throw IllegalStateException("Import recovery needs attention.")
            }
            throw failure
        }
        fault?.invoke(ImportStep.COMMITTED)
        try {
            completeReplay(stores, record)
            journal.delete()
        } catch (failure: Exception) {
            recoveryProblem = true
            SetupTransferGate.requireRecovery { recover(stores) }
            throw IllegalStateException("Import saved; recovery needs attention.")
        }
    }

    /** Validate the complete published roster, not just the individual endpoint store. */
    private fun validateProjections(current: LocalTransferState, plan: ImportPlan) {
        val endpoints = current.endpoints + plan.addedEndpoints
        EndpointProfiles.encode(endpoints)
        ProfileCatalog.encodeSources(current.view.sources + plan.addedSources)
        val discovered = plan.raw.filter { it.sourceId != "manual" }
        val urls = discovered.mapNotNull { it.profile.gadgetUrl }.toSet()
        val manual = endpoints.filter { it.endpoint.url !in urls }.map {
            CatalogPage(
                "manual",
                "Saved on phone",
                CatalogProfile(
                    ClientSpeech.endpointId(it.endpoint),
                    it.label,
                    it.endpoint.url.takeIf { _ -> it.endpoint.isTls },
                    null
                ),
                CatalogStatus.AVAILABLE,
                0
            )
        }
        ProfileCatalog.encodePages(discovered + manual)
        ProfileCatalog.encodePages(plan.raw)
        ProfileCatalog.encodePages(plan.fallbacks)
        ProfileCatalog.encodePages(plan.overrides)
        PortraitAnimations.encode(plan.animations)
        plan.actions?.let(QuickActions::encode)
        HomeWidgetTemplateStore.encode(plan.templates)
    }

    private fun completeReplay(stores: SetupTransferStores, record: Record) {
        record.replay?.let { (id, expires) ->
            val now = System.currentTimeMillis() / 1000
            if (expires > now) SetupReplayJournal(stores.directory).spend(id, expires, now)
        }
    }

    private fun apply(stores: SetupTransferStores, plan: ImportPlan) {
        val catalog = stores.catalog
        val vault = catalog.vault
        fault?.invoke(ImportStep.ARTWORK)
        catalog.portraits.protect(
            (plan.raw + plan.fallbacks + plan.overrides).mapNotNull { it.profile.portrait?.sha256 }
                .toSet() + catalog.portraits.hashes()
        )
        plan.newPictures.forEach {
            val bytes = it.read()
            try {
                catalog.portraits.adopt(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        catalog.animations.protect(
            plan.animations.values.map { it.sha256 }.toSet() +
                catalog.animations.hashes()
        )
        plan.newAnimations.forEach { (descriptor, art) ->
            val bytes = art.read()
            try {
                catalog.animations.store(descriptor, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        fault?.invoke(ImportStep.ENDPOINTS)
        plan.addedEndpoints.forEach { stores.profiles.save(it) }
        plan.updatedEndpoints.forEach { (url, label, transport) ->
            val current = stores.profiles.profiles().single { it.endpoint.url == url }
            stores.profiles.save(
                EndpointProfile(current.endpoint, label, current.accessToken, transport)
            )
        }
        fault?.invoke(ImportStep.SOURCES)
        val sources = vault.sources().map { source ->
            plan.updatedSources[source.id]?.let {
                CatalogSource(source.id, it, source.url, source.credential)
            } ?: source
        } + plan.addedSources
        vault.saveSources(sources)
        fault?.invoke(ImportStep.PAGES)
        vault.savePages(plan.raw)
        fault?.invoke(ImportStep.FALLBACKS)
        vault.saveFallbacks(plan.fallbacks)
        fault?.invoke(ImportStep.OVERRIDES)
        vault.saveOverrides(plan.overrides)
        fault?.invoke(ImportStep.ANIMATIONS)
        vault.saveAnimations(plan.animations)
        fault?.invoke(ImportStep.ORDER)
        plan.sourceOrder?.let(vault::saveSourceOrder)
        fault?.invoke(ImportStep.ACTIONS)
        plan.actions?.let { stores.quickActions.save(it) }
        fault?.invoke(ImportStep.TEMPLATES)
        stores.templates.save(plan.templates)
        fault?.invoke(ImportStep.APPEARANCE)
        stores.appearance.set(plan.appearance)
    }

    /** Idempotent undo from the journal's originals and inverse credential-store operations. */
    private fun rollback(stores: SetupTransferStores, record: Record) {
        val catalog = stores.catalog
        val vault = catalog.vault
        record.addedEndpoints.forEach { url ->
            if (stores.profiles.profiles().any {
                    it.endpoint.url == url
                }
            ) {
                stores.profiles.remove(url)
            }
        }
        record.updatedEndpoints.forEach { (url, label, transport) ->
            stores.profiles.profiles().singleOrNull { it.endpoint.url == url }?.let { current ->
                stores.profiles.save(
                    EndpointProfile(current.endpoint, label, current.accessToken, transport)
                )
            }
        }
        vault.saveSources(
            vault.sources().filter { it.id !in record.addedSources }.map { source ->
                record.updatedSources[source.id]?.let {
                    CatalogSource(source.id, it, source.url, source.credential)
                } ?: source
            }
        )
        vault.savePages(ProfileCatalog.decodePages(record.raw))
        vault.saveFallbacks(ProfileCatalog.decodePages(record.fallbacks))
        vault.saveOverrides(ProfileCatalog.decodePages(record.overrides))
        val animations = PortraitAnimations.decode(record.animations)
        vault.saveAnimations(animations)
        record.sourceOrder?.let(vault::saveSourceOrder) ?: vault.clearSourceOrder()
        record.actions?.let { stores.quickActions.save(QuickActions.decode(it)) }
            ?: stores.quickActions.clearForRestore()
        stores.templates.restore(HomeWidgetTemplateStore.decode(record.templates))
        stores.appearance.set(record.appearance)
        val pictures = (
            ProfileCatalog.decodePages(record.raw) + ProfileCatalog.decodePages(record.fallbacks) +
                ProfileCatalog.decodePages(record.overrides)
            ).mapNotNull { it.profile.portrait?.sha256 }.toSet()
        catalog.portraits.protect(pictures)
        record.addedPictures.filter { it !in pictures }.forEach(catalog.portraits::discard)
        val gifs = animations.values.map { it.sha256 }.toSet()
        catalog.animations.protect(gifs)
        record.addedAnimations.filter { it !in gifs }.forEach(catalog.animations::discard)
    }

    /**
     * Runs exclusively before the first gated mutation after a restart. A prepared record is
     * rolled back; a committed one is finished. An unreadable record is kept and reported.
     */
    fun recover(stores: SetupTransferStores) {
        val journal = SetupJournalFile(stores.directory, stores.journalAlias)
        if (!journal.exists()) {
            recoveryProblem = false
            return
        }
        val record = runCatching { Record.decode(requireNotNull(journal.read())) }.getOrNull()
        if (record == null) {
            recoveryProblem = true
            error("An earlier import record needs attention.")
        }
        if (!record.committed) rollback(stores, record) else completeReplay(stores, record)
        journal.delete()
        recoveryProblem = false
        SetupTransferGate.afterRecovery {
            val catalog = try {
                stores.catalog.republish(preserveSettledArtwork = !record.committed)
            } catch (error: Exception) {
                CompletableFuture<Unit>().also { it.completeExceptionally(error) }
            }
            publishAfterCommit(stores, catalog)
        }
    }

    /** Owner decision for an unreadable record: keep current settings, remove the record. */
    fun discardUnreadableRecord(stores: SetupTransferStores) = SetupTransferGate.resolveRecovery {
        val journal = SetupJournalFile(stores.directory, stores.journalAlias)
        if (journal.exists() &&
            runCatching { Record.decode(requireNotNull(journal.read())) }.isFailure
        ) {
            journal.delete()
        }
        recoveryProblem = journal.exists()
    }

    /** Status for the transfer page: true when an unreadable record blocks import. */
    fun blocked(stores: SetupTransferStores): Boolean {
        if (runCatching { SetupTransferGate.ensureRecovered() }.isFailure) return true
        return recoveryProblem ||
            SetupJournalFile(stores.directory, stores.journalAlias).exists()
    }

    @Volatile internal var pendingPublication: SetupPublication? = null

    internal fun publishAfterCommit(
        stores: SetupTransferStores,
        catalog: CompletableFuture<Unit>?
    ): SetupPublication = SetupPublication(
        catalog ?: CompletableFuture.completedFuture(Unit),
        SetupPublication.attempt { stores.publishSetup() },
        SetupPublication.attempt { stores.quickActions.stored()?.let(stores.syncShortcuts) }
    ).also { pendingPublication = it }

    /** Stored metadata only. No import, replay admission, discovery, pairing or host request. */
    internal fun retryPublication(
        stores: SetupTransferStores,
        previous: SetupPublication
    ): SetupPublication {
        requireTransferWorker()
        fun failed(future: CompletableFuture<Unit>) =
            !future.isDone || future.isCompletedExceptionally || future.isCancelled
        val catalog = if (failed(previous.catalog)) {
            try {
                stores.catalog.republish()
            } catch (error: Exception) {
                CompletableFuture<Unit>().also { it.completeExceptionally(error) }
            }
        } else {
            previous.catalog
        }
        val setup = if (failed(previous.setup)) {
            SetupPublication.attempt {
                stores.publishSetup()
            }
        } else {
            previous.setup
        }
        val shortcuts = if (failed(previous.shortcuts)) {
            SetupPublication.attempt {
                stores.quickActions.stored()?.let(stores.syncShortcuts)
            }
        } else {
            previous.shortcuts
        }
        return SetupPublication(catalog, setup, shortcuts).also { pendingPublication = it }
    }

    private const val PICTURE_BUDGET = 8L * 1024 * 1024
    private const val ANIMATION_BUDGET = 8L * 1024 * 1024
    private const val FREE_SPACE_MARGIN = 8L * 1024 * 1024

    /** Journal content: credential-free originals and inverse operations for credential stores. */
    internal class Record(
        val committed: Boolean,
        val addedEndpoints: List<String>,
        val updatedEndpoints: List<Triple<String, String, TransportPreference>>,
        val addedSources: List<String>,
        val updatedSources: Map<String, String>,
        val raw: String,
        val fallbacks: String,
        val overrides: String,
        val animations: String,
        val sourceOrder: List<String>?,
        val actions: String?,
        val templates: String,
        val appearance: Appearance,
        val addedPictures: List<String>,
        val addedAnimations: List<String>,
        val replay: Pair<String, Long>? = null
    ) {
        fun encode(committed: Boolean): String = buildJsonObject {
            put("version", 1)
            put("committed", committed)
            put("added_endpoints", strings(addedEndpoints))
            put(
                "updated_endpoints",
                JsonArray(
                    updatedEndpoints.map { (url, label, transport) ->
                        buildJsonObject {
                            put("url", url)
                            put("label", label)
                            put("transport", transport.name)
                        }
                    }
                )
            )
            put("added_sources", strings(addedSources))
            put(
                "updated_sources",
                JsonObject(updatedSources.mapValues { JsonPrimitive(it.value) })
            )
            put("pages", raw)
            put("fallbacks", fallbacks)
            put("overrides", overrides)
            put("animations", animations)
            put("source_order", sourceOrder?.let(::strings) ?: JsonNull)
            put("actions", actions?.let(::JsonPrimitive) ?: JsonNull)
            put("templates", templates)
            put("appearance", appearance.name)
            put("added_pictures", strings(addedPictures))
            put("added_animations", strings(addedAnimations))
            put("replay_id", replay?.first?.let(::JsonPrimitive) ?: JsonNull)
            put("replay_expires", replay?.second?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()

        companion object {
            fun of(
                current: LocalTransferState,
                plan: ImportPlan,
                replay: Pair<String, Long>? = null
            ): Record {
                val originalLabels = current.endpoints.associateBy { it.endpoint.url }
                val originalSources = current.view.sources.associate { it.id to it.label }
                return Record(
                    false,
                    plan.addedEndpoints.map { it.endpoint.url },
                    plan.updatedEndpoints.map { (url, _, _) ->
                        val original = originalLabels.getValue(url)
                        Triple(url, original.label, original.transport)
                    },
                    plan.addedSources.map { it.id },
                    plan.updatedSources.keys.associateWith { originalSources.getValue(it) },
                    ProfileCatalog.encodePages(current.view.raw),
                    ProfileCatalog.encodePages(current.view.fallbacks),
                    ProfileCatalog.encodePages(current.view.overrides),
                    PortraitAnimations.encode(current.view.animations),
                    current.view.storedSourceOrder,
                    current.actions?.let(QuickActions::encode),
                    HomeWidgetTemplateStore.encode(current.templates),
                    current.appearance,
                    plan.newPictures.map { it.hash },
                    plan.newAnimations.map { it.first.sha256 },
                    replay
                )
            }

            fun decode(text: String): Record {
                val item = Json.parseToJsonElement(text) as JsonObject
                require(item.int("version") == 1)
                fun list(key: String) = (item.getValue(key) as JsonArray).map { it.text() }
                return Record(
                    (item.getValue("committed") as JsonPrimitive).content.toBooleanStrict(),
                    list("added_endpoints"),
                    (item.getValue("updated_endpoints") as JsonArray).map {
                        val value = it as JsonObject
                        Triple(
                            value.getValue("url").text(),
                            value.getValue("label").text(),
                            TransportPreference.valueOf(value.getValue("transport").text())
                        )
                    },
                    list("added_sources"),
                    (item.getValue("updated_sources") as JsonObject).mapValues { it.value.text() },
                    item.getValue("pages").text(),
                    item.getValue("fallbacks").text(),
                    item.getValue("overrides").text(),
                    item.getValue("animations").text(),
                    (item.getValue("source_order") as? JsonArray)?.map { it.text() },
                    item.getValue("actions").takeIf { it != JsonNull }?.text(),
                    item.getValue("templates").text(),
                    Appearance.valueOf(item.getValue("appearance").text()),
                    list("added_pictures"),
                    list("added_animations"),
                    item["replay_id"]?.takeIf { it != JsonNull }?.let {
                        it.text() to
                            (item.getValue("replay_expires") as JsonPrimitive).content.toLong()
                    }
                )
            }

            private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
            private fun JsonElement.text(): String =
                (this as JsonPrimitive).also { require(it.isString && it != JsonNull) }.content
            private fun JsonObject.int(key: String): Int =
                (getValue(key) as JsonPrimitive).also { require(!it.isString) }.content.toInt()
        }
    }
}
