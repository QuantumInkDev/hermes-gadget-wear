package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.os.Looper
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetStore
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetTemplateStore
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgets
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.MigrationArtwork
import dev.quantumink.hermesgadget.protocol.MigrationArtworkFormat
import dev.quantumink.hermesgadget.protocol.MigrationProfile
import dev.quantumink.hermesgadget.protocol.MigrationSnapshot
import dev.quantumink.hermesgadget.protocol.MigrationTheme
import dev.quantumink.hermesgadget.protocol.MigrationWidget
import dev.quantumink.hermesgadget.protocol.MigrationWidgetPreset
import dev.quantumink.hermesgadget.protocol.MigrationWidgetTheme
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The exact stores configuration transfer may read or write. Identity, enrollment, provider,
 * speech, tile, history and relay stores are deliberately absent from this bundle.
 */
internal class SetupTransferStores(
    val context: Context,
    val catalog: CatalogRepository,
    val quickActions: QuickActionStore,
    val templates: HomeWidgetTemplateStore,
    val appearance: AppearancePreferences,
    val widgets: HomeWidgetStore,
    val directory: File,
    val journalAlias: String,
    val widgetInstances: () -> List<Pair<Int, HomeWidgetPreset>>,
    /** Watch endpoint list only; never catalog discovery or a network request. */
    val publishSetup: () -> Unit,
    val syncShortcuts: (List<QuickAction>) -> Unit,
    /** Space the platform can provide, including clearable cache; 0 when unknown. */
    val freeBytes: () -> Long = {
        runCatching {
            val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
        }.getOrDefault(0L)
    }
) {
    val profiles: ProfileVault get() = catalog.setup

    companion object {
        fun get(context: Context): SetupTransferStores {
            val app = context.applicationContext
            val catalog = CatalogRepository.get(app)
            return SetupTransferStores(
                app,
                catalog,
                QuickActionStore(app),
                HomeWidgetTemplateStore(File(app.filesDir, "widget-templates")),
                AppearancePreferences(app),
                HomeWidgets.store(app),
                File(app.filesDir, "setup-transfer"),
                "quantumink.relay.setup-transfer.v1",
                { HomeWidgets.instances(app) },
                { ProfileSync.publishSetup(app, catalog.setup) },
                {
                    check(QuickActionShortcuts.sync(app, it) == null) {
                        "Launcher shortcuts need retry."
                    }
                }
            )
        }
    }
}

/** KDF, archive, image and store work must never run on the main thread. */
internal fun requireTransferWorker() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Transfer work must run on a worker." }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** What an export contains; counts only, never addresses, for the confirmation screen. */
internal class ExportSummary(
    val endpoints: Int,
    val sources: Int,
    val profiles: Int,
    val pictures: Int,
    val animations: Int,
    val actions: Int,
    val widgets: Int,
    val appearance: Appearance,
    val skipped: Int,
    val omittedWidgets: Int = 0
)

/** Owned snapshot and private, immutable staged art. Only one image is loaded at a time. */
internal class ExportBundle(
    val snapshot: MigrationSnapshot,
    files: Map<String, File>,
    val summary: ExportSummary,
    private val directory: File
) : AutoCloseable {
    private val files = files.toMap()
    val artwork: Map<String, ByteArray> = object : AbstractMap<String, ByteArray>() {
        override val keys: Set<String> get() = files.keys
        override val size: Int get() = files.size
        override fun get(key: String): ByteArray? = files[key]?.inputStream()?.use {
            require(it.channel.size() in 1..ProfileCatalog.MAX_PORTRAIT_BYTES.toLong())
            it.readBytes()
        }
        override val entries: Set<Map.Entry<String, ByteArray>> get() =
            object : AbstractSet<Map.Entry<String, ByteArray>>() {
                override val size: Int get() = files.size
                override fun iterator(): Iterator<Map.Entry<String, ByteArray>> =
                    files.keys.asSequence()
                        .map {
                            java.util.AbstractMap.SimpleImmutableEntry(it, getValue(it))
                        }.iterator()
            }
    }
    override fun close() {
        // Screen disposal is on the main thread; deletion of a staged snapshot is still IO.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.execute { directory.deleteRecursively() }
        } else {
            directory.deleteRecursively()
        }
    }
    companion object {
        private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { action ->
            Thread(action, "setup-export-cleanup").apply { isDaemon = true }
        }
    }
}

internal object SetupExport {
    private val cleaned = mutableSetOf<String>()

    /** Allowlisted projection through the owned store APIs only. IO worker. */
    fun build(stores: SetupTransferStores): ExportBundle {
        requireTransferWorker()
        return stores.catalog.transferRead { snapshot(stores) }
    }

    private fun snapshot(stores: SetupTransferStores): ExportBundle {
        val cache = stores.context.cacheDir
        synchronized(cleaned) {
            if (cleaned.add(cache.absolutePath)) {
                // Only once per process/path, before any new bundle exists: retire staging left
                // by a prior process death without deleting another live confirmation snapshot.
                cache.listFiles().orEmpty().filter {
                    it.isDirectory && it.name.startsWith("setup-export-")
                }.forEach { it.deleteRecursively() }
            }
        }
        val stage = File(cache, "setup-export-${UUID.randomUUID()}")
        check(stage.mkdirs())
        return try {
            snapshot(stores, stage)
        } catch (failure: Exception) {
            stage.deleteRecursively()
            throw failure
        }
    }

    private fun snapshot(stores: SetupTransferStores, stage: File): ExportBundle {
        var skipped = 0
        val view = stores.catalog.transferView()
        val endpoints = stores.profiles.profiles().mapNotNull { profile ->
            runCatching { profile to SetupEndpoint.from(profile) }.getOrNull()
                .also { if (it == null) skipped++ }
        }
        val manualIds = endpoints.associate { (profile, setup) ->
            "manual:" + ClientSpeech.endpointId(profile.endpoint) to setup
        }
        val catalogs = view.sources.mapNotNull { source ->
            runCatching { SetupCatalog.from(source) }.getOrNull().also { if (it == null) skipped++ }
        }
        val sourceIds = catalogs.map { it.id }.toSet()
        val fallbacks = view.fallbacks.associate { it.profile.id to it.profile.portrait }
        val overrides = view.overrides.associate { it.key to it.profile.portrait }
        val artwork = linkedMapOf<String, File>()
        fun stageArt(hash: String, bytes: ByteArray) {
            try {
                artwork[hash] = File(stage, hash).also { it.writeBytes(bytes) }
            } finally {
                bytes.fill(0)
            }
        }
        val formats = linkedMapOf<String, MigrationArtworkFormat>()
        fun png(hash: String?): String? {
            hash ?: return null
            if (formats[hash] == MigrationArtworkFormat.PNG) return hash
            val bytes = stores.catalog.portraitFile(hash)?.readBytes() ?: return null
            if (bytes.size > ProfileCatalog.MAX_PORTRAIT_BYTES || sha256Hex(bytes) != hash) {
                return null
            }
            stageArt(hash, bytes)
            formats[hash] = MigrationArtworkFormat.PNG
            return hash
        }
        val profiles = view.raw.filter { it.status != CatalogStatus.WITHDRAWN }.mapNotNull { page ->
            val manual = page.sourceId == "manual"
            if (manual && page.key !in manualIds) return@mapNotNull null
            if (!manual && page.sourceId !in sourceIds) return@mapNotNull null
            val fallbackId = if (manual) {
                page.profile.id
            } else {
                page.profile.gadgetUrl?.let { ClientSpeech.endpointId(Endpoint.parse(it)) }
            }
            val animation = view.animations[page.key]?.let { descriptor ->
                val fallback = png(descriptor.fallbackSha256)
                val bytes = stores.catalog.animations.file(descriptor)?.readBytes()
                if (fallback == null || bytes == null || sha256Hex(bytes) != descriptor.sha256 ||
                    runCatching { GifPortrait.validate(bytes) }.isFailure
                ) {
                    null
                } else {
                    stageArt(descriptor.sha256, bytes)
                    formats[descriptor.sha256] = MigrationArtworkFormat.GIF
                    descriptor
                }
            }
            runCatching {
                MigrationProfile(
                    page.key,
                    page.profile.name,
                    page.profile.gadgetUrl,
                    portrait = if (manual) null else png(page.profile.portrait?.sha256),
                    fallbackPortrait = png(fallbackId?.let { fallbacks[it]?.sha256 }),
                    watchPortrait = png(overrides[page.key]?.sha256),
                    animation = animation?.sha256,
                    animationFallback = animation?.fallbackSha256
                )
            }.getOrNull().also { if (it == null) skipped++ }
        }
        val keys = profiles.map { it.key }.toSet()
        val referenced = profiles.flatMap {
            listOf(
                it.portrait,
                it.fallbackPortrait,
                it.watchPortrait,
                it.animation,
                it.animationFallback
            )
        }.filterNotNull().toSet()
        // Pictures read for a profile that was later skipped must not travel unreferenced.
        artwork.keys.retainAll(referenced)
        // Read-only: defaults that were never saved are not the owner's actions.
        val actions = stores.quickActions.stored().orEmpty().map { action ->
            if (action.profileKey == null || action.profileKey in keys) {
                action
            } else {
                action.copy(profileKey = null)
            }
        }
        fun slots(values: List<String?>) = values.map { it?.takeIf { key -> key in keys } }
        val placed = stores.widgetInstances().mapNotNull { (id, preset) ->
            val config = stores.widgets.config(id)?.takeIf { it.preset == preset }
                ?: return@mapNotNull null
            MigrationWidget(
                UUID.randomUUID().toString(),
                MigrationWidgetPreset.valueOf(preset.name),
                slots(config.slots),
                MigrationWidgetTheme.valueOf(config.appearance.name)
            )
        }
        val saved = stores.templates.templates().map { template ->
            MigrationWidget(
                template.id,
                MigrationWidgetPreset.valueOf(template.preset.name),
                slots(template.slots),
                MigrationWidgetTheme.valueOf(template.appearance.name)
            )
        }
        val allWidgets = (placed + saved).distinctBy { it.id }
        val widgets = allWidgets.take(128)
        skipped += allWidgets.size - widgets.size
        val mode = stores.appearance.mode.value
        val snapshot = MigrationSnapshot(
            endpoints = endpoints.map { it.second },
            catalogs = catalogs,
            profiles = profiles,
            sourceOrder = view.sourceOrder.filter { it == "manual" || it in sourceIds },
            profileOrder = profiles.map { it.key },
            appearance = MigrationTheme.valueOf(mode.name),
            actions = actions,
            widgets = widgets,
            artwork = artwork.map { (hash, file) ->
                MigrationArtwork(hash, file.length().toInt(), formats.getValue(hash))
            }
        )
        return ExportBundle(
            snapshot,
            artwork,
            ExportSummary(
                endpoints.size,
                catalogs.size,
                profiles.size,
                artwork.count { formats[it.key] == MigrationArtworkFormat.PNG },
                artwork.count { formats[it.key] == MigrationArtworkFormat.GIF },
                actions.size,
                widgets.size,
                mode,
                skipped,
                allWidgets.size - widgets.size
            ),
            stage
        )
    }
}
