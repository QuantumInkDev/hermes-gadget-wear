package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual native controls and Android Keystore; every credential and endpoint is synthetic. */
class SpeechSettingsArtifactTest {
    private enum class Artifacts { BASE_AND_NEW, NEW_ONLY, BACKUP_AND_BASE }

    @Test fun savePreservesValidBaseAndCorruptNew() = rejected(Artifacts.BASE_AND_NEW, false)

    @Test fun deletePreservesValidBaseAndCorruptNew() = rejected(Artifacts.BASE_AND_NEW, true)

    @Test fun savePreservesCorruptNewOnlyWithExistingAlias() = rejected(Artifacts.NEW_ONLY, false)

    @Test fun deletePreservesCorruptNewOnlyWithExistingAlias() = rejected(Artifacts.NEW_ONLY, true)

    @Test fun savePreservesValidBackupAndCorruptBase() = rejected(Artifacts.BACKUP_AND_BASE, false)

    @Test fun deletePreservesValidBackupAndCorruptBase() = rejected(Artifacts.BACKUP_AND_BASE, true)

    private fun rejected(artifacts: Artifacts, deleting: Boolean) {
        assertTrue("Owned emulator only", isAndroidEmulator())
        NativePhoneFixture().use { fixture ->
            val directory = File(fixture.context.filesDir, "artifact-vault")
            val alias = "test.speech.artifact." + UUID.randomUUID()
            val vault = TtsVault(fixture.context, directory, alias)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val endpoint = Endpoint.parse("wss://artifact.example.invalid/gadget")
            val id = ClientSpeech.endpointId(endpoint)
            val page = CatalogPage(
                "synthetic",
                "Synthetic box",
                CatalogProfile("artifact", "Synthetic artifact profile", endpoint.url, null),
                CatalogStatus.OFFLINE,
                1
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val published = AtomicInteger()
            val session = SpeechSettingsSession(scope, vault::read, vault::save, {
                published.incrementAndGet()
                Unit
            }, { error("No quota action is authorized") })
            try {
                vault.save(
                    "shared",
                    SpeechSettings(SpeechMode.SHARED, "syntheticShared", "sharedVoice")
                )
                vault.save(id, SpeechSettings(SpeechMode.PROFILE, "syntheticOwn", "profileVoice"))
                val base = File(directory, "$id.sealed")
                val pending = File(directory, "$id.sealed.new")
                val corrupt = byteArrayOf(1, 12, 9, 8, 7)
                when (artifacts) {
                    Artifacts.BASE_AND_NEW -> pending.writeBytes(corrupt)
                    Artifacts.NEW_ONLY -> {
                        assertTrue(base.delete())
                        pending.writeBytes(corrupt)
                    }
                    Artifacts.BACKUP_AND_BASE -> {
                        base.copyTo(File(directory, "$id.sealed.bak"))
                        base.writeBytes(corrupt)
                    }
                }
                assertTrue(keys.containsAlias(alias))
                val before = bytes(directory)
                fixture.show(destination = "settings", scroll = true) {
                    SpeechSettingsPage(
                        fixture.context,
                        SpeechSettingsTarget(page, endpoint),
                        providedSession = session
                    ) {}
                }
                fixture.await { session.state.value.loaded && !session.state.value.busy }
                val action = if (deleting) "Delete per-profile key" else "Save server voice mode"
                fixture.scrollTo(action)
                fixture.click(action)
                fixture.await {
                    !session.state.value.busy && session.state.value.notice !in
                        setOf(SpeechNotice.LOADED, SpeechNotice.NONE)
                }
                assertEquals(
                    "$artifacts / $action changed artifact bytes",
                    before,
                    bytes(directory)
                )
                assertEquals(SpeechNotice.FAILED, session.state.value.notice)
                assertEquals(0, published.get())
                assertTrue(keys.containsAlias(alias))
            } finally {
                session.close()
                scope.cancel()
                directory.deleteRecursively()
                if (keys.containsAlias(alias)) keys.deleteEntry(alias)
            }
        }
    }

    private fun bytes(directory: File): Map<String, List<Byte>> =
        directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).path to it.readBytes().toList()
        }
}
