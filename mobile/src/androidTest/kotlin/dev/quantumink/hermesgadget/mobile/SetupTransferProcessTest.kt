package dev.quantumink.hermesgadget.mobile

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Two separate instrumentation processes, intentionally killed at a caller-selected phase. */
class SetupTransferProcessTest {
    private val args = InstrumentationRegistry.getArguments()
    private val tag by lazy { requireNotNull(args.getString("transfer_process_tag")) }
    private val step by lazy {
        ImportStep.valueOf(requireNotNull(args.getString("transfer_process_step")))
    }
    private val evidence by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "transfer-process/$tag"
        )
    }

    @Before fun requireExplicitProcessFixtureArguments() {
        // The controller runs each deliberate death/recovery pair separately. An ordinary
        // instrumentation suite must not kill its own runner or invent an evidence tag.
        assumeTrue(
            args.containsKey("transfer_process_tag") && args.containsKey("transfer_process_step")
        )
    }

    private fun capture(name: String, values: Map<String, String>) {
        evidence.mkdirs()
        evidence.resolve(name).writeText(
            JsonObject(
                values.mapValues {
                    JsonPrimitive(it.value)
                }
            ).toString()
        )
    }

    private fun originalArt(stores: SetupTransferStores) {
        if (args.getString("transfer_preserve_art") != "true") return
        stores.catalog.selectPhoto(
            stores.profiles.profiles().first(),
            TransferFixture.png(android.graphics.Color.RED, 24)
        )
        stores.catalog.selectWatchPhoto(manualKey(ALPHA_URL), TransferFixture.gif())
        stores.catalog.portraits.adopt(
            stores.catalog.portraits.prepare(TransferFixture.png(android.graphics.Color.CYAN, 36))
        )
        val gif = dev.quantumink.hermesgadget.protocol.GifPortrait.normalize(
            TransferFixture.gif().also {
                it[13] =
                    61
            }
        ).bytes
        stores.catalog.animations.store(
            dev.quantumink.hermesgadget.protocol.AnimationDescriptor(
                sha256Hex(gif),
                gif.size,
                "0".repeat(64)
            ),
            gif
        )
    }

    @Test fun prepareOriginalsAndTerminateAtTheSelectedPhase() {
        check(!evidence.exists()) { "Never replace an earlier process fixture's originals." }
        val source = TransferFixture()
        val envelope = SetupExport.build(source.seed()).use {
            MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
        }
        source.close()
        val target = TransferFixture(tag)
        val stores = target.partial()
        originalArt(stores)
        capture("before.json", target.state(stores))
        evidence.resolve("prepare-pid.txt").writeText(Process.myPid().toString())
        val reference = TransferFixture()
        val referenceStores = reference.partial()
        originalArt(referenceStores)
        requireNotNull(openPreview(referenceStores, envelope.copyOf(), PASSPHRASE)).use {
            SetupImport.commit(referenceStores, it, it.defaults()).watch?.get()
        }
        reference.quiesce()
        capture("committed.json", reference.state(referenceStores))
        reference.close()
        val preview = requireNotNull(openPreview(stores, envelope, PASSPHRASE))
        SetupImport.fault = {
            if (it == step) {
                evidence.resolve("reached.txt").writeText(it.name)
                Process.killProcess(Process.myPid())
                Thread.sleep(10000)
                error("Process termination did not occur")
            }
        }
        SetupImport.commit(stores, preview, preview.defaults())
        error("Expected process termination")
    }

    @Test fun recoverInAnotherProcessAndVerifyCapturedOriginals() {
        assertEquals(step.name, evidence.resolve("reached.txt").readText())
        assertTrue(Process.myPid().toString() != evidence.resolve("prepare-pid.txt").readText())
        val target = TransferFixture(tag)
        try {
            val stores = target.stores()
            assertTrue(File(stores.directory, "import-journal").isFile)
            SetupTransferGate.resetForProcessFixture { SetupImport.recover(stores) }
            stores.catalog.transferRead { stores.catalog.savedPages() }
            target.quiesce()
            assertEquals("Recovery publishes the roster exactly once", 1, target.published.size)
            assertEquals("Recovery publishes setup exactly once", 1, target.setupPublications.get())
            assertFalse(File(stores.directory, "import-journal").exists())
            val file = if (step == ImportStep.COMMITTED) "committed.json" else "before.json"
            val expected = (
                Json.parseToJsonElement(
                    evidence.resolve(file).readText()
                ) as JsonObject
                )
                .mapValues { (it.value as JsonPrimitive).content }
            assertEquals(expected, target.state(stores))
            capture("recovered.json", target.state(stores))
            evidence.resolve("recover-pid.txt").writeText(Process.myPid().toString())
        } finally {
            SetupTransferGate.resetForProcessFixture(null)
            SetupTransferGate.ensureRecovered()
            target.close()
        }
    }
}
