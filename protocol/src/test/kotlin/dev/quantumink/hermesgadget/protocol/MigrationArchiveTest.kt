package dev.quantumink.hermesgadget.protocol

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationArchiveTest {
    private val password = "synthetic passphrase!".toCharArray()
    private val key = "box:bot"
    private val id = "00000000-0000-4000-8000-000000000001"
    private val png = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()
    private val pngHash = MigrationArchive.sha256(png)
    private val gif = GifPortrait.normalize(
        File("protocol/src/test/resources/gif/original-two-frame.gif").readBytes()
    ).bytes
    private val gifHash = MigrationArchive.sha256(gif)
    private val lan = "ws://10.0.0.2:8080/"
    private val manual = "manual:" + MigrationArchive.sha256(lan.toByteArray())

    private fun snapshot(): MigrationSnapshot = MigrationSnapshot(
        endpoints = listOf(
            SetupEndpoint("Bot", "wss://gadget.example.test/"),
            SetupEndpoint("Lan", lan, TransportPreference.DIRECT)
        ),
        catalogs = listOf(SetupCatalog("box", "Box", "https://catalog.example.test/catalog")),
        profiles = listOf(
            MigrationProfile(
                key,
                "Bot",
                "wss://gadget.example.test/",
                pngHash,
                pngHash,
                pngHash,
                gifHash,
                pngHash
            ),
            MigrationProfile(manual, "Lan", null, fallbackPortrait = pngHash)
        ),
        sourceOrder = listOf("box", "manual"), profileOrder = listOf(manual, key),
        appearance = MigrationTheme.MOONLIGHT,
        actions = listOf(QuickAction(id, key, "Review", "Review {{topic}}")),
        widgets = MigrationWidgetPreset.entries.mapIndexed { index, preset ->
            MigrationWidget(
                "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
                preset,
                listOf(key) + List(preset.capacity - 1) {
                    null
                },
                MigrationWidgetTheme.MOONLIGHT
            )
        },
        artwork = listOf(
            MigrationArtwork(pngHash, png.size, MigrationArtworkFormat.PNG),
            MigrationArtwork(gifHash, gif.size, MigrationArtworkFormat.GIF)
        )
    )
    private fun art(): Map<String, ByteArray> = mapOf(pngHash to png, gifHash to gif)
    private fun plain(): ByteArray = MigrationArchive.manifest(MigrationSnapshot())
    private fun rejectZip(bytes: ByteArray) {
        assertThrows(Exception::class.java) { MigrationArchive.decodeZip(bytes) }
    }
    private fun rejectManifest(text: String) {
        assertThrows(Exception::class.java) { MigrationArchive.parseManifest(text.toByteArray()) }
    }

    @Test fun encryptedRoundtripPreservesAllTemplatesReferencesAndBytes() {
        val expected = snapshot()
        val encrypted = MigrationArchive.encrypt(expected, password, art())
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("gadget.example.test"))
        val result = MigrationArchive.decrypt(encrypted, password) as MigrationReadResult.Success
        result.use {
            assertEquals(expected, it.snapshot)
            assertEquals(7, it.snapshot.widgets.size)
            assertArrayEquals(png, it.artwork.getValue(pngHash))
            assertArrayEquals(gif, it.artwork.getValue(gifHash))
        }
        assertTrue(result.artwork.values.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(png.any { it != 0.toByte() }) // Caller inputs remain owned by caller.
    }

    @Test fun encryptionUsesFreshSaltNonceAndRealJcaAes256Gcm() {
        val first = MigrationArchive.encrypt(MigrationSnapshot(), password)
        val second = MigrationArchive.encrypt(MigrationSnapshot(), password)
        assertNotEquals(first.toList(), second.toList())
        assertNotEquals(first.slice(24..39), second.slice(24..39))
        assertNotEquals(first.slice(40..51), second.slice(40..51))
        assertEquals("HGMIGR01", first.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        val header = first.copyOfRange(0, 52)
        val opened = cipher(Cipher.DECRYPT_MODE, header).doFinal(first, 52, first.size - 52)
        try {
            assertEquals(MigrationSnapshot(), MigrationArchive.decodeZip(opened).snapshot)
        } finally {
            opened.fill(0)
        }
    }

    @Test fun wrongPasswordAndAllTamperClassesHaveOneTypedFailure() {
        val encrypted = MigrationArchive.encrypt(snapshot(), password, art())
        assertSame(
            MigrationReadResult.Invalid,
            MigrationArchive.decrypt(encrypted, "incorrect password!".toCharArray())
        )
        // Includes algorithm, work factor, authenticated length, salt, nonce, body and tag.
        for (offset in listOf(
            0, 8, 9, 10, 11, 12, 15, 19, 23, 24, 39, 40, 51, 52,
            encrypted.size / 2, encrypted.lastIndex
        )) {
            val changed = encrypted.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            assertSame(
                "offset=$offset",
                MigrationReadResult.Invalid,
                MigrationArchive.decrypt(changed, password)
            )
        }
    }

    @Test fun truncationAndTrailingEnvelopeBytesHaveNoPartialResult() {
        val encrypted = MigrationArchive.encrypt(snapshot(), password, art())
        for (length in listOf(0, 7, 51, 52, encrypted.size - 16, encrypted.size - 1)) {
            assertSame(
                MigrationReadResult.Invalid,
                MigrationArchive.decrypt(encrypted.copyOf(length), password)
            )
        }
        assertSame(
            MigrationReadResult.Invalid,
            MigrationArchive.decrypt(encrypted + byteArrayOf(0), password)
        )
    }

    @Test fun hostileHeaderIsRejectedBeforeBodyReadOrAttackerSelectedWork() {
        val valid = MigrationArchive.encrypt(MigrationSnapshot(), password).copyOfRange(0, 52)
        for ((offset, number) in listOf(
            12 to 0,
            12 to 209999,
            12 to 210001,
            12 to Int.MAX_VALUE,
            16 to Int.MAX_VALUE,
            20 to Int.MAX_VALUE
        )) {
            val header = valid.copyOf().also { big(it).putInt(offset, number) }
            var read = 0
            val input = object : InputStream() {
                override fun read(): Int {
                    if (read >= 52) throw AssertionError("Read body of rejected envelope")
                    return header[read++].toInt() and 255
                }
            }
            assertSame(MigrationReadResult.Invalid, MigrationArchive.read(input, password))
            assertEquals(52, read)
        }
    }

    @Test fun shortPassphrasesAndOversizedInputsFailSafely() {
        assertThrows(Exception::class.java) {
            MigrationArchive.encrypt(MigrationSnapshot(), CharArray(11) { 'x' })
        }
        assertThrows(Exception::class.java) {
            MigrationArchive.encrypt(MigrationSnapshot(), CharArray(1025) { 'x' })
        }
        assertSame(
            MigrationReadResult.Invalid,
            MigrationArchive.decrypt(byteArrayOf(), CharArray(11))
        )
        assertSame(
            MigrationReadResult.Invalid,
            MigrationArchive.decrypt(ByteArray(MigrationArchive.MAX_ENVELOPE_BYTES + 1), password)
        )
    }

    @Test fun streamedEnvelopeSupportsBoundedPartialReads() {
        val encrypted = MigrationArchive.encrypt(MigrationSnapshot(), password)
        val stream = object : ByteArrayInputStream(encrypted) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(3, length))
        }
        assertEquals(
            MigrationSnapshot(),
            (MigrationArchive.read(stream, password) as MigrationReadResult.Success).snapshot
        )
    }

    @Test fun allowlistProjectionExcludesNativeCredentialsAndCleartextConsent() {
        val native =
            EndpointProfile(
                Endpoint.parse("ws://10.0.0.1/", true, true),
                "Bot",
                "ACCESS_SENTINEL",
                TransportPreference.DIRECT
            )
        val source =
            CatalogSource("box", "Box", "https://catalog.example.test/", "CATALOG_SENTINEL")
        val value =
            MigrationSnapshot(
                endpoints = listOf(SetupEndpoint.from(native)),
                catalogs = listOf(SetupCatalog.from(source)),
                sourceOrder = listOf("manual", "box")
            )
        val text = MigrationArchive.manifest(value).toString(Charsets.UTF_8)
        for (forbidden in listOf(
            "SENTINEL", "credential", "cleartext", "private", "identity", "session", "provider",
            "token", "approval", "history", "task", "PendingIntent"
        )) {
            assertFalse(forbidden, text.contains(forbidden))
        }
        assertEquals(value, MigrationArchive.parseManifest(text.toByteArray()))
    }

    @Test fun unknownFieldsIncludingIdentityAuthProviderConsentAndNativeWidgetIdsAreRejected() {
        val text = MigrationArchive.manifest(snapshot()).toString(Charsets.UTF_8)
        for (field in listOf(
            "identity", "token", "provider", "cleartext", "private", "history", "tasks",
            "approvals", "extensions"
        )) {
            rejectManifest(text.dropLast(1) + ",\"$field\":{}}")
            rejectManifest(
                text.replace("\"transport\":\"AUTO\"", "\"transport\":\"AUTO\",\"$field\":true")
            )
        }
        rejectManifest(
            text.replace("\"preset\":\"FOCUS\"", "\"preset\":\"FOCUS\",\"appWidgetId\":42")
        )
        rejectManifest(
            text.replace("\"name\":\"Bot\"", "\"name\":\"Bot\",\"device_key\":\"secret\"")
        )
        rejectManifest(
            text.replace(
                "\"url\":\"https://catalog.example.test/catalog\"",
                "\"url\":\"https://catalog.example.test/catalog\",\"credential\":\"secret\""
            )
        )
    }

    @Test fun duplicateJsonKeysEscapedAliasesAndTrailingJsonAreRejected() {
        val text = MigrationArchive.manifest(snapshot()).toString(Charsets.UTF_8)
        rejectManifest(text.replace("\"version\":1", "\"version\":1,\"version\":1"))
        rejectManifest(text.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"))
        rejectManifest(text.replace("\"name\":\"Bot\"", "\"name\":\"Bot\",\"name\":\"Other\""))
        rejectManifest(text + "{}")
    }

    @Test fun manifestVersionsTypesThemeLayoutAndIdsAreStrict() {
        val text = MigrationArchive.manifest(snapshot()).toString(Charsets.UTF_8)
        for (candidate in listOf(
            text.replace("\"version\":1", "\"version\":2"),
            text.replace("\"version\":1", "\"version\":\"1\""),
            text.replace("\"version\":1", "\"version\":1.0"),
            text.replace("\"appearance\":\"MOONLIGHT\"", "\"appearance\":\"SYSTEM\""),
            text.replace("\"preset\":\"FOCUS\"", "\"preset\":\"5x5\""),
            text.replace(id, "42"),
            text.replace("\"key\":\"box:bot\"", "\"key\":\"bad/key\""),
            text.replace("\"name\":\"Bot\"", "\"name\":null"),
            text.replace("\"appearance\":", "\"watch_theme\":\"DARK\",\"appearance\":")
        )) {
            rejectManifest(candidate)
        }
    }

    @Test fun metadataByteDepthAndMalformedUtf8BoundsAreEnforced() {
        assertThrows(Exception::class.java) {
            MigrationArchive.parseManifest(ByteArray(512 * 1024 + 1))
        }
        rejectManifest("[".repeat(1000) + "0" + "]".repeat(1000))
        assertThrows(Exception::class.java) {
            MigrationArchive.parseManifest(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        }
        rejectManifest(plain().toString(Charsets.UTF_8).replace("DARK", "\\uD800"))
    }

    @Test fun duplicateEntitiesAndNonResolvingReferencesCannotBeConstructed() {
        val value = snapshot()
        val bot = value.profiles.first()
        val lanProfile = value.profiles.last()
        val cases = listOf<() -> Any>(
            { value.copy(endpoints = value.endpoints + value.endpoints) },
            { value.copy(catalogs = value.catalogs + value.catalogs) },
            { value.copy(profiles = value.profiles + value.profiles) },
            { value.copy(actions = value.actions + value.actions) },
            { value.copy(widgets = value.widgets + value.widgets) },
            { value.copy(artwork = value.artwork + value.artwork) },
            { value.copy(sourceOrder = listOf("manual", "manual")) },
            { value.copy(sourceOrder = listOf("manual")) },
            { value.copy(profileOrder = listOf("box:missing", manual)) },
            { value.copy(profileOrder = listOf(key)) },
            { value.copy(profiles = listOf(bot.copy(key = "missing:bot"), lanProfile)) },
            { value.copy(actions = listOf(value.actions.single().copy(profileKey = "box:gone"))) },
            {
                value.copy(widgets = listOf(value.widgets.first().copy(slots = listOf("box:gone"))))
            },
            { value.copy(widgets = listOf(value.widgets.first().copy(slots = emptyList()))) },
            {
                value.copy(
                    widgets = listOf(value.widgets[1].copy(slots = listOf(key, key, null, null)))
                )
            },
            { value.copy(profiles = listOf(bot.copy(portrait = "0".repeat(64)), lanProfile)) },
            { value.copy(profiles = listOf(bot.copy(portrait = gifHash), lanProfile)) },
            { value.copy(profiles = listOf(bot.copy(animationFallback = null), lanProfile)) },
            {
                value.copy(
                    artwork =
                    value.artwork + MigrationArtwork("0".repeat(64), 1, MigrationArtworkFormat.PNG)
                )
            }
        )
        for ((index, candidate) in cases.withIndex()) {
            assertThrows("case=$index", IllegalArgumentException::class.java) { candidate() }
        }
    }

    @Test fun manualProfilesBindToExactlyOneExportedEndpointIdentity() {
        val value = snapshot()
        val bot = value.profiles.first()
        val lanProfile = value.profiles.last()
        val other = "manual:" + MigrationArchive.sha256("ws://10.0.0.3/".toByteArray())
        for (candidate in listOf<() -> Any>(
            {
                value.copy(
                    profiles = listOf(bot, lanProfile.copy(key = other)),
                    profileOrder = listOf(other, key)
                )
            },
            { value.copy(profiles = listOf(bot, lanProfile.copy(gadgetUrl = lan))) },
            { value.copy(endpoints = value.endpoints.take(1)) },
            { MigrationProfile("manual:" + "A".repeat(64), "Lan", null) }
        )) {
            assertThrows(IllegalArgumentException::class.java) { candidate() }
        }
        val tls = "wss://tls.example.test/"
        val tlsKey = "manual:" + MigrationArchive.sha256(tls.toByteArray())
        val accepted = value.copy(
            endpoints = value.endpoints + SetupEndpoint("Tls", tls),
            profiles = value.profiles + MigrationProfile(tlsKey, "Tls", tls),
            profileOrder = value.profileOrder + tlsKey
        )
        assertEquals(accepted, MigrationArchive.parseManifest(MigrationArchive.manifest(accepted)))
        assertThrows(IllegalArgumentException::class.java) {
            accepted.copy(
                profiles = value.profiles + MigrationProfile(tlsKey, "Tls", null)
            )
        }
    }

    @Test fun ownerFallbackPicturesRequireAnEndpointBinding() {
        val value = snapshot()
        val bot = value.profiles.first()
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(profiles = listOf(bot.copy(gadgetUrl = null), value.profiles.last()))
        }
        val unbound = value.copy(
            profiles = listOf(
                bot.copy(gadgetUrl = null, fallbackPortrait = null),
                value.profiles.last()
            )
        )
        assertEquals(unbound, MigrationArchive.parseManifest(MigrationArchive.manifest(unbound)))
    }

    @Test fun appearanceIsPhoneLocalAndNoWatchThemeIsInvented() {
        val text = MigrationArchive.manifest(snapshot()).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"appearance\":\"MOONLIGHT\""))
        assertEquals(
            setOf(
                "version", "endpoints", "catalogs", "profiles", "source_order", "profile_order",
                "appearance", "actions", "widgets", "artwork"
            ),
            (kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject).keys
        )
        assertFalse(text.contains("watch_theme") || text.contains("phone_theme"))
    }

    @Test fun catalogBoxPicturesLargerThanPhoneNormalizationRemainExportable() {
        val large = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val hash = MigrationArchive.sha256(large)
        val value = MigrationSnapshot(
            catalogs = listOf(SetupCatalog("box", "Box", "https://catalog.example.test/")),
            profiles = listOf(MigrationProfile("box:big", "Big", null, portrait = hash)),
            sourceOrder = listOf("box", "manual"),
            profileOrder = listOf("box:big"),
            artwork = listOf(MigrationArtwork(hash, large.size, MigrationArtworkFormat.PNG))
        )
        MigrationArchive.decodeZip(MigrationArchive.encodeZip(value, mapOf(hash to large))).use {
            assertArrayEquals(large, it.artwork.getValue(hash))
        }
        val wide = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(4097, 1, BufferedImage.TYPE_BYTE_GRAY), "png", it)
        }.toByteArray()
        val wideHash = MigrationArchive.sha256(wide)
        val rejected = value.copy(
            profiles = listOf(MigrationProfile("box:big", "Big", null, portrait = wideHash)),
            artwork = listOf(MigrationArtwork(wideHash, wide.size, MigrationArtworkFormat.PNG))
        )
        assertThrows(IllegalArgumentException::class.java) {
            MigrationArchive.encodeZip(rejected, mapOf(wideHash to wide))
        }
    }

    @Test fun keyDerivationIsRfcPbkdf2HmacSha256OverStrictUtf8() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            MigrationArchive.deriveKey(
                "passwd".toCharArray(),
                "salt".toByteArray(),
                1
            ).transferHex()
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            MigrationArchive.deriveKey("Password".toCharArray(), "NaCl".toByteArray(), 80000)
                .transferHex()
        )
        // Independent Python hashlib vector at the fixed production work factor.
        assertEquals(
            "92b02f4b9d5cb8aacac3fbbe41ce7962651bbf19d39577ec7185da1afbc941f5",
            MigrationArchive.deriveKey(
                "pässwörd 月光 moon".toCharArray(),
                ByteArray(16) { it.toByte() },
                MigrationArchive.KDF_ITERATIONS
            ).transferHex()
        )
    }

    @Test fun nonAsciiPassphrasesRoundtripAndUnpairedSurrogatesAreRejected() {
        val unicode = "pässwörd 月光 moon".toCharArray()
        val encrypted = MigrationArchive.encrypt(snapshot(), unicode, art())
        (MigrationArchive.decrypt(encrypted, unicode) as MigrationReadResult.Success).use {
            assertEquals(snapshot(), it.snapshot)
        }
        val lone = "twelve chars\uD800".toCharArray()
        assertThrows(IllegalArgumentException::class.java) {
            MigrationArchive.encrypt(MigrationSnapshot(), lone)
        }
        assertSame(MigrationReadResult.Invalid, MigrationArchive.decrypt(encrypted, lone))
    }

    @Test fun dishonestDeclaredBodyLengthFailsAtActualStreamEnd() {
        val header = MigrationArchive.encrypt(MigrationSnapshot(), password).copyOfRange(0, 52)
        val maxZip = MigrationArchive.MAX_ENVELOPE_BYTES - 52 - 16
        big(header).putInt(16, maxZip).putInt(20, maxZip + 16)
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int = if (read <
                60
            ) {
                (header.getOrElse(read++) { 0 }.toInt() and 255)
            } else {
                -1
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (read >= 60) return -1
                bytes[offset] = header.getOrElse(read++) { 0 }
                return 1
            }
        }
        assertSame(MigrationReadResult.Invalid, MigrationArchive.read(input, password))
        assertEquals(60, read)
    }

    @Test fun nativeCollectionAndActionLimitsAreReusedAtExactBoundaries() {
        fun endpoints(count: Int) = MigrationSnapshot(
            endpoints = List(count) { SetupEndpoint("Bot", "wss://b$it.example.test/") }
        )
        fun catalogs(count: Int) = List(count) {
            SetupCatalog("box$it", "Box", "https://b$it.example.test/")
        }.let { MigrationSnapshot(catalogs = it, sourceOrder = it.map { c -> c.id } + "manual") }
        fun profiles(count: Int) = List(count) { MigrationProfile("box:p$it", "Bot", null) }.let {
            MigrationSnapshot(
                catalogs = listOf(SetupCatalog("box", "Box", "https://b.example.test/")),
                profiles = it,
                sourceOrder = listOf("box", "manual"),
                profileOrder = it.map { p -> p.key }
            )
        }
        fun actions(count: Int) = MigrationSnapshot(
            actions = List(count) {
                QuickAction(
                    "00000000-0000-4000-8000-" + it.toString().padStart(12, '0'),
                    null,
                    "A",
                    "R"
                )
            }
        )
        fun widgets(count: Int) = MigrationSnapshot(
            widgets = List(count) {
                MigrationWidget(
                    "00000000-0000-4000-8000-" + it.toString().padStart(12, '0'),
                    MigrationWidgetPreset.FOCUS,
                    listOf(null)
                )
            }
        )
        for ((build, limit) in listOf<Pair<(Int) -> MigrationSnapshot, Int>>(
            ::endpoints to EndpointProfiles.MAX_PROFILES,
            ::catalogs to ProfileCatalog.MAX_SOURCES,
            ::profiles to ProfileCatalog.MAX_PROFILES,
            ::actions to QuickActions.MAX_ACTIONS,
            ::widgets to MigrationArchive.MAX_WIDGETS
        )) {
            val accepted = build(limit)
            assertEquals(
                accepted,
                MigrationArchive.parseManifest(MigrationArchive.manifest(accepted))
            )
            assertThrows("limit=$limit", IllegalArgumentException::class.java) { build(limit + 1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationSnapshot(
                artwork = List(129) {
                    MigrationArtwork(it.toString().padStart(64, '0'), 1, MigrationArtworkFormat.PNG)
                }
            )
        }
        assertThrows(Exception::class.java) { QuickAction(id, null, "Action", "{{INVALID}}") }
        assertThrows(Exception::class.java) {
            MigrationArtwork(
                pngHash,
                MigrationArchive.MAX_ARTWORK_BYTES + 1,
                MigrationArtworkFormat.PNG
            )
        }
    }

    @Test fun actualArtworkSizeHashTypeAndReferenceClosureAreChecked() {
        val value = snapshot()
        assertThrows(Exception::class.java) { MigrationArchive.encodeZip(value, emptyMap()) }
        assertThrows(Exception::class.java) {
            MigrationArchive.encodeZip(
                value,
                art() + ("0".repeat(64) to png)
            )
        }
        assertThrows(Exception::class.java) {
            MigrationArchive.encodeZip(
                value,
                art() + (pngHash to png.copyOf(png.size - 1))
            )
        }
        assertThrows(Exception::class.java) {
            MigrationArchive.encodeZip(
                value,
                art() + (pngHash to ByteArray(png.size))
            )
        }
        val bytes = MigrationArchive.encodeZip(value, art())
        val decoded = MigrationArchive.decodeZip(bytes)
        decoded.use { assertArrayEquals(png, it.artwork.getValue(pngHash)) }
    }

    @Test fun invalidPngGifAndOversizedNativeArtworkAreRejected() {
        fun rejectArt(data: ByteArray, format: MigrationArtworkFormat) {
            val hash = MigrationArchive.sha256(data)
            val profile = if (format ==
                MigrationArtworkFormat.PNG
            ) {
                MigrationProfile("box:p", "Bot", null, portrait = hash)
            } else {
                MigrationProfile(
                    "box:p",
                    "Bot",
                    null,
                    portrait = pngHash,
                    animation = hash,
                    animationFallback = pngHash
                )
            }
            val descriptors = listOf(MigrationArtwork(hash, data.size, format)) +
                if (format ==
                    MigrationArtworkFormat.GIF
                ) {
                    listOf(MigrationArtwork(pngHash, png.size, MigrationArtworkFormat.PNG))
                } else {
                    emptyList()
                }
            val snapshot =
                MigrationSnapshot(
                    catalogs = listOf(SetupCatalog("box", "Box", "https://b.example.test/")),
                    profiles = listOf(profile),
                    sourceOrder = listOf("box", "manual"),
                    profileOrder = listOf("box:p"),
                    artwork = descriptors
                )
            assertThrows(Exception::class.java) {
                MigrationArchive.encodeZip(
                    snapshot,
                    mapOf(hash to data) +
                        if (format ==
                            MigrationArtworkFormat.GIF
                        ) {
                            mapOf(pngHash to png)
                        } else {
                            emptyMap()
                        }
                )
            }
        }
        rejectArt(ByteArray(60), MigrationArtworkFormat.PNG)
        rejectArt(png + byteArrayOf(0), MigrationArtworkFormat.PNG)
        rejectArt(gif.copyOf(gif.size - 1), MigrationArtworkFormat.GIF)
        rejectArt(ByteArray(ProfileCatalog.MAX_PORTRAIT_BYTES + 1), MigrationArtworkFormat.PNG)
    }

    @Test fun storedAndDeflatedZipWithDataDescriptorsAreAccepted() {
        for (deflated in listOf(false, true)) {
            val bytes = zip(listOf("manifest.json" to plain()), deflated)
            assertEquals(MigrationSnapshot(), MigrationArchive.decodeZip(bytes).snapshot)
        }
    }

    @Test fun traversalAbsoluteBackslashNulDirectoryAndUnexpectedMembersAreRejected() {
        for (name in listOf(
            "../manifest.json",
            "/manifest.json",
            "artwork/../evil",
            "artwork\\evil",
            "bad\u0000name",
            "artwork/",
            "extra.txt"
        )) {
            rejectZip(zip(listOf("manifest.json" to plain(), name to byteArrayOf(1))))
        }
    }

    @Test fun duplicateLocalAndCentralMembersAreRejected() {
        // JDK writer forbids duplicate names; patch equal-length names in both records afterwards.
        val bytes = zip(listOf("manifest.json" to plain(), "manifesz.json" to plain()))
        val target = "manifesz.json".toByteArray()
        for (offset in 0..bytes.size - target.size) {
            if (bytes.copyOfRange(offset, offset + target.size).contentEquals(target)) {
                bytes[
                    offset +
                        7
                ] =
                    't'.code.toByte()
            }
        }
        rejectZip(bytes)
    }

    @Test fun trailingZipBytesHiddenLocalPayloadAndCommentsAreRejected() {
        val valid = zip(listOf("manifest.json" to plain()))
        rejectZip(valid + byteArrayOf(1))
        rejectZip(valid + valid)
        rejectZip(valid.copyOf(valid.size - 1))
        val commented = valid + byteArrayOf('x'.code.toByte())
        little(commented).putShort(valid.size - 2, 1)
        rejectZip(commented)
        val prefixed = byteArrayOf(0) + valid
        // Even an otherwise adjusted directory cannot hide a preamble/local prefix.
        little(prefixed).putInt(prefixed.size - 6, central(prefixed))
        rejectZip(prefixed)
    }

    @Test fun symlinkDirectoryAttributesEncryptionZip64AndExtraFieldsAreRejected() {
        val valid = zip(listOf("manifest.json" to plain()))
        val central = central(valid)
        for ((offset, number) in listOf(
            central + 38 to (0xa1ff shl 16),
            central + 38 to 0x10,
            central + 20 to -1,
            central + 42 to -1
        )) {
            rejectZip(valid.copyOf().also { little(it).putInt(offset, number) })
        }
        for ((offset, number) in listOf(
            central + 8 to 1,
            central + 6 to 45,
            central + 30 to 1,
            28 to 1
        )) {
            rejectZip(valid.copyOf().also { little(it).putShort(offset, number.toShort()) })
        }
    }

    @Test fun dishonestSizesOverlapsCorruptionAndMissingMembersAreRejected() {
        val valid = zip(listOf("manifest.json" to plain()))
        val central = central(valid)
        for ((offset, number) in listOf(
            central + 24 to Int.MAX_VALUE,
            central + 20 to 1,
            central + 42 to 1,
            22 to 1,
            18 to 1
        )) {
            rejectZip(valid.copyOf().also { little(it).putInt(offset, number) })
        }
        rejectZip(valid.copyOf().also { it[43] = (it[43].toInt() xor 1).toByte() })
        rejectZip(zip(listOf("manifest.json" to MigrationArchive.manifest(snapshot()))))
        rejectZip(zip(listOf("manifest.json" to plain(), "artwork/$pngHash.png" to png)))
    }

    @Test fun compressionBombWithFalseSmallLengthStopsAtActualBound() {
        val bomb =
            zip(
                listOf(
                    "manifest.json" to ByteArray(2 * 1024 * 1024) {
                        ' '.code.toByte()
                    }
                ),
                deflated = true
            )
        val central = central(bomb)
        rejectZip(bomb) // Honest oversize declaration fails before inflate.
        val dishonest = bomb.copyOf()
        little(dishonest).putInt(central + 24, 32)
        little(dishonest).putInt(central - 4, 32) // Signed data descriptor's uncompressed size.
        rejectZip(dishonest) // Actual inflate exceeds 32 before any output is returned.
    }

    @Test fun aggregateEntryAndMemberQuotasAreCheckedBeforeMemberWork() {
        val name = "artwork/${"0".repeat(64)}.png"
        val member = zip(listOf("manifest.json" to plain(), name to byteArrayOf(1)))
        val second = central(member, 1)
        rejectZip(member.copyOf().also { little(it).putInt(second + 24, 8 * 1024 * 1024 + 1) })
        val oversizedCount = zip((0..256).map { index -> "x$index" to byteArrayOf(1) })
        rejectZip(oversizedCount)
        val aggregate = zip(
            listOf("manifest.json" to plain()) + (0..4).map {
                "artwork/${it.toString().padStart(64, '0')}.png" to byteArrayOf(1)
            }
        )
        for (index in 1..5) {
            val c = central(aggregate, index)
            val local = little(aggregate).getInt(c + 42)
            little(aggregate).putInt(c + 24, 8 * 1024 * 1024)
            little(aggregate).putInt(local + 22, 8 * 1024 * 1024)
        }
        rejectZip(aggregate)
    }

    @Test fun corruptDeflateTruncatedDescriptorAndCompressedTrailingDataAreRejected() {
        val valid = zip(listOf("manifest.json" to plain()), deflated = true)
        rejectZip(valid.copyOf().also { it[43] = 0x7f })
        val central = central(valid)
        rejectZip(valid.copyOf().also { little(it).putInt(central - 16, 0) })
        rejectZip(
            valid.copyOf().also {
                little(it).putInt(
                    central + 20,
                    little(it).getInt(central + 20) - 1
                )
            }
        )
    }

    @Test fun authenticatedButHostileArchiveStillReturnsOnlyInvalid() {
        val template = MigrationArchive.encrypt(MigrationSnapshot(), password).copyOfRange(0, 52)
        for (hostile in listOf(
            zip(listOf("manifest.json" to plain(), "../secret" to byteArrayOf(1))),
            zip(listOf("manifest.json" to "{\"version\":1,\"version\":1}".toByteArray())),
            zip(listOf("manifest.json" to plain())) + byteArrayOf(0)
        )) {
            val header = template.copyOf().also {
                big(it).putInt(16, hostile.size).putInt(
                    20,
                    hostile.size + 16
                )
            }
            val sealed = header + cipher(Cipher.ENCRYPT_MODE, header).doFinal(hostile)
            assertSame(MigrationReadResult.Invalid, MigrationArchive.decrypt(sealed, password))
        }
    }

    private fun cipher(mode: Int, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password, header.copyOfRange(24, 40), 210000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    mode,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, header.copyOfRange(40, 52))
                )
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }
    private fun zip(members: List<Pair<String, ByteArray>>, deflated: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            members.forEach { (name, data) ->
                val entry = ZipEntry(name).apply {
                    time = 946684800000L
                    if (!deflated) {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().also { it.update(data) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun central(bytes: ByteArray, index: Int = 0): Int {
        var cursor = little(bytes).getInt(bytes.size - 6)
        repeat(index) { cursor += 46 + little(bytes).getShort(cursor + 28).toInt() }
        return cursor
    }
    private fun little(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun big(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
}
