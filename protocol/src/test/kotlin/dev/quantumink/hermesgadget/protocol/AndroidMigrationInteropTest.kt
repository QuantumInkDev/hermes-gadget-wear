package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Conscrypt/libcore output retained from the owned API29 and API37 native fixtures. */
class AndroidMigrationInteropTest {
    @Test fun streamingWriterAndConsumedReaderKeepTheV1ContractAndClearTheirBuffers() {
        val bytes = requireNotNull(
            javaClass.getResourceAsStream("/migration/android-sealed-api29.hgmigr")
        )
            .use { it.readBytes() }
        val phrase = "Ångström ☾ 月光 moonlight".toCharArray()
        val original = MigrationArchive.decrypt(
            bytes.copyOf(),
            phrase
        ) as MigrationReadResult.Success
        original.use { source ->
            val output = java.io.ByteArrayOutputStream()
            MigrationArchive.writeEncrypted(source.snapshot, phrase, source.artwork, output)
            val encrypted = output.toByteArray()
            (MigrationArchive.decrypt(encrypted, phrase) as MigrationReadResult.Success).use {
                assertEquals(source.snapshot, it.snapshot)
            }
            val consumed = MigrationArchive.decryptOwned(
                encrypted,
                phrase
            ) as MigrationReadResult.Success
            assertTrue(encrypted.all { it == 0.toByte() })
            val moved = consumed.takeArtwork()
            consumed.close()
            moved.forEach { (hash, content) ->
                org.junit.Assert.assertArrayEquals(source.artwork.getValue(hash), content)
                content.fill(0)
            }
            val damaged = output.toByteArray().also {
                it[it.lastIndex] =
                    (it.last().toInt() xor 1).toByte()
            }
            assertEquals(
                MigrationReadResult.Invalid,
                MigrationArchive.decryptOwned(damaged, phrase)
            )
            assertTrue(damaged.all { it == 0.toByte() })
        }
    }

    @Test fun multiChunkWriterAndOwnedReaderMatchJcaAndRejectAllTamperClasses() {
        val phrase = "synthetic passphrase!".toCharArray()
        val original = requireNotNull(
            javaClass.getResourceAsStream(
                "/migration/android-sealed-api29.hgmigr"
            )
        ).use { it.readBytes() }
        val png = (
            MigrationArchive.decrypt(
                original,
                "Ångström ☾ 月光 moonlight".toCharArray()
            ) as MigrationReadResult.Success
            ).use {
            val asset = it.snapshot.artwork.first { a -> a.format == MigrationArtworkFormat.PNG }
            it.artwork.getValue(asset.sha256).copyOf()
        }
        val gif = requireNotNull(
            javaClass.getResourceAsStream(
                "/gif/large-noise-three-frames.gif"
            )
        ).use { it.readBytes() }
        val pngHash = MigrationArchive.sha256(png)
        val gifHash = MigrationArchive.sha256(gif)
        val snapshot = MigrationSnapshot(
            catalogs = listOf(SetupCatalog("box", "Box", "https://box.example.invalid/catalog")),
            profiles = listOf(
                MigrationProfile(
                    "box:one",
                    "One",
                    null,
                    portrait = pngHash,
                    animation = gifHash,
                    animationFallback = pngHash
                )
            ),
            sourceOrder = listOf("box", "manual"),
            profileOrder = listOf("box:one"),
            artwork = listOf(
                MigrationArtwork(pngHash, png.size, MigrationArtworkFormat.PNG),
                MigrationArtwork(gifHash, gif.size, MigrationArtworkFormat.GIF)
            )
        )
        val art = mapOf(pngHash to png, gifHash to gif)
        val stream = java.io.ByteArrayOutputStream()
        MigrationArchive.writeEncrypted(snapshot, phrase, art, stream)
        val bcEnvelope = stream.toByteArray()
        (MigrationArchive.decrypt(bcEnvelope, phrase) as MigrationReadResult.Success).use {
            org.junit.Assert.assertArrayEquals(gif, it.artwork.getValue(gifHash))
        }
        val jcaEnvelope = MigrationArchive.encrypt(snapshot, phrase, art)
        (MigrationArchive.decryptOwned(jcaEnvelope, phrase) as MigrationReadResult.Success).use {
            org.junit.Assert.assertArrayEquals(gif, it.artwork.getValue(gifHash))
        }
        assertTrue(jcaEnvelope.all { it == 0.toByte() })
        val malformed =
            listOf(0, 8, 12, 16, 20, 24, 40, 52, 65536, bcEnvelope.lastIndex).map { at ->
                bcEnvelope.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            } + listOf(
                bcEnvelope.copyOf(40),
                bcEnvelope.copyOf(bcEnvelope.size - 1),
                bcEnvelope + byteArrayOf(0)
            )
        malformed.forEach { bytes ->
            assertEquals(MigrationReadResult.Invalid, MigrationArchive.decryptOwned(bytes, phrase))
            assertTrue(bytes.all { it == 0.toByte() })
        }
        for (wrong in listOf("wrong synthetic phrase".toCharArray(), charArrayOf('\ud800'))) {
            val bytes = bcEnvelope.copyOf()
            assertEquals(MigrationReadResult.Invalid, MigrationArchive.decryptOwned(bytes, wrong))
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun androidSealedArchiveOpensOnTheJvmWithTheOppositeNormalizationForm() {
        checkNativeArchive("/migration/android-sealed-api29.hgmigr")
    }

    @Test fun android37SealedArchiveOpensOnTheJvmWithTheOppositeNormalizationForm() {
        checkNativeArchive("/migration/android-sealed-api37.hgmigr")
    }

    private fun checkNativeArchive(resource: String) {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resource))
            .use { it.readBytes() }
        val result = MigrationArchive.decrypt(bytes, "Ångström ☾ 月光 moonlight".toCharArray())
        assertTrue(result is MigrationReadResult.Success)
        (result as MigrationReadResult.Success).use {
            assertEquals("wss://interop.example.invalid/gadget", it.snapshot.endpoints.single().url)
            assertEquals(MigrationTheme.MOONLIGHT, it.snapshot.appearance)
            assertEquals(listOf("box", "manual"), it.snapshot.sourceOrder)
            assertEquals("box:bot", it.snapshot.widgets.single().slots.first())
            assertEquals(it.snapshot.artwork.map { a -> a.sha256 }.toSet(), it.artwork.keys)
        }
    }
}
