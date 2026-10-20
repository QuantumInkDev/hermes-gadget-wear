package dev.quantumink.hermesgadget.mobile

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.MigrationArtwork
import dev.quantumink.hermesgadget.protocol.MigrationArtworkFormat
import dev.quantumink.hermesgadget.protocol.MigrationProfile
import dev.quantumink.hermesgadget.protocol.MigrationSnapshot
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-format aggregate with real RGBA pixels and real three-frame GIFs, no padding. */
class SetupTransferFullFormatTest {
    @Test fun fullFormatArchiveCanBeReviewedAndOnlyOneProfileImported() {
        val source = TransferFixture()
        val target = TransferFixture()
        val runtime = Runtime.getRuntime()
        if (android.os.Build.VERSION.SDK_INT ==
            29
        ) {
            assertEquals(48L * 1024 * 1024, runtime.maxMemory())
        }
        val running = AtomicBoolean(true)
        val peak = AtomicLong()
        val native = AtomicLong()
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                native.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                SystemClock.sleep(10)
            }
        }.apply { start() }
        try {
            val files = linkedMapOf<String, File>()
            val assets = mutableListOf<MigrationArtwork>()
            fun asset(bytes: ByteArray, format: MigrationArtworkFormat): String {
                val hash = sha256Hex(bytes)
                files[hash] = File(source.root, hash).apply { writeBytes(bytes) }
                assets += MigrationArtwork(hash, bytes.size, format)
                bytes.fill(0)
                return hash
            }
            val profiles = (0 until 32).map { index ->
                val portrait = asset(png(index * 3), MigrationArtworkFormat.PNG)
                val fallback = asset(png(index * 3 + 1), MigrationArtworkFormat.PNG)
                val watch = asset(png(index * 3 + 2), MigrationArtworkFormat.PNG)
                val gif = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("large-noise-three-frames.gif").use { it.readBytes() }
                gif[13] = index.toByte()
                val animation = asset(GifPortrait.normalize(gif).bytes, MigrationArtworkFormat.GIF)
                MigrationProfile(
                    "capacity:p$index",
                    "Bot $index",
                    "wss://bot$index.example.invalid/gadget",
                    portrait,
                    fallback,
                    watch,
                    animation,
                    watch
                )
            }
            val snapshot =
                MigrationSnapshot(
                    catalogs = listOf(SetupCatalog("capacity", "Capacity", CATALOG_URL)),
                    profiles = profiles,
                    sourceOrder = listOf("capacity", "manual"),
                    profileOrder = profiles.map {
                        it.key
                    },
                    artwork = assets
                )
            val total = assets.sumOf { it.bytes.toLong() }
            assertEquals(128, assets.size)
            assertTrue(
                "actual content=$total",
                total > MigrationArchive.MAX_UNCOMPRESSED_BYTES * 995L / 1000
            )
            val artwork = object : AbstractMap<String, ByteArray>() {
                override val entries: Set<Map.Entry<String, ByteArray>> get() = error(
                    "writer must read one asset at a time"
                )
                override val keys: Set<String> get() = files.keys
                override fun get(key: String) = files[key]?.readBytes()
            }
            val envelope = File(source.root, "full-format.hgmigr")
            val start = SystemClock.elapsedRealtime()
            envelope.outputStream().use {
                MigrationArchive.writeEncrypted(snapshot, PASSPHRASE, artwork, it)
            }
            val written = SystemClock.elapsedRealtime() - start
            val stores = target.stores()
            val opened = SystemClock.elapsedRealtime()
            val input = envelope.inputStream().use { readArchiveInput(stores.context, it) }
            val preview = requireNotNull(openPreview(stores, input, PASSPHRASE))
            val openMs = SystemClock.elapsedRealtime() - opened
            preview.use {
                assertEquals(128, it.art.size)
                val defaults = it.defaults()
                val choices = defaults.copy(
                    sources = mapOf("capacity" to SourceChoice(true, "independent-key")),
                    profiles = defaults.profiles.mapValues { (key, _) ->
                        ProfileChoice(key == "capacity:p0")
                    }
                )
                SetupImport.commit(stores, it, choices).watch?.get()
            }
            target.quiesce()
            assertEquals(listOf("capacity:p0"), stores.catalog.vault.pages().map { it.key })
            assertEquals(3, stores.catalog.portraits.hashes().size)
            assertEquals(1, stores.catalog.animations.hashes().size)
            val metrics = "api=${android.os.Build.VERSION.SDK_INT} " +
                "heapLimit=${runtime.maxMemory()} peakJava=${peak.get()} " +
                "peakNative=${native.get()} contentBytes=$total " +
                "envelopeBytes=${envelope.length()} artwork=128 png=96 gif=32 " +
                "exportMs=$written openMs=$openMs selectedProfiles=1"
            val evidence = File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "fix1"
            ).apply {
                mkdirs()
            }
            evidence.resolve(
                "full-format-api${android.os.Build.VERSION.SDK_INT}.txt"
            ).writeText(metrics)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("fullFormat", metrics)
                }
            )
        } finally {
            running.set(false)
            sampler.join()
            source.close()
            target.close()
        }
    }

    private fun png(seed: Int): ByteArray {
        val pixels = ByteArray(256 * (255 * 4 + 1))
        Random(seed).nextBytes(pixels)
        repeat(256) { pixels[it * (255 * 4 + 1)] = 0 }
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(pixels) }
        }.toByteArray()
        pixels.fill(0)
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size)
            data.write(name)
            data.write(bytes)
            val crc = CRC32().apply {
                update(name)
                update(bytes)
            }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(255)
                writeInt(256)
                write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        chunk("IHDR", header)
        chunk("IDAT", compressed)
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }
}
