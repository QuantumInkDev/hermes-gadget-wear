package dev.quantumink.hermesgadget.mobile.widgets

import dev.quantumink.hermesgadget.protocol.CatalogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeWidgetModelTest {
    @Test fun requestedLayoutsAllocateTheirOrderedSlots() {
        val expected = listOf(1 to 1, 2 to 2, 2 to 4, 1 to 4, 4 to 4, 1 to 5, 2 to 5)
        assertEquals(expected, HomeWidgetPreset.entries.map { it.rows to it.columns })
        assertEquals(listOf(1, 4, 8, 4, 16, 5, 10), HomeWidgetPreset.entries.map { it.capacity })
        val config = WidgetConfig(HomeWidgetPreset.STRIP_FOUR, listOf("a:one", "b:one", null, null))
        assertEquals(listOf("a:one", "b:one", null, null), config.slots)
        assertEquals(WidgetAppearance.SYSTEM, config.appearance)
    }

    @Test fun duplicateAndMalformedKeysCannotChangeTheSelectedBot() {
        listOf(
            "https://host.invalid",
            "a:one:two",
            "a:../",
            "",
            "a:" + "x".repeat(65)
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                WidgetConfig(HomeWidgetPreset.FOCUS, listOf(bad))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetConfig(HomeWidgetPreset.FOUR, listOf("a:one", "a:one", null, null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetConfig(HomeWidgetPreset.FOCUS, listOf(null, null))
        }
    }

    @Test fun configurationRoundTripKeepsExactKeysThemeEmptySlotsAndRevision() {
        val value =
            WidgetConfig(
                HomeWidgetPreset.STRIP_FOUR,
                listOf("a:one", null, "b:one", null),
                WidgetAppearance.MOONLIGHT,
                7
            )
        val restored = WidgetJson.decodeConfig(WidgetJson.encodeConfig(value))
        assertEquals(value, restored)
        val encoded = WidgetJson.encodeConfig(value)
        assertFalse(encoded.contains("endpoint"))
        assertFalse(encoded.contains("credential"))
    }

    @Test fun malformedConfigurationFailsInsteadOfAcceptingAChangedTarget() {
        val valid = """{"version":1,"preset":"FOCUS","appearance":"SYSTEM","revision":0,""" +
            """"slots":["a:one"]}"""
        assertEquals("a:one", WidgetJson.decodeConfig(valid).slots.single())
        listOf(
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"a:one\"", "true"),
            valid.replace("\"revision\":0", "\"revision\":-1"),
            valid.replace("\"SYSTEM\"", "\"AUTO\""),
            valid.dropLast(1) + ",\"url\":\"secret\"}",
            "x".repeat(65537)
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { WidgetJson.decodeConfig(text) }
        }
    }

    @Test fun localProfilesKeepSourceIdentityAndWithdrawalWithoutEndpointData() {
        val profiles =
            listOf(
                WidgetProfile("a:one", "Orbit", "Studio", CatalogStatus.OFFLINE, "a".repeat(64)),
                WidgetProfile("b:one", "Orbit", "Lab", CatalogStatus.WITHDRAWN, null)
            )
        assertEquals(profiles, WidgetJson.decodeProfiles(WidgetJson.encodeProfiles(profiles)))
        assertThrows(IllegalArgumentException::class.java) {
            WidgetJson.encodeProfiles(profiles + profiles.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetProfile("a:one", "\n", "Studio", CatalogStatus.AVAILABLE, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetProfile("a:one", "Orbit", "Studio", CatalogStatus.AVAILABLE, "../bad")
        }
    }
}
