package dev.quantumink.hermesgadget.client

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    // Catches foreground/container mismatches and unreadable palette adaptation.
    @Test fun everyAppearanceHasReadableTextAndActionsOnPhoneAndWatch() {
        for (watch in listOf(false, true)) {
            Appearance.entries.forEach { mode ->
                val palette = appearanceTokens(mode, watch)
                listOf(
                    palette.text to palette.background,
                    palette.text to palette.surface,
                    palette.secondaryText to palette.surface,
                    palette.onAccent to palette.accent,
                    palette.onSupportingAccent to palette.supportingAccent,
                    palette.onAccentContainer to palette.accentContainer,
                    palette.onError to palette.error,
                    palette.onErrorContainer to palette.errorContainer
                ).forEach { (foreground, container) ->
                    val ratio = contrast(foreground, container)
                    assertTrue("$mode watch=$watch contrast=$ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun watchInteractiveCanvasHonorsLightAndBlackDarkModes() {
        assertEquals(0xfff7f8fc.toInt(), appearanceTokens(Appearance.LIGHT, true).background)
        assertEquals(0xff000000.toInt(), appearanceTokens(Appearance.DARK, true).background)
        assertEquals(0xff000000.toInt(), appearanceTokens(Appearance.MOONLIGHT, true).background)
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
