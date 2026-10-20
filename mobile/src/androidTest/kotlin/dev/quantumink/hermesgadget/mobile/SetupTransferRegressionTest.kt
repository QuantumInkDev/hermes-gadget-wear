package dev.quantumink.hermesgadget.mobile

import android.graphics.Color
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Successor regressions: observations and pre-operation originals are independent of the journal. */
class SetupTransferRegressionTest {
    private val fixtures = mutableListOf<TransferFixture>()
    private fun fixture() = TransferFixture().also(fixtures::add)

    @After fun close() {
        SetupImport.fault = null
        SetupTransferGate.resetForProcessFixture(null)
        SetupTransferGate.ensureRecovered()
        fixtures.forEach { it.quiesce() }
        fixtures.forEach { it.close() }
    }

    private fun archive(source: SetupTransferStores) = SetupExport.build(source).use {
        MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
    }

    @Test fun unrelatedCachedArtSurvivesARejectedImport() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.stores()
        repeat(32) { index ->
            val bytes = dev.quantumink.hermesgadget.protocol.GifPortrait.normalize(
                TransferFixture.gif().also { it[13] = (index + 32).toByte() }
            ).bytes
            val descriptor = dev.quantumink.hermesgadget.protocol.AnimationDescriptor(
                sha256Hex(bytes),
                bytes.size,
                "0".repeat(64)
            )
            stores.catalog.animations.store(descriptor, bytes)
        }
        val before = target.state(stores)
        requireNotNull(openPreview(stores, archive(source), PASSPHRASE)).use { preview ->
            assertThrows(ImportPlanException::class.java) {
                SetupImport.commit(stores, preview, preview.defaults())
            }
        }
        assertEquals(before, target.state(stores))
    }

    @Test fun fullReplayHistoryCannotLeaveAnUnreportedConnection() {
        val target = fixture()
        val stores = target.stores()
        val now = System.currentTimeMillis() / 1000
        val replay = SetupReplayJournal(stores.directory)
        repeat(256) { replay.spend("%032x".format(it), now + 600, now) }
        val before = target.state(stores)
        val review = SetupMetadataCodec.decode(
            SetupMetadataCodec.encode(
                SetupMetadataCodec.create("Alpha", SetupEndpoint("Alpha", ALPHA_URL), null, now)
            ),
            now
        )
        assertThrows(IllegalArgumentException::class.java) {
            SetupQrImport.add(stores, review, QrAddChoice(), now)
        }
        assertEquals(before, target.state(stores))
        assertFalse(replay.spent(review.metadata.setupId, now))
    }

    @Test fun unreadableRecoveryBlocksNormalWritesToo() {
        val target = fixture()
        val stores = target.partial()
        File(stores.directory, "import-journal").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64) { 7 })
        }
        val before = target.state(stores)
        SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
        assertTrue(SetupImport.blocked(stores))
        assertThrows(IllegalStateException::class.java) {
            stores.profiles.save(EndpointProfile(Endpoint.parse(TWO_URL), "Late write", ""))
        }
        assertEquals(before, target.state(stores))
        SetupImport.discardUnreadableRecord(stores)
        assertFalse(SetupImport.blocked(stores))
        stores.profiles.save(EndpointProfile(Endpoint.parse(TWO_URL), "Allowed now", ""))
        assertEquals(2, stores.profiles.profiles().size)
    }

    @Test fun selectingImportedPicturesReplacesTheExistingBasePortrait() {
        val source = fixture().seed()
        val target = fixture()
        val stores = target.seed()
        val local = stores.catalog.portraits.normalize(TransferFixture.png(Color.MAGENTA, 32))
        stores.catalog.vault.savePages(
            stores.catalog.vault.pages().map {
                if (it.key != "home:one") {
                    it
                } else {
                    dev.quantumink.hermesgadget.protocol.CatalogPage(
                        it.sourceId,
                        it.sourceLabel,
                        CatalogProfile(it.profile.id, it.profile.name, it.profile.gadgetUrl, local),
                        it.status,
                        it.checkedAt
                    )
                }
            }
        )
        val preview = requireNotNull(openPreview(stores, archive(source), PASSPHRASE))
        preview.use {
            val choices = it.defaults().let { d ->
                d.copy(profiles = d.profiles + ("home:one" to ProfileChoice(false, true)))
            }
            SetupImport.commit(stores, it, choices)
        }
        assertEquals(
            source.catalog.vault.pages().single { it.key == "home:one" }.profile.portrait,
            stores.catalog.vault.pages().single { it.key == "home:one" }.profile.portrait
        )
    }

    @Test fun bindingOnlyActionCollisionOffersAndAppliesTheImportedTarget() {
        val source = fixture().seed()
        val target = fixture().seed()
        val imported = TransferFixture.actions().first()
        target.quickActions.save(listOf(imported.copy(profileKey = manualKey(ALPHA_URL))))
        val preview = requireNotNull(openPreview(target, archive(source), PASSPHRASE))
        preview.use {
            assertFalse(it.actions.single { item -> item.action.id == imported.id }.identical)
            val choices = it.defaults().let { d ->
                d.copy(actions = d.actions + (imported.id to ItemChoice(false, true)))
            }
            SetupImport.commit(target, it, choices)
        }
        assertEquals("home:one", target.quickActions.stored()!!.first().profileKey)
    }

    @Test fun realEnrollmentAndProviderVaultsRemainByteIdenticalAndIndependent() {
        val source = fixture()
        val sourceStores = source.seed()
        val target = fixture()
        val targetStores = target.partial()
        fun identity(f: TransferFixture) = ConversationVault(
            f.context,
            File(f.root, "phone-conversations"),
            f.aliases[4]
        )
        fun provider(f: TransferFixture) = TtsVault(
            f.context,
            File(f.root, "speech-vault"),
            f.aliases[5]
        )
        val endpoint = Endpoint.parse(ALPHA_URL)
        val sourceId = identity(source).identity(endpoint, create = true).enrollmentKey()
        val targetId = identity(target).identity(endpoint, create = true).enrollmentKey()
        assertFalse(sourceId == targetId)
        provider(
            source
        ).save("shared", SpeechSettings(SpeechMode.SHARED, "SOURCE-PROVIDER-SENTINEL", "voice"))
        provider(
            target
        ).save("shared", SpeechSettings(SpeechMode.SHARED, "TARGET-PROVIDER-SENTINEL", "voice"))
        fun protected(f: TransferFixture) =
            listOf("phone-conversations", "speech-vault").flatMap { name ->
                File(f.root, name).walkTopDown().filter { it.isFile }.map {
                    it.relativeTo(f.root).path to sha256Hex(it.readBytes())
                }.toList()
            }.toMap()
        val sourceBefore = protected(source)
        val targetBefore = protected(target)
        val envelope = archive(sourceStores)
        val preview = requireNotNull(openPreview(targetStores, envelope, PASSPHRASE))
        preview.use { SetupImport.commit(targetStores, it, it.defaults()) }
        assertEquals(sourceBefore, protected(source))
        assertEquals(targetBefore, protected(target))
        assertEquals(sourceId, identity(source).identity(endpoint).enrollmentKey())
        assertEquals(targetId, identity(target).identity(endpoint).enrollmentKey())
        assertEquals("TARGET-PROVIDER-SENTINEL", provider(target).read("shared").key)
    }

    private class ProcessDeath : Error()

    @Test fun qrCrashRecoversNativeWritesAndCompletesReplayOnlyAfterCommit() {
        val now = System.currentTimeMillis() / 1000
        listOf(ImportStep.SOURCES, ImportStep.COMMITTED).forEach { step ->
            val target = fixture()
            val stores = target.partial()
            val before = target.state(stores)
            val review = SetupMetadataCodec.decode(
                SetupMetadataCodec.encode(
                    SetupMetadataCodec.create("Two", SetupEndpoint("Two", TWO_URL), null, now)
                ),
                now
            )
            SetupImport.fault = { if (it == step) throw ProcessDeath() }
            assertThrows(ProcessDeath::class.java) {
                SetupQrImport.add(stores, review, QrAddChoice(), now)
            }
            SetupImport.fault = null
            SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
            SetupTransferGate.ensureRecovered()
            stores.catalog.republish()
            target.quiesce()
            val replay = SetupReplayJournal(stores.directory)
            if (step == ImportStep.COMMITTED) {
                assertEquals(2, stores.profiles.profiles().size)
                assertTrue(replay.spent(review.metadata.setupId, now))
                assertEquals(
                    QrAddResult.ALREADY_USED,
                    SetupQrImport.add(stores, review, QrAddChoice(), now)
                )
            } else {
                assertEquals(before, target.state(stores))
                assertFalse(replay.spent(review.metadata.setupId, now))
            }
        }
    }
}
