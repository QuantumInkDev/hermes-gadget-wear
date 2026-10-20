package dev.quantumink.hermesgadget.mobile

import android.graphics.Color
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.WidgetAppearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetConfig
import dev.quantumink.hermesgadget.mobile.widgets.WidgetTemplate
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTransferFixRoundOneTest {
    private val fixtures = mutableListOf<TransferFixture>()
    private fun fixture() = TransferFixture().also(fixtures::add)

    @After fun close() {
        SetupImport.fault = null
        SetupImport.pendingPublication = null
        SetupTransferGate.resetForProcessFixture(null)
        SetupTransferGate.ensureRecovered()
        fixtures.forEach { it.quiesce() }
        fixtures.forEach { it.close() }
    }
    private fun archive(stores: SetupTransferStores) = SetupExport.build(stores).use {
        MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
    }
    private fun interrupt(stores: SetupTransferStores, envelope: ByteArray) {
        requireNotNull(openPreview(stores, envelope, PASSPHRASE)).use { preview ->
            SetupImport.fault = { if (it == ImportStep.PAGES) throw InterruptedFixture() }
            assertThrows(InterruptedFixture::class.java) {
                SetupImport.commit(stores, preview, preview.defaults())
            }
            SetupImport.fault = null
        }
    }
    private class InterruptedFixture : Error()

    @Test fun pendingJournalRecoveryThroughRepositoryEntryDoesNotWaitUnderMonitor() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.partial()
        val before = target.state(stores)
        interrupt(stores, archive(source))
        SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit { stores.catalog.transferRead { stores.catalog.savedPages() } }
        try {
            future.get(3, TimeUnit.SECONDS)
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
        target.quiesce()
        assertEquals(before, target.state(stores))
        assertEquals(1, target.published.size)
    }

    @Test fun qrCombinedRosterCapacityRejectsBeforeJournalAndReplay() {
        val target = fixture()
        val stores = target.stores()
        stores.catalog.saveSource(CatalogSource("home", "Home", CATALOG_URL, "local-key"))
        stores.catalog.vault.savePages(
            (0 until 32).map {
                CatalogPage(
                    "home",
                    "Home",
                    CatalogProfile("p$it", "Bot $it", null, null),
                    CatalogStatus.AVAILABLE,
                    0
                )
            }
        )
        stores.catalog.savedPages()
        val before = target.state(stores)
        val now = System.currentTimeMillis() / 1000
        val metadata = SetupMetadataCodec.create("New", SetupEndpoint("New", ALPHA_URL), null, now)
        val review = SetupMetadataCodec.decode(SetupMetadataCodec.encode(metadata), now)
        assertThrows(IllegalArgumentException::class.java) {
            SetupQrImport.add(stores, review, QrAddChoice(), now)
        }
        assertEquals(before, target.state(stores))
        assertFalse(SetupReplayJournal(stores.directory).spent(metadata.setupId, now))
        assertFalse(java.io.File(stores.directory, "import-journal").exists())
    }

    @Test fun preparedRollbackPreservesAllSettledArtAfterPublicationCompletes() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.seed()
        stores.catalog.portraits.adopt(
            stores.catalog.portraits.prepare(TransferFixture.png(Color.CYAN, 36))
        )
        val bytes = GifPortrait.normalize(TransferFixture.gif().also { it[13] = 61 }).bytes
        stores.catalog.animations.store(
            AnimationDescriptor(sha256Hex(bytes), bytes.size, "0".repeat(64)),
            bytes
        )
        val before = target.state(stores)
        interrupt(stores, archive(source))
        SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
        SetupTransferGate.ensureRecovered()
        target.quiesce()
        assertEquals(before, target.state(stores))
    }

    @Test fun equalRawActionKeysWithDifferentSourceUrlsAreNotIdentical() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.seed()
        stores.catalog.saveSource(
            CatalogSource("home", "Other", "https://other.example.invalid/catalog", "local-key")
        )
        stores.catalog.vault.savePages(
            stores.catalog.vault.pages().map { p ->
                if (p.sourceId ==
                    "manual"
                ) {
                    p
                } else {
                    CatalogPage(
                        p.sourceId,
                        p.sourceLabel,
                        CatalogProfile(
                            p.profile.id,
                            p.profile.name,
                            "wss://other-${p.profile.id}.example.invalid/gadget",
                            p.profile.portrait
                        ),
                        p.status,
                        p.checkedAt
                    )
                }
            }
        )
        requireNotNull(openPreview(stores, archive(source), PASSPHRASE)).use { preview ->
            val item = preview.actions.first()
            assertEquals("home-2", preview.sources.single().targetId)
            assertFalse(item.identical)
            val defaults = preview.defaults()
            assertEquals(null, SetupImport.plan(stores, preview, defaults).actions)
            val selected = defaults.copy(
                sources = mapOf("home" to SourceChoice(true, "independent-key"))
            )
            assertEquals("home-2:one", preview.effectiveKey(item.action.profileKey, selected))
            assertFalse(item.identical(preview.effectiveKey(item.action.profileKey, selected)))
            assertTrue(
                preview.recipient(item.existing?.profileKey).contains("other-one.example.invalid")
            )
            assertTrue(
                preview.recipient(
                    preview.effectiveKey(item.action.profileKey, selected)
                ).contains(ONE_URL)
            )
            val replacement = selected.copy(
                actions =
                selected.actions + (item.action.id to ItemChoice(false, true))
            )
            assertEquals(
                "home-2:one",
                SetupImport.plan(stores, preview, replacement).actions!!.first().profileKey
            )
        }
    }

    @Test fun differentRawKeysRemappingToLocalRecipientAreIdentical() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.stores()
        stores.catalog.saveSource(CatalogSource("local", "Local", CATALOG_URL, "local-key"))
        stores.catalog.vault.savePages(
            listOf(
                CatalogPage(
                    "local",
                    "Local",
                    CatalogProfile("one", "One", ONE_URL, null),
                    CatalogStatus.AVAILABLE,
                    0
                )
            )
        )
        stores.quickActions.save(
            listOf(TransferFixture.actions().first().copy(profileKey = "local:one"))
        )
        requireNotNull(openPreview(stores, archive(source), PASSPHRASE)).use {
            assertTrue(it.actions.first().identical)
        }
    }

    @Test fun setupTimeoutAndShortcutThrowReachImportOutcomeWithoutUndoingCommit() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.stores()
        target.setupAction = { throw TimeoutException("synthetic timeout") }
        target.shortcutAction = { throw IllegalStateException("synthetic shortcut failure") }
        requireNotNull(openPreview(stores, archive(source), PASSPHRASE)).use {
            val outcome = SetupImport.commit(stores, it, it.defaults())
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                outcome.watch?.get(3, TimeUnit.SECONDS)
            }
        }
        assertTrue(stores.profiles.profiles().isNotEmpty())
        assertFalse(java.io.File(stores.directory, "import-journal").exists())
    }

    @Test fun eachPublicationChannelReportsFailureAndRetriesMetadataOnly() {
        val envelope = archive(fixture().seed())
        for (channel in listOf("catalog", "setup", "shortcuts")) {
            val target = fixture()
            val stores = target.stores()
            when (channel) {
                "catalog" ->
                    target.catalogAction =
                        { throw TimeoutException("synthetic catalog timeout") }
                "setup" ->
                    target.setupAction =
                        { throw TimeoutException("synthetic setup timeout") }
                else ->
                    target.shortcutAction =
                        { throw IllegalStateException("synthetic shortcut failure") }
            }
            requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE)).use {
                val outcome = SetupImport.commit(stores, it, it.defaults())
                val pending = outcome.publication.await()
                assertEquals(1, pending.size)
                assertTrue(
                    pending.single().contains(
                        when (channel) {
                            "catalog" -> "pictures"
                            "setup" -> "connections"
                            else -> "shortcuts"
                        }
                    )
                )
                val committed = target.state(stores)
                target.catalogAction = {}
                target.setupAction = {}
                target.shortcutAction = {}
                SetupImport.fault = { error("Retry must not enter import or admission") }
                val retried = SetupImport.retryPublication(stores, outcome.publication)
                assertTrue(retried.await().isEmpty())
                assertEquals(committed, target.state(stores))
                SetupImport.fault = null
            }
        }
    }

    @Test fun qrCommitReportsSetupTimeoutAndRetryDoesNotSpendOrAddAgain() {
        val target = fixture()
        val stores = target.stores()
        target.setupAction = { throw TimeoutException("synthetic setup timeout") }
        val now = System.currentTimeMillis() / 1000
        val metadata = SetupMetadataCodec.create(
            "Alpha",
            SetupEndpoint("Alpha", ALPHA_URL),
            null,
            now
        )
        val review = SetupMetadataCodec.decode(SetupMetadataCodec.encode(metadata), now)
        val outcome = SetupQrImport.addReviewed(stores, review, QrAddChoice(), now)
        assertEquals(QrAddResult.ADDED, outcome.result)
        assertEquals(setOf("watch connections"), outcome.publication!!.await())
        assertTrue(SetupReplayJournal(stores.directory).spent(metadata.setupId, now))
        val committed = target.state(stores)
        val replay = java.io.File(stores.directory, "replay.json").readBytes()
        target.setupAction = {}
        SetupImport.fault = { error("Retry must not admit QR again") }
        assertTrue(SetupImport.retryPublication(stores, outcome.publication).await().isEmpty())
        assertEquals(committed, target.state(stores))
        org.junit.Assert.assertArrayEquals(
            replay,
            java.io.File(stores.directory, "replay.json").readBytes()
        )
        SetupImport.fault = null
    }

    @Test fun synchronousCatalogEnqueueFailureStillReturnsCommittedPublicationOutcome() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.stores()
        requireNotNull(openPreview(stores, archive(source), PASSPHRASE)).use {
            target.quiesce()
            val outcome = SetupImport.commit(stores, it, it.defaults())
            assertEquals(setOf("watch profiles and pictures"), outcome.publication.await())
            assertTrue(stores.profiles.profiles().isNotEmpty())
            val committed = target.state(stores)
            assertTrue(
                SetupImport.retryPublication(target.stores(), outcome.publication).await().isEmpty()
            )
            assertEquals(committed, target.state(stores))
        }
    }

    @Test fun committedQrCatalogRecoveryMatchesTheNormalCommittedProjection() {
        val target = fixture()
        val stores = target.stores()
        val reference = fixture()
        val normal = reference.stores()
        val now = System.currentTimeMillis() / 1000
        val metadata = SetupMetadataCodec.create(
            "Home",
            null,
            SetupCatalog("home", "Home", CATALOG_URL),
            now
        )
        val review = SetupMetadataCodec.decode(SetupMetadataCodec.encode(metadata), now)
        val choice = QrAddChoice(catalogCredential = "independent-key")
        val expected = SetupQrImport.addReviewed(normal, review, choice, now)
        assertTrue(expected.publication!!.await().isEmpty())
        SetupImport.fault = { if (it == ImportStep.COMMITTED) throw InterruptedFixture() }
        assertThrows(InterruptedFixture::class.java) {
            SetupQrImport.addReviewed(stores, review, choice, now)
        }
        SetupImport.fault = null
        SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
        stores.catalog.transferRead { Unit }
        target.quiesce()
        assertEquals(reference.state(normal), target.state(stores))
        assertEquals(1, target.published.size)
        assertEquals(1, target.setupPublications.get())
        assertTrue(SetupReplayJournal(stores.directory).spent(metadata.setupId, now))
    }

    @Test fun widgetExportReportsLayoutsBeyondTheFormatCap() {
        val target = fixture()
        val stores = target.stores()
        stores.templates.save(
            (0 until 128).map {
                WidgetTemplate(
                    UUID.randomUUID().toString(),
                    HomeWidgetPreset.FOCUS,
                    listOf(null),
                    WidgetAppearance.SYSTEM
                )
            }
        )
        stores.widgets.save(
            111,
            WidgetConfig(HomeWidgetPreset.FOCUS, listOf(null), WidgetAppearance.SYSTEM),
            0
        )
        target.placed += 111 to HomeWidgetPreset.FOCUS
        SetupExport.build(stores).use {
            assertEquals(128, it.summary.widgets)
            assertEquals(1, it.summary.skipped)
            assertEquals(1, it.summary.omittedWidgets)
        }
    }
}
