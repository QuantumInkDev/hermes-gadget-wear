package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.AnimationCache
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetStore
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetTemplateStore
import dev.quantumink.hermesgadget.mobile.widgets.WidgetAppearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetConfig
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.PortraitAnimations
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import dev.quantumink.hermesgadget.protocol.TransportPreference
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Synthetic credential sentinels; any appearance in an archive or another install is a leak. */
internal const val TOKEN_ALPHA = "SENTINEL-TOKEN-ALPHA-7f3a"
internal const val TOKEN_BETA = "SENTINEL-TOKEN-BETA-91cc"
internal const val CATALOG_SECRET = "SENTINEL-CATALOG-KEY-55e1"
internal val PASSPHRASE = "synthetic passphrase for N10 task2".toCharArray()

internal const val ALPHA_URL = "wss://alpha.example.invalid/gadget"
internal const val BETA_URL = "ws://192.168.40.20:8765/gadget"
internal const val CATALOG_URL = "https://catalog.example.invalid/hermes"
internal const val ONE_URL = "wss://one.example.invalid/gadget"
internal const val TWO_URL = "wss://two.example.invalid/gadget"

internal fun manualKey(url: String) =
    "manual:" + ClientSpeech.endpointId(Endpoint.parse(url, true, true))

/** One isolated synthetic installation: own directories, preferences and Keystore aliases. */
internal class TransferFixture(val tag: String = UUID.randomUUID().toString()) : AutoCloseable {
    val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(target.cacheDir, "transfer-$tag").apply { mkdirs() }
    private val prefix = "transfer-$tag-"
    val context: Context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
    }
    private val alias = "test.transfer.$tag"
    val aliases = listOf(
        ".catalog",
        ".profiles",
        ".actions",
        ".journal",
        ".identity",
        ".provider"
    ).map {
        alias +
            it
    }
    val published = CopyOnWriteArrayList<List<CatalogPage>>()
    val setupPublications = AtomicInteger()
    val placed = mutableListOf<Pair<Int, HomeWidgetPreset>>()
    var setupAction: () -> Unit = {}
    var shortcutAction: () -> Unit = {}
    var catalogAction: () -> Unit = {}
    var free: Long = Long.MAX_VALUE
    private val repositories = CopyOnWriteArrayList<CatalogRepository>()

    /** New store objects over the same files: also models a process restart. */
    fun stores(): SetupTransferStores {
        val repository = CatalogRepository(
            context,
            CatalogVault(context, File(root, "catalog-vault"), aliases[0]),
            ProfileVault(context, File(root, "profile-vault"), aliases[1]),
            PortraitStore(File(root, "catalog-portraits")),
            AnimationCache(File(root, "catalog-animations")),
            fetchProfiles = { error("No network in transfer fixtures") },
            fetchPortrait = { _, _, _ -> error("No network in transfer fixtures") },
            publish = { pages, _ ->
                catalogAction()
                published += pages
            }
        ).also(repositories::add)
        return SetupTransferStores(
            context,
            repository,
            QuickActionStore(context, File(root, "quick-actions"), aliases[2]),
            HomeWidgetTemplateStore(File(root, "widget-templates")),
            AppearancePreferences(context),
            HomeWidgetStore(File(root, "home-widgets")),
            File(root, "setup-transfer"),
            aliases[3],
            { placed.toList() },
            {
                setupAction()
                setupPublications.incrementAndGet()
            },
            { shortcutAction() },
            { free }
        )
    }

    /** A realistic source installation using only the native picture and store flows. */
    fun seed(appearance: Appearance = Appearance.MOONLIGHT): SetupTransferStores {
        val s = stores()
        val alpha = EndpointProfile(Endpoint.parse(ALPHA_URL), "Alpha", TOKEN_ALPHA)
        val beta = EndpointProfile(
            Endpoint.parse(BETA_URL, privateNetwork = true, cleartextAccepted = true),
            "Beta LAN",
            TOKEN_BETA,
            TransportPreference.DIRECT
        )
        s.profiles.save(alpha)
        s.profiles.save(beta)
        s.catalog.saveSource(CatalogSource("home", "Home box", CATALOG_URL, CATALOG_SECRET))
        val box = s.catalog.portraits.normalize(png(Color.RED, 64))
        s.catalog.vault.savePages(
            listOf(
                CatalogPage(
                    "home",
                    "Home box",
                    CatalogProfile("one", "Bot One", ONE_URL, box),
                    CatalogStatus.AVAILABLE,
                    1
                ),
                CatalogPage(
                    "home",
                    "Home box",
                    CatalogProfile("two", "Bot Two", TWO_URL, null),
                    CatalogStatus.AVAILABLE,
                    1
                )
            )
        )
        s.catalog.savedPages()
        s.catalog.selectPhoto(alpha, png(Color.BLUE, 300))
        s.catalog.selectPhoto(
            EndpointProfile(Endpoint.parse(TWO_URL), "Bot Two", ""),
            png(Color.YELLOW, 48)
        )
        s.catalog.selectWatchPhoto("home:one", png(Color.GREEN, 40))
        s.catalog.selectWatchPhoto(manualKey(ALPHA_URL), gif())
        s.quickActions.save(actions())
        val keys = listOf(manualKey(ALPHA_URL), "home:one", "home:two", manualKey(BETA_URL))
        HomeWidgetPreset.entries.forEachIndexed { index, preset ->
            val id = 1001 + index
            val slots = List(preset.capacity) { slot ->
                if (slot < keys.size &&
                    slot != index % keys.size
                ) {
                    keys[(slot + index) % keys.size]
                } else {
                    null
                }
            }
            s.widgets.save(
                id,
                WidgetConfig(preset, slots, WidgetAppearance.entries[index % 4]),
                0
            )
            placed += id to preset
        }
        s.appearance.set(appearance)
        return s
    }

    /** An existing installation that overlaps the source only partly and has no pictures. */
    fun partial(): SetupTransferStores {
        val s = stores()
        s.profiles.save(EndpointProfile(Endpoint.parse(ALPHA_URL), "Alpha local", "LOCAL-TOKEN"))
        s.quickActions.save(listOf(actions()[0].copy(title = "Local morning")))
        s.appearance.set(Appearance.LIGHT)
        s.catalog.savedPages()
        return s
    }

    /**
     * Test-owned capture of every value the transfer may touch, including credentials, consent
     * and stored file bytes. Independent of the implementation's own journal.
     */
    fun state(s: SetupTransferStores = stores()): Map<String, String> {
        val vault = s.catalog.vault
        fun files(name: String) = File(root, name).listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".new") && !it.name.endsWith(".bak") }
            .sortedBy { it.name }.joinToString("\n") { it.name + "=" + sha256Hex(it.readBytes()) }
        return linkedMapOf(
            "endpoints" to s.profiles.profiles().joinToString("\n") {
                listOf(
                    it.endpoint.url,
                    it.label,
                    it.accessToken,
                    it.endpoint.privateNetwork,
                    it.endpoint.cleartextAccepted,
                    it.transport
                ).joinToString("|")
            },
            "sources" to vault.sources().joinToString("\n") {
                listOf(it.id, it.label, it.url, it.credential).joinToString("|")
            },
            "order" to vault.sourceOrder().toString(),
            "pages" to ProfileCatalog.encodePages(vault.pages()),
            // Keyed records: list order carries no meaning, so compare them sorted.
            "fallbacks" to ProfileCatalog.encodePages(vault.fallbacks().sortedBy { it.profile.id }),
            "overrides" to ProfileCatalog.encodePages(vault.overrides().sortedBy { it.key }),
            "animations" to PortraitAnimations.encode(vault.animations().toSortedMap()),
            // stored(), not load(): capturing state must never seed default actions.
            "actions" to s.quickActions.stored()?.let(QuickActions::encode).toString(),
            "templates" to HomeWidgetTemplateStore.encode(s.templates.templates()),
            "appearance" to s.appearance.mode.value.name,
            "widgets" to placed.joinToString("\n") { (id, _) -> "$id=" + s.widgets.config(id) },
            "portraits" to files("catalog-portraits"),
            "gifs" to files("catalog-animations")
        )
    }

    /** Stops every repository worker; later stores() calls create new ones. */
    fun quiesce() {
        repositories.forEach { it.close() }
        repositories.clear()
    }

    /** Callers quiesce every fixture first so no publication still uses a deleted alias. */
    override fun close() {
        quiesce()
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            aliases.forEach { if (containsAlias(it)) deleteEntry(it) }
        }
        target.deleteSharedPreferences(prefix + "appearance-v1")
    }

    companion object {
        fun actions() = listOf(
            QuickAction(
                "00000000-0000-4000-8000-0000000000a1",
                "home:one",
                "Morning check",
                "Summarize {{topic}} for me."
            ),
            QuickAction(
                "00000000-0000-4000-8000-0000000000a2",
                manualKey(ALPHA_URL),
                "Alpha status",
                "Report status."
            ),
            QuickAction("00000000-0000-4000-8000-0000000000a3", null, "Free prompt", "Hello.")
        )

        fun png(color: Int, size: Int, noise: Boolean = false, seed: Int = 1): ByteArray {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                if (noise) {
                    val random = Random(seed)
                    val pixels = IntArray(size * size) {
                        0xff000000.toInt() or
                            random.nextInt(0xffffff)
                    }
                    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                } else {
                    bitmap.eraseColor(color)
                }
                return ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        fun gif(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
            .open("original-two-frame.gif").use { it.readBytes() }
    }
}
