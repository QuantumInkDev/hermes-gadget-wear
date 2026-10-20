package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.WidgetAppearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetTemplate
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.MigrationReadResult
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.TransportPreference
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real vaults, Keystore, files and journal on an owned emulator; no network or launcher. */
class SetupTransferTest {
    private val fixtures = mutableListOf<TransferFixture>()
    private fun fixture() = TransferFixture().also(fixtures::add)

    @After fun close() {
        SetupImport.fault = null
        SetupTransferGate.resetForProcessFixture(null)
        SetupTransferGate.ensureRecovered()
        fixtures.forEach { it.quiesce() }
        fixtures.forEach { it.close() }
    }

    private fun dispose(fixture: TransferFixture) {
        fixtures.forEach { it.quiesce() }
        fixture.close()
        fixtures.remove(fixture)
    }

    private class SimulatedProcessDeath : Error()

    private fun export(source: TransferFixture): ByteArray = source.stores().let { stores ->
        SetupExport.build(stores).use {
            MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
        }
    }

    /** Includes the cleartext endpoint with fresh consent and the box with a new credential. */
    private fun everything(preview: ImportPreview, credential: String = "fresh-catalog-key-1") =
        preview.defaults().let { selection ->
            selection.copy(
                endpoints = selection.endpoints.mapValues { (url, choice) ->
                    if (url == BETA_URL) {
                        EndpointChoice(true, privateNetwork = true, cleartextAccepted = true)
                    } else {
                        choice
                    }
                },
                sources = selection.sources.mapValues { (_, choice) ->
                    if (choice.include) choice else SourceChoice(true, credential)
                },
                profiles = selection.profiles.mapValues { (_, choice) ->
                    choice.copy(include = true)
                }
            )
        }

    /** Every kind of write: new and updated endpoints, pictures, replacements, appearance. */
    private fun picturesToo(preview: ImportPreview) = everything(preview).let { s ->
        s.copy(
            endpoints = s.endpoints + (ALPHA_URL to EndpointChoice(false, update = true)),
            profiles = s.profiles.mapValues { it.value.copy(usePictures = true) },
            actions = s.actions.mapValues { it.value.copy(replace = true) },
            useAppearance = true
        )
    }

    @Test fun exportCarriesNoCredentialAndImportsIntoAFreshInstallationExactly() {
        val source = fixture()
        source.seed()
        val before = source.state()
        val envelope = export(source)
        assertEquals("Export must not change the source installation", before, source.state())
        val text = String(envelope, Charsets.ISO_8859_1)
        listOf(TOKEN_ALPHA, TOKEN_BETA, CATALOG_SECRET, "alpha.example").forEach {
            assertFalse("Envelope must be encrypted", text.contains(it))
        }
        val read = MigrationArchive.decrypt(envelope.copyOf(), PASSPHRASE)
            as MigrationReadResult.Success
        read.use {
            val snapshot = it.snapshot
            val fields = (
                snapshot.endpoints.flatMap { e -> listOf(e.label, e.url, e.transport.name) } +
                    snapshot.catalogs.flatMap { c -> listOf(c.id, c.label, c.url) } +
                    snapshot.profiles.flatMap { p ->
                        listOf(p.key, p.name, p.gadgetUrl.orEmpty())
                    } +
                    snapshot.actions.flatMap { a -> listOf(a.title, a.template) }
                ).joinToString("\n")
            listOf(TOKEN_ALPHA, TOKEN_BETA, CATALOG_SECRET).forEach { sentinel ->
                assertFalse(fields.contains(sentinel))
                it.artwork.values.forEach { bytes ->
                    assertFalse(String(bytes, Charsets.ISO_8859_1).contains(sentinel))
                }
            }
            assertEquals(2, snapshot.endpoints.size)
            assertEquals(1, snapshot.catalogs.size)
            assertEquals(4, snapshot.profiles.size)
            assertEquals(7, snapshot.widgets.size)
            assertEquals(
                HomeWidgetPreset.entries.map { p -> p.name }.toSet(),
                snapshot.widgets.map { w -> w.preset.name }.toSet()
            )
        }

        val fresh = fixture()
        val empty = fresh.state()
        assertTrue(
            "Fresh fixture starts without enrollments",
            empty.getValue("endpoints").isEmpty()
        )
        val stores = fresh.stores()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        // Defaults never assume consent or a credential.
        val defaults = preview.defaults()
        assertFalse(defaults.endpoints.getValue(BETA_URL).include)
        assertFalse(defaults.sources.values.single().include)
        assertEquals(empty, fresh.state())
        SetupImport.commit(stores, preview, everything(preview))
        preview.close()

        val after = fresh.state()
        val endpoints = after.getValue("endpoints").lines().sorted()
        assertEquals(
            listOf(
                "$ALPHA_URL|Alpha||false|false|AUTO",
                "$BETA_URL|Beta LAN||true|true|DIRECT"
            ).sorted(),
            endpoints
        )
        assertEquals("home|Home box|$CATALOG_URL|fresh-catalog-key-1", after.getValue("sources"))
        listOf(TOKEN_ALPHA, TOKEN_BETA, CATALOG_SECRET).forEach { sentinel ->
            after.values.forEach { assertFalse(it.contains(sentinel)) }
        }
        // Exact picture and animation bytes, content-addressed under the same names.
        assertEquals(before.getValue("portraits"), after.getValue("portraits"))
        assertEquals(before.getValue("gifs"), after.getValue("gifs"))
        listOf("fallbacks", "animations").forEach {
            assertEquals(it, before.getValue(it), after.getValue(it))
        }
        // Overrides carry runtime status/check time, which is deliberately not migrated.
        fun pictures(f: TransferFixture) = f.stores().catalog.vault.overrides()
            .associate { it.key to it.profile.portrait?.sha256 }
        assertEquals(pictures(source), pictures(fresh))
        val imported = fresh.stores().quickActions.load()
        assertTrue(imported.containsAll(TransferFixture.actions()))
        assertEquals("Imported appearance is opt-in", "DARK", after.getValue("appearance"))
        val templates = fresh.stores().templates.templates()
        assertEquals(7, templates.size)
        source.placed.forEach { (id, preset) ->
            val config = requireNotNull(source.stores().widgets.config(id))
            assertTrue(
                templates.any {
                    it.preset == preset && it.slots == config.slots &&
                        it.appearance == config.appearance
                }
            )
        }
        val roster = fresh.stores().catalog.savedPages().map { it.key }.toSet()
        assertEquals(
            setOf(manualKey(ALPHA_URL), manualKey(BETA_URL), "home:one", "home:two"),
            roster
        )
        assertEquals("Watch metadata published once after commit", 1, fresh.published.size)
        assertEquals(1, fresh.setupPublications.get())
        assertFalse(File(fresh.root, "setup-transfer/import-journal").exists())
    }

    @Test fun defaultPreviewPreservesEveryCollidingValueAndRemapsBoxKeys() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        val stores = target.stores()
        stores.profiles.save(
            EndpointProfile(Endpoint.parse(ALPHA_URL), "Alpha here", "LOCAL-TOKEN")
        )
        stores.catalog.saveSource(CatalogSource("other", "My box", CATALOG_URL, "local-key"))
        stores.quickActions.save(
            listOf(
                QuickAction(
                    "00000000-0000-4000-8000-0000000000a1",
                    null,
                    "Mine",
                    "Different prompt."
                )
            )
        )
        stores.templates.save(
            listOf(
                WidgetTemplate(
                    "00000000-0000-4000-8000-0000000000f1",
                    HomeWidgetPreset.FOUR,
                    List(4) { null },
                    WidgetAppearance.LIGHT
                )
            )
        )
        stores.appearance.set(Appearance.LIGHT)
        stores.catalog.savedPages()
        val before = target.state()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        assertEquals(before, target.state())
        assertTrue(preview.sources.single().merged)
        assertEquals("other", preview.sources.single().targetId)
        assertNotNull(preview.endpoints.single { it.setup.url == ALPHA_URL }.existing)
        assertNotNull(preview.actions.single { it.action.title == "Morning check" }.existing)
        SetupImport.commit(stores, preview, preview.defaults())
        preview.close()
        val after = target.state()
        assertTrue(
            after.getValue(
                "endpoints"
            ).contains("$ALPHA_URL|Alpha here|LOCAL-TOKEN|false|false|AUTO")
        )
        assertFalse(after.getValue("endpoints").contains(BETA_URL))
        assertEquals("other|My box|$CATALOG_URL|local-key", after.getValue("sources"))
        assertEquals("LIGHT", after.getValue("appearance"))
        val actions = target.stores().quickActions.load()
        assertEquals("Mine", actions.single { it.id.endsWith("a1") }.title)
        // The imported action for Alpha keeps its binding; box keys are remapped to "other".
        assertEquals(manualKey(ALPHA_URL), actions.single { it.id.endsWith("a2") }.profileKey)
        val pages = target.stores().catalog.savedPages().map { it.key }.toSet()
        assertTrue("other:one" in pages && "other:two" in pages && "home:one" !in pages)
        val templates = target.stores().templates.templates()
        assertEquals(WidgetAppearance.LIGHT, templates.single { it.id.endsWith("f1") }.appearance)
        assertTrue(templates.flatMap { it.slots }.filterNotNull().none { it.startsWith("home:") })
    }

    @Test fun explicitCollisionChoicesNeverReplaceTokensOrConsent() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        val stores = target.stores()
        stores.profiles.save(
            EndpointProfile(
                Endpoint.parse(BETA_URL, true, true),
                "Old name",
                "KEEP-ME",
                TransportPreference.AUTO
            )
        )
        stores.quickActions.save(listOf(TransferFixture.actions()[2].copy(title = "Old")))
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        val selection = preview.defaults().let {
            it.copy(
                endpoints = it.endpoints + (BETA_URL to EndpointChoice(false, update = true)),
                actions = it.actions.mapValues { (_, choice) -> choice.copy(replace = true) },
                useAppearance = true
            )
        }
        SetupImport.commit(stores, preview, selection)
        preview.close()
        val after = target.state()
        assertTrue(
            after.getValue("endpoints").contains("$BETA_URL|Beta LAN|KEEP-ME|true|true|DIRECT")
        )
        assertEquals("MOONLIGHT", after.getValue("appearance"))
        assertEquals(
            "Free prompt",
            target.stores().quickActions.load().single {
                it.id.endsWith("a3")
            }.title
        )
    }

    @Test fun cancelTamperWrongPassphraseAndOversizeNeverMutate() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        target.seed(Appearance.LIGHT)
        val before = target.state()
        val stores = target.stores()
        openPreview(stores, envelope.copyOf(), PASSPHRASE)!!.close() // Review, then cancel.
        assertNull(openPreview(stores, envelope.copyOf(), "wrong passphrase!!".toCharArray()))
        listOf(10, 60, envelope.size / 2, envelope.size - 1).forEach { index ->
            val tampered = envelope.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(openPreview(stores, tampered, PASSPHRASE))
        }
        assertNull(openPreview(stores, envelope.copyOf(envelope.size - 7), PASSPHRASE))
        assertThrows(IllegalArgumentException::class.java) {
            readBounded(ByteArrayInputStream(ByteArray(1024)), 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readBounded(ByteArrayInputStream(ByteArray(1024)), 4096, declared = 512)
        }
        assertEquals(before, target.state())
    }

    @Test fun quotaAndCapacityFailuresAreReportedBeforeAnyWrite() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        val stores = target.stores()
        stores.quickActions.save(
            (1..31).map {
                QuickAction(
                    "00000000-0000-4000-8000-%012d".format(1000 + it),
                    null,
                    "Local $it",
                    "Prompt $it"
                )
            }
        )
        val before = target.state()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        val failure = assertThrows(ImportPlanException::class.java) {
            SetupImport.commit(stores, preview, everything(preview))
        }
        assertTrue(failure.problems.any { "quick actions" in it })
        assertEquals(before, target.state())
        target.free = 1024
        val space = assertThrows(ImportPlanException::class.java) {
            SetupImport.plan(stores, preview, preview.defaults().copy(actions = emptyMap()))
        }
        assertTrue(space.problems.any { "free storage" in it })
        preview.close()
        assertEquals(before, target.state())
    }

    @Test fun pictureBudgetIsCheckedAgainstTheRealStoreBeforeWriting() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        val stores = target.stores()
        // Fill the 8 MiB budget with referenced, protected noise pictures through real pages.
        val pages = (0 until 31).map { index ->
            val picture = stores.catalog.portraits.normalize(
                TransferFixture.png(0, 256, noise = true, seed = index)
            )
            dev.quantumink.hermesgadget.protocol.CatalogPage(
                "fill",
                "Filler",
                dev.quantumink.hermesgadget.protocol.CatalogProfile(
                    "p$index",
                    "P$index",
                    null,
                    picture
                ),
                dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE,
                0
            )
        }
        stores.catalog.vault.savePages(pages)
        val used = stores.catalog.portraits.protectedBytes(
            pages.map {
                it.profile.portrait!!.sha256
            }.toSet()
        )
        val before = target.state()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        val selection = everything(preview)
        val result = runCatching { SetupImport.plan(stores, preview, selection) }
        val needed = preview.art.values.sumOf { it.size.toLong() }
        if (used + needed > 8L * 1024 * 1024) {
            val failure = result.exceptionOrNull() as ImportPlanException
            assertTrue(
                failure.problems.joinToString(),
                failure.problems.any {
                    "picture space" in it ||
                        "show at most" in it
                }
            )
        }
        assertThrows(ImportPlanException::class.java) {
            SetupImport.commit(stores, preview, selection)
        }
        preview.close()
        assertEquals(before, target.state())
    }

    @Test fun anyFailureDuringCommitRestoresEveryOriginalValue() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        ImportStep.entries.filter { it != ImportStep.COMMITTED }.forEach { step ->
            val target = fixture()
            target.partial()
            val before = target.state()
            val stores = target.stores()
            val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
            val selection = picturesToo(preview)
            SetupImport.fault = { if (it == step) throw IllegalStateException("injected $it") }
            val failure = assertThrows(IllegalStateException::class.java) {
                SetupImport.commit(stores, preview, selection)
            }
            assertEquals("injected $step", failure.message)
            SetupImport.fault = null
            preview.close()
            assertEquals("failure at $step", before, target.state())
            assertFalse(File(target.root, "setup-transfer/import-journal").exists())
            dispose(target)
        }
    }

    @Test fun processDeathInEveryPhaseIsRecoveredBeforeTheNextMutation() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val reference = fixture()
        reference.partial().let { stores ->
            val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
            SetupImport.commit(stores, preview, picturesToo(preview))
            preview.close()
        }
        reference.stores().catalog.savedPages()
        val committed = reference.state()
        ImportStep.entries.forEach { step ->
            val target = fixture()
            target.partial()
            val before = target.state()
            val stores = target.stores()
            val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
            SetupImport.fault = { if (it == step) throw SimulatedProcessDeath() }
            assertThrows(SimulatedProcessDeath::class.java) {
                SetupImport.commit(stores, preview, picturesToo(preview))
            }
            SetupImport.fault = null
            preview.close()
            assertTrue(File(target.root, "setup-transfer/import-journal").exists())
            // A new process: fresh objects, recovery runs before the first gated mutation.
            val restarted = target.stores()
            SetupTransferGate.resetForProcessFixture { SetupImport.recover(restarted) }
            restarted.quickActions.load()
            assertFalse(File(target.root, "setup-transfer/import-journal").exists())
            // The next launch's normal roster refresh (recovery also schedules one).
            target.stores().catalog.savedPages()
            val recovered = target.state()
            if (step == ImportStep.COMMITTED) {
                assertEquals("finished at $step", committed, recovered)
            } else {
                assertEquals("rolled back at $step", before, recovered)
            }
            dispose(target)
        }
    }

    @Test fun unreadableJournalFailsClosedUntilTheOwnerRemovesIt() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        target.seed(Appearance.LIGHT)
        val stores = target.stores()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        File(target.root, "setup-transfer").mkdirs()
        File(target.root, "setup-transfer/import-journal").writeBytes(ByteArray(64) { 7 })
        val before = target.state()
        SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
        assertTrue(SetupImport.blocked(stores))
        assertEquals(before, target.state())
        assertNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        assertThrows(IllegalStateException::class.java) {
            SetupImport.commit(stores, preview, everything(preview))
        }
        assertEquals(before, target.state())
        SetupImport.discardUnreadableRecord(stores)
        assertFalse(SetupImport.blocked(stores))
        SetupImport.commit(stores, preview, everything(preview))
        preview.close()
    }

    @Test fun normalMutationsWaitForTheWholeImportAndSeeItsResult() {
        val source = fixture()
        source.seed()
        val envelope = export(source)
        val target = fixture()
        val stores = target.partial()
        val original = stores.quickActions.load()
        val preview = requireNotNull(openPreview(stores, envelope.copyOf(), PASSPHRASE))
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        SetupImport.fault = {
            if (it == ImportStep.TEMPLATES) {
                paused.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        val importer = Thread {
            SetupImport.commit(
                stores,
                preview,
                everything(preview).copy(
                    actions = preview.defaults().actions.mapValues {
                        it.value.copy(include = true, replace = true)
                    }
                )
            )
        }.apply { start() }
        assertTrue(paused.await(20, TimeUnit.SECONDS))
        val outcome = AtomicReference<Result<Unit>>()
        val writer = Thread {
            outcome.set(
                runCatching {
                    target.stores().quickActions.save(original.reversed(), original)
                }
            )
        }.apply { start() }
        writer.join(700)
        assertTrue("A normal write must wait for the import", writer.isAlive)
        release.countDown()
        importer.join(30000)
        writer.join(30000)
        SetupImport.fault = null
        preview.close()
        // The waiting write observed the imported actions, so its stale expectation fails.
        assertTrue(outcome.get().isFailure)
        assertTrue(target.stores().quickActions.load().size > original.size)
    }
}
