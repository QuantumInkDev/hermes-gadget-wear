package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionStoreTest {
    private fun withStore(test: (Context, File, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "quick-actions-test-" + UUID.randomUUID())
        val alias = "test.quick-actions." + UUID.randomUUID()
        try {
            test(context, directory, alias)
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test fun restartEditOrderDeletionAndNoPlaintext() = withStore { context, directory, alias ->
        val store = QuickActionStore(context, directory, alias)
        val seeds = store.load()
        assertEquals(6, seeds.size)
        val edited = seeds[1].copy(profileKey = "box:bot", template = "Private thought {{thought}}")
        store.save(listOf(edited, seeds[0]))
        assertEquals(listOf(edited, seeds[0]), QuickActionStore(context, directory, alias).load())
        val encrypted = File(directory, "actions").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encrypted.contains("Private thought"))
        assertFalse(encrypted.contains("box:bot"))
        store.save(emptyList())
        assertEquals(emptyList<QuickAction>(), QuickActionStore(context, directory, alias).load())
    }

    @Test fun staleEditorCannotOverwriteANewerActionList() = withStore {
            context,
            directory,
            alias
        ->
        val first = QuickActionStore(context, directory, alias)
        val second = QuickActionStore(context, directory, alias)
        val initial = first.load()
        val edited = initial.toMutableList().apply { set(0, first().copy(title = "Updated")) }
        first.save(edited, expected = initial)
        assertTrue(runCatching { second.save(emptyList(), expected = initial) }.isFailure)
        assertEquals(edited, second.load())
        assertTrue(runCatching { second.save(listOf(edited.first(), edited.first())) }.isFailure)
        assertEquals(edited, first.load())
        File(directory, "actions").delete()
        assertTrue(runCatching { first.load() }.isFailure)
        assertTrue(runCatching { first.save(emptyList()) }.isFailure)
    }

    @Test fun tamperBoundsAndMissingKeyNeverResetSavedActions() = withStore {
            context,
            directory,
            alias
        ->
        val store = QuickActionStore(context, directory, alias)
        store.load()
        val record = File(directory, "actions")
        val original = record.readBytes()
        val corrupted = original.copyOf().apply {
            this[lastIndex] = (last().toInt() xor 1).toByte()
        }
        record.writeBytes(corrupted)
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(runCatching { store.save(emptyList()) }.isFailure)
        assertArrayEquals(corrupted, record.readBytes())
        record.writeBytes(ByteArray(QuickActions.MAX_BYTES + 31))
        assertTrue(runCatching { store.load() }.isFailure)
        record.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
        assertTrue(runCatching { QuickActionStore(context, directory, alias).load() }.isFailure)
        assertArrayEquals(original, record.readBytes())
    }

    @Test fun shortcutContainsOnlyOpaqueIdAndResolvesLatestOrMissingRecord() = withStore {
            context,
            directory,
            alias
        ->
        val store = QuickActionStore(context, directory, alias)
        val before = store.load()[0].copy(profileKey = "box:bot")
        store.save(listOf(before))
        val intent = QuickActionShortcuts.intent(context, before.id)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty())
        assertEquals(before.id, QuickActionShortcuts.parse(intent))
        assertFalse(intent.toUri(0).contains("box:bot"))
        store.save(listOf(before.copy(template = "Latest prompt")))
        assertEquals(
            "Latest prompt",
            store.load().single {
                it.id ==
                    QuickActionShortcuts.parse(intent)
            }.template
        )
        store.save(emptyList())
        assertNull(store.load().singleOrNull { it.id == QuickActionShortcuts.parse(intent) })
        assertNull(
            QuickActionShortcuts.parse(
                android.content.Intent(
                    intent
                ).setData(android.net.Uri.parse("relay-action://review/not-an-id"))
            )
        )
    }
}
