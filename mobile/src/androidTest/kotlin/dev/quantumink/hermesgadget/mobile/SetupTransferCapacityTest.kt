package dev.quantumink.hermesgadget.mobile

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.GifDecoder
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native upper-capacity fixture, with actual image content rather than padded metadata. */
class SetupTransferCapacityTest {
    @Test fun nearFullNativeArtworkStoresExportDecryptValidateAndImportWithinTheHeap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = TransferFixture()
        val target = TransferFixture()
        val running = AtomicBoolean(true)
        val peakJava = AtomicLong()
        val peakNative = AtomicLong()
        val runtime = Runtime.getRuntime()
        val sampler = Thread {
            while (running.get()) {
                peakJava.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                SystemClock.sleep(10)
            }
        }.apply { start() }
        try {
            val stores = source.stores()
            stores.catalog.saveSource(
                CatalogSource("capacity", "Capacity", CATALOG_URL, "local-key")
            )
            val pictures = mutableListOf<dev.quantumink.hermesgadget.protocol.PortraitDescriptor>()
            var remaining = 8L * 1024 * 1024
            while (pictures.size < 64) {
                var side = 256
                var bytes = stores.catalog.portraits.prepare(
                    TransferFixture.png(0, side, true, pictures.size)
                )
                while (bytes.size > remaining && side > 8) {
                    side--
                    bytes =
                        stores.catalog.portraits.prepare(
                            TransferFixture.png(0, side, true, pictures.size)
                        )
                }
                if (bytes.size > remaining) break
                pictures += stores.catalog.portraits.adopt(bytes)
                stores.catalog.portraits.protect(pictures.map { it.sha256 }.toSet())
                remaining -= bytes.size
                if (remaining < 1024) break
            }
            val pictureBytes = pictures.sumOf { it.bytes.toLong() }
            assertTrue(
                "PNG bytes=$pictureBytes count=${pictures.size}",
                pictureBytes in (8L * 1024 * 1024 * 98 / 100)..(8L * 1024 * 1024)
            )
            val overrides = pictures.size - 32
            assertTrue(overrides in 0..32)
            val gif = instrumentation.context.assets.open("large-noise-three-frames.gif").use {
                it.readBytes()
            }
            val animations = (0 until 32).associate { index ->
                val bytes = GifPortrait.normalize(
                    gif.copyOf().also {
                        it[13] = index.toByte()
                    }
                ).bytes
                assertEquals(3, GifPortrait.validate(bytes).frames)
                GifDecoder.drawable(bytes)
                val fallback = pictures[if (index < overrides) index + 32 else index]
                val descriptor = AnimationDescriptor(sha256Hex(bytes), bytes.size, fallback.sha256)
                stores.catalog.animations.store(descriptor, bytes)
                "capacity:p$index" to descriptor
            }
            val animationBytes = animations.values.sumOf { it.bytes.toLong() }
            assertTrue(animationBytes in (8L * 1024 * 1024 * 98 / 100)..(8L * 1024 * 1024))
            val pages = (0 until 32).map { index ->
                CatalogPage(
                    "capacity",
                    "Capacity",
                    CatalogProfile("p$index", "Bot $index", null, pictures[index]),
                    CatalogStatus.AVAILABLE,
                    0
                )
            }
            stores.catalog.vault.savePages(pages)
            stores.catalog.vault.saveOverrides(
                (0 until overrides).map { index ->
                    CatalogPage(
                        "capacity",
                        "Capacity",
                        CatalogProfile("p$index", "Bot $index", null, pictures[index + 32]),
                        CatalogStatus.AVAILABLE,
                        0
                    )
                }
            )
            stores.catalog.vault.saveAnimations(animations)
            stores.catalog.vault.saveSourceOrder(listOf("capacity", "manual"))
            val started = SystemClock.elapsedRealtime()
            val archive = File(source.root, "large.hgmigr")
            SetupExport.build(stores).use {
                assertEquals(pictures.size + 32, it.snapshot.artwork.size)
                archive.outputStream().use { output ->
                    MigrationArchive.writeEncrypted(it.snapshot, PASSPHRASE, it.artwork, output)
                }
            }
            val exportMs = SystemClock.elapsedRealtime() - started
            val archiveBytes = archive.length()
            val destination = target.stores()
            val openStarted = SystemClock.elapsedRealtime()
            val preview = requireNotNull(
                openPreview(
                    destination,
                    archive.inputStream().use {
                        readArchiveInput(destination.context, it)
                    },
                    PASSPHRASE
                )
            )
            val openMs = SystemClock.elapsedRealtime() - openStarted
            val commitStarted = SystemClock.elapsedRealtime()
            preview.use {
                val choices = it.defaults().let { d ->
                    d.copy(sources = mapOf("capacity" to SourceChoice(true, "independent-key")))
                }
                SetupImport.commit(destination, it, choices).watch?.get()
            }
            val commitMs = SystemClock.elapsedRealtime() - commitStarted
            val before = source.state(stores)
            val after = target.state(destination)
            assertEquals(before.getValue("portraits"), after.getValue("portraits"))
            assertEquals(before.getValue("gifs"), after.getValue("gifs"))
            assertEquals(32, destination.catalog.vault.pages().size)
            val metrics =
                "api=${android.os.Build.VERSION.SDK_INT} heapLimit=${runtime.maxMemory()} " +
                    "peakJava=${peakJava.get()} peakNative=${peakNative.get()} " +
                    "pngBytes=$pictureBytes gifBytes=$animationBytes archiveBytes=$archiveBytes " +
                    "exportMs=$exportMs openMs=$openMs commitMs=$commitMs"
            instrumentation.sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("capacity", metrics)
                }
            )
            File(instrumentation.targetContext.filesDir, "interop").apply { mkdirs() }
                .resolve("capacity-api${android.os.Build.VERSION.SDK_INT}.txt").writeText(metrics)
        } finally {
            running.set(false)
            sampler.join()
            source.quiesce()
            target.quiesce()
            source.close()
            target.close()
        }
    }
}
